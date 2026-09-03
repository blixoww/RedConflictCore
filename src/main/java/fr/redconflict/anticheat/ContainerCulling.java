package fr.redconflict.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anti chest-ESP : le serveur cesse d'envoyer les coffres qu'on ne peut pas voir.
 *
 * <p><b>Pourquoi le masquage et pas la détection.</b> Un ESP de coffres n'émet
 * aucun paquet : il lit ce que le client a déjà reçu et le dessine à travers les
 * murs. Il n'y a donc rien à détecter — pas de distance anormale, pas de cadence,
 * pas de rotation impossible. La seule chose qui l'arrête, c'est de ne pas
 * envoyer la donnée. C'est le même raisonnement que {@link VisibilityCulling}
 * pour les joueurs, appliqué aux conteneurs.
 *
 * <p><b>Comment on masque un bloc sans toucher au monde.</b>
 * {@code sendBlockChange} envoie à UN joueur un bloc différent de celui qui
 * existe réellement. Le serveur garde le coffre, les autres joueurs le voient,
 * son contenu est intact ; seul le client visé croit qu'il y a de la pierre. Et
 * comme le client vanilla supprime la tuile associée quand le bloc change, le
 * coffre disparaît aussi de la liste des tuiles — la source que lisent la plupart
 * des ESP.
 *
 * <p><b>Pourquoi ça ne gêne pas un joueur honnête.</b> On ne masque que ce qui
 * est hors de vue ET au-delà du rayon de proximité. Or pour ouvrir un coffre, le
 * client doit d'abord le viser, donc l'avoir en vue — un coffre masqué est un
 * coffre qu'on ne pouvait de toute façon pas atteindre. Le retour est immédiat :
 * dès qu'une ligne de vue s'ouvre, le vrai bloc repart à la passe suivante.
 *
 * <p><b>Le compteur, en prime.</b> Le balayage sait, pour chaque joueur et chaque
 * conteneur, s'il a EU une ligne de vue dessus depuis qu'il est à portée. Ouvrir
 * ou casser un coffre entièrement muré sans l'avoir jamais vu est le geste du
 * chercheur de planques. Une fois c'est de la chance, dix fois non — d'où un
 * seuil, et une alerte plutôt qu'une sanction.
 */
public class ContainerCulling implements Listener {

    /** Durée de validité de l'index des conteneurs d'un chunk. */
    private static final long INDEX_TTL_MS = 60_000L;

    /** Reconstructions d'index autorisées par passe : borne le coût d'une arrivée. */
    private static final int INDEX_BUDGET = 6;

    private final Plugin plugin;
    private final ViolationTracker violations;
    private BukkitTask task;

    /** Conteneurs connus par chunk, avec la date de l'inventaire. */
    private final Map<Long, ChunkIndex> index = new HashMap<Long, ChunkIndex>();

    /** Ce que chaque joueur sait de chaque conteneur. */
    private final Map<UUID, Map<Long, Track>> tracks = new HashMap<UUID, Map<Long, Track>>();

    /** Inventaires de chunk reconstruits pendant la passe en cours. */
    private int rebuilds;

    public ContainerCulling(Plugin plugin, ViolationTracker violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    // ── Cycle de vie ───────────────────────────────────────────────────────────

    public void start() {
        if (!plugin.getConfig().getBoolean("anticheat.chest-esp.enabled", true)) {
            return;
        }
        long interval = Math.max(1, plugin.getConfig().getInt("anticheat.chest-esp.interval-ticks", 8));
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                sweep();
            }
        }, interval, interval);
        plugin.getLogger().info("[AC] Anti chest-ESP actif — masquage : "
                + plugin.getConfig().getBoolean("anticheat.chest-esp.mask", false) + ".");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Rendre tout ce qui est masqué : sinon un /reload laisserait des coffres
        // invisibles jusqu'au prochain rechargement de chunk.
        for (Player player : Bukkit.getOnlinePlayers()) {
            revealAll(player);
        }
        tracks.clear();
        index.clear();
    }

    public void forget(UUID player) {
        tracks.remove(player);
    }

    // ── Passe ──────────────────────────────────────────────────────────────────

    private void sweep() {
        if (!plugin.getConfig().getBoolean("anticheat.enabled", true)
                || !plugin.getConfig().getBoolean("anticheat.chest-esp.enabled", true)) {
            return;
        }
        final double radius = plugin.getConfig().getDouble("anticheat.chest-esp.radius", 24.0);
        final double close = plugin.getConfig().getDouble("anticheat.chest-esp.always-visible-radius", 5.0);
        final long hideDelay = plugin.getConfig().getLong("anticheat.chest-esp.hide-delay-ms", 1000L);
        final int budget = plugin.getConfig().getInt("anticheat.chest-esp.max-traces-per-pass", 1500);
        final boolean mask = plugin.getConfig().getBoolean("anticheat.chest-esp.mask", false);
        final List<String> disabled = plugin.getConfig().getStringList("anticheat.chest-esp.disabled-worlds");

        final double radiusSq = radius * radius;
        final double closeSq = close * close;
        final long now = System.currentTimeMillis();
        int traces = 0;
        rebuilds = 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (traces >= budget) {
                return;
            }
            if (exempt(player) || disabled.contains(player.getWorld().getName())) {
                continue;
            }
            Location eye = player.getEyeLocation();
            World world = player.getWorld();
            Map<Long, Track> mine = tracks.get(player.getUniqueId());

            int chunkRadius = (int) Math.ceil(radius / 16.0);
            int cx = player.getLocation().getBlockX() >> 4;
            int cz = player.getLocation().getBlockZ() >> 4;

            for (int ox = -chunkRadius; ox <= chunkRadius && traces < budget; ox++) {
                for (int oz = -chunkRadius; oz <= chunkRadius && traces < budget; oz++) {
                    if (!world.isChunkLoaded(cx + ox, cz + oz)) {
                        continue;
                    }
                    ChunkIndex containers = indexOf(world, cx + ox, cz + oz, now);
                    if (containers == null) {
                        continue; // pas d'inventaire disponible cette passe
                    }
                    for (Location container : containers.locations) {
                        double distSq = eye.distanceSquared(container);
                        if (distSq > radiusSq) {
                            continue;
                        }
                        if (mine == null) {
                            mine = new HashMap<Long, Track>();
                            tracks.put(player.getUniqueId(), mine);
                        }
                        Track track = mine.get(Long.valueOf(key(container)));
                        if (track == null) {
                            track = new Track(container, buried(container));
                            mine.put(Long.valueOf(key(container)), track);
                        }
                        track.touchedAt = now;

                        if (distSq <= closeSq) {
                            track.seen = true;
                            track.blindSince = 0L;
                            reveal(player, track);
                            continue;
                        }
                        if (traces++ >= budget) {
                            break;
                        }
                        if (clearLineOfSight(eye, container)) {
                            track.seen = true;
                            track.blindSince = 0L;
                            reveal(player, track);
                        } else {
                            if (track.blindSince == 0L) {
                                track.blindSince = now;
                            } else if (mask && now - track.blindSince >= hideDelay) {
                                conceal(player, track);
                            }
                        }
                    }
                }
            }
            prune(mine, now);
        }
    }

    /**
     * Oublie les conteneurs qu'on n'a plus croisés depuis longtemps.
     *
     * <p>Sans ça, la carte d'un joueur qui traverse la map grossit indéfiniment.
     * Un conteneur oublié repart d'une page blanche s'il revient à portée, et le
     * masque éventuel se dissipe tout seul : le client recharge le chunk avec le
     * vrai bloc dès qu'il l'a déchargé.
     */
    private static void prune(Map<Long, Track> mine, long now) {
        if (mine == null || mine.size() < 256) {
            return;
        }
        Iterator<Map.Entry<Long, Track>> iterator = mine.entrySet().iterator();
        while (iterator.hasNext()) {
            Track track = iterator.next().getValue();
            if (now - track.touchedAt > 120_000L && !track.masked) {
                iterator.remove();
            }
        }
    }

    /**
     * Ligne de vue de l'œil vers le conteneur.
     *
     * <p>Le tracé s'arrête UN bloc avant la cible : le conteneur lui-même occulte,
     * et se compter soi-même reviendrait à ne jamais voir aucun coffre.
     */
    private boolean clearLineOfSight(Location eye, Location container) {
        Location center = container.clone().add(0.5, 0.5, 0.5);
        Vector direction = center.toVector().subtract(eye.toVector());
        double length = direction.length();
        if (length < 1.0) {
            return true;
        }
        try {
            BlockIterator iterator = new BlockIterator(
                    eye.getWorld(), eye.toVector(), direction.normalize(), 0, (int) length);
            while (iterator.hasNext()) {
                Block block = iterator.next();
                if (block.getX() == container.getBlockX() && block.getY() == container.getBlockY()
                        && block.getZ() == container.getBlockZ()) {
                    return true;
                }
                Material type = block.getType();
                if (type != null && type.isOccluding()) {
                    return false;
                }
            }
            return true;
        } catch (IllegalStateException e) {
            return true; // vecteur dégénéré ou chunk absent : dans le doute, on montre
        }
    }

    // ── Masquage ───────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void conceal(Player player, Track track) {
        if (track.masked) {
            return;
        }
        Block block = track.at.getBlock();
        if (!isContainer(block.getType())) {
            return; // le conteneur a disparu entre deux passes
        }
        Block cover = coverOf(block);
        track.masked = true;
        player.sendBlockChange(track.at, cover.getType(), cover.getData());
    }

    @SuppressWarnings("deprecation")
    private void reveal(Player player, Track track) {
        if (!track.masked) {
            return;
        }
        track.masked = false;
        Block block = track.at.getBlock();
        player.sendBlockChange(track.at, block.getType(), block.getData());
    }

    private void revealAll(Player player) {
        Map<Long, Track> mine = tracks.get(player.getUniqueId());
        if (mine == null) {
            return;
        }
        for (Track track : mine.values()) {
            reveal(player, track);
        }
    }

    /**
     * Le bloc dont on prend l'apparence.
     *
     * <p>On copie le voisin plein le plus fréquent : un coffre enterré dans la
     * pierre devient de la pierre, un coffre dans un mur de bois devient du bois.
     * Un remplacement fixe se repérerait immédiatement — il suffirait de chercher
     * les blocs de pierre isolés dans une maison.
     */
    private Block coverOf(Block block) {
        Map<Material, Integer> votes = new HashMap<Material, Integer>();
        Block best = null;
        int bestVotes = 0;
        for (BlockFace face : new BlockFace[] {
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN }) {
            Block neighbour = block.getRelative(face);
            Material type = neighbour.getType();
            if (type == null || !type.isOccluding() || isContainer(type)) {
                continue;
            }
            Integer count = votes.get(type);
            int value = (count == null ? 0 : count.intValue()) + 1;
            votes.put(type, Integer.valueOf(value));
            if (value > bestVotes) {
                bestVotes = value;
                best = neighbour;
            }
        }
        return best != null ? best : block.getRelative(BlockFace.DOWN);
    }

    // ── Détection : la planque trouvée sans jamais l'avoir vue ─────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        Player player = (Player) event.getPlayer();
        if (holder instanceof DoubleChest) {
            DoubleChest chest = (DoubleChest) holder;
            judge(player, blockOf(chest.getLeftSide()));
            judge(player, blockOf(chest.getRightSide()));
        } else {
            judge(player, blockOf(holder));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        // Juger d'abord : invalider efface le suivi, donc la preuve.
        if (isContainer(event.getBlock().getType())) {
            judge(event.getPlayer(), event.getBlock().getLocation());
        }
        invalidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        invalidate(event.getBlock());
    }

    /**
     * Le joueur a-t-il atteint ce conteneur sans jamais l'avoir eu en vue ?
     *
     * <p>Trois conditions cumulatives, et c'est voulu : le conteneur devait être
     * ENTERRÉ quand on l'a indexé (une planque, pas un coffre de maison), le
     * joueur ne devait JAMAIS avoir eu de ligne de vue dessus, et il ne doit pas
     * en avoir une à l'instant même. Chacune seule produirait du bruit.
     */
    private void judge(Player player, Location container) {
        if (container == null || !plugin.getConfig().getBoolean("anticheat.chest-esp.detect", true)) {
            return;
        }
        Map<Long, Track> mine = tracks.get(player.getUniqueId());
        if (mine == null) {
            return;
        }
        Track track = mine.get(Long.valueOf(key(container)));
        if (track == null || track.seen || !track.buried) {
            return;
        }
        if (clearLineOfSight(player.getEyeLocation(), container)) {
            track.seen = true;
            return;
        }
        violations.flag(player, Check.CHEST_ESP, "conteneur muré atteint sans ligne de vue en "
                + container.getBlockX() + " " + container.getBlockY() + " " + container.getBlockZ());
    }

    /** Muré : au moins cinq des six faces sont pleines. C'est une planque. */
    private static boolean buried(Location at) {
        Block block = at.getBlock();
        int solid = 0;
        for (BlockFace face : new BlockFace[] {
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN }) {
            Material type = block.getRelative(face).getType();
            if (type != null && type.isOccluding()) {
                solid++;
            }
        }
        return solid >= 5;
    }

    private static Location blockOf(InventoryHolder holder) {
        if (holder instanceof BlockState) {
            return ((BlockState) holder).getBlock().getLocation();
        }
        return null;
    }

    // ── Index par chunk ────────────────────────────────────────────────────────

    /**
     * Les conteneurs d'un chunk, inventoriés paresseusement.
     *
     * <p>Parcourir les tuiles d'un chunk n'est pas gratuit ; le faire pour chaque
     * joueur et à chaque passe le serait beaucoup moins. L'inventaire est donc
     * partagé, daté, invalidé par les poses et les casses, et son nombre de
     * reconstructions est borné par passe : l'arrivée d'un joueur dans une base
     * neuve étale son coût sur quelques passes au lieu de le payer d'un coup.
     */
    private ChunkIndex indexOf(World world, int cx, int cz, long now) {
        long chunkKey = chunkKey(world, cx, cz);
        ChunkIndex cached = index.get(Long.valueOf(chunkKey));
        if (cached != null && now - cached.builtAt < INDEX_TTL_MS) {
            return cached;
        }
        if (rebuilds >= INDEX_BUDGET) {
            return cached; // périmé mais utilisable : mieux que rien cette passe
        }
        rebuilds++;
        List<Location> found = new ArrayList<Location>();
        try {
            Chunk chunk = world.getChunkAt(cx, cz);
            for (BlockState state : chunk.getTileEntities()) {
                if (state != null && isContainer(state.getType())) {
                    found.add(state.getLocation());
                }
            }
        } catch (Throwable ignored) {
            // Chunk déchargé pendant l'inventaire : on réessaiera.
        }
        ChunkIndex fresh = new ChunkIndex(now, found);
        index.put(Long.valueOf(chunkKey), fresh);
        return fresh;
    }

    private void invalidate(Block block) {
        index.remove(Long.valueOf(chunkKey(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)));
        // Le joueur qui vient de casser le bloc ne doit pas garder un masque sur
        // une case devenue vide : le serveur enverra le vrai changement, mais le
        // suivi, lui, doit oublier.
        long position = key(block.getLocation());
        for (Map<Long, Track> mine : tracks.values()) {
            mine.remove(Long.valueOf(position));
        }
    }

    // ── Divers ─────────────────────────────────────────────────────────────────

    private Set<Material> containerTypes() {
        Set<Material> types = EnumSet.noneOf(Material.class);
        List<String> configured = plugin.getConfig().getStringList("anticheat.chest-esp.types");
        if (configured.isEmpty()) {
            configured = java.util.Arrays.asList(
                    "CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "FURNACE", "BURNING_FURNACE",
                    "DISPENSER", "DROPPER", "HOPPER", "BREWING_STAND", "BEACON");
        }
        for (String name : configured) {
            Material material = Material.getMaterial(name.toUpperCase(java.util.Locale.ROOT));
            if (material != null) {
                types.add(material);
            }
        }
        return types;
    }

    /** Les types surveillés, relus une fois par passe et non par bloc. */
    private Set<Material> cachedTypes;
    private long typesReadAt;

    private boolean isContainer(Material type) {
        long now = System.currentTimeMillis();
        if (cachedTypes == null || now - typesReadAt > 30_000L) {
            cachedTypes = containerTypes();
            typesReadAt = now;
        }
        return type != null && cachedTypes.contains(type);
    }

    private static boolean exempt(Player player) {
        return player.hasPermission("redconflict.anticheat.seeall")
                || player.hasPermission("staff.staff")
                || player.hasPermission("redconflict.anticheat.bypass");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tracks.remove(event.getPlayer().getUniqueId());
    }

    /** Changer de monde invalide tout : les clés de position ne portent pas le monde. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        tracks.remove(event.getPlayer().getUniqueId());
    }

    private static long key(Location at) {
        return ((long) at.getBlockX() & 0x3FFFFFF) << 38
                | ((long) at.getBlockZ() & 0x3FFFFFF) << 12
                | ((long) at.getBlockY() & 0xFFF);
    }

    private static long chunkKey(World world, int cx, int cz) {
        return ((long) world.getName().hashCode() << 42)
                ^ ((long) cx & 0x1FFFFF) << 21 ^ ((long) cz & 0x1FFFFF);
    }

    /** Ce qu'un joueur sait d'un conteneur donné. */
    private static final class Track {
        private final Location at;
        private final boolean buried;
        private boolean seen;
        private boolean masked;
        private long blindSince;
        private long touchedAt;

        private Track(Location at, boolean buried) {
            this.at = at;
            this.buried = buried;
        }
    }

    /** Les conteneurs d'un chunk et la date de l'inventaire. */
    private static final class ChunkIndex {
        private final long builtAt;
        private final List<Location> locations;

        private ChunkIndex(long builtAt, List<Location> locations) {
            this.builtAt = builtAt;
            this.locations = locations;
        }
    }
}
