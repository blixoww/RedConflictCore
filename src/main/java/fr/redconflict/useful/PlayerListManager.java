package fr.redconflict.useful;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.economy.VaultEconomy;
import fr.redconflict.data.PlayerDatabase;
import fr.redconflict.staff.StaffManager;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scoreboard.*;

import java.util.UUID;

public class PlayerListManager {

    private final RedConflictCore plugin;
    private final StaffManager mgr = StaffManager.get();

    public PlayerListManager(RedConflictCore plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() { update(); }
        }, 20L, 40L);
    }

    private void update() {
        // Récupérer le provider Vault Chat une seule fois par tick
        Chat vaultChat = null;
        try {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp != null) vaultChat = rsp.getProvider();
        } catch (Exception ignored) {}

        // Nom affiché dans le tab : une passe par joueur, pas une par spectateur.
        // setPlayerListName diffuse déjà le paquet à tous ceux qui voient le
        // joueur ; le refaire dans la boucle des viewers multiplierait l'envoi
        // par le nombre de connectés.
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyTabName(p, rankPrefix(p, vaultChat));
        }
        purgeOfflineTabNames();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            if (board == null || board.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
                board = Bukkit.getScoreboardManager().getNewScoreboard();
                viewer.setScoreboard(board);
            }
            setupTeams(board, viewer, vaultChat);
            sendTabHeader(viewer);
        }
    }

    /**
     * Préfixe de grade traduit, ou {@code ""} si Vault ne répond pas.
     *
     * <p>translateAlternateColorCodes et non replace('&','§') : le remplacement
     * aveugle transformait aussi les &amp; du texte ordinaire (« Rock &amp; Roll »)
     * en code de couleur.
     */
    private String rankPrefix(Player p, Chat vaultChat) {
        if (vaultChat == null) return "";
        try {
            String raw = vaultChat.getPlayerPrefix(p);
            if (raw != null) return ChatColor.translateAlternateColorCodes('&', raw);
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Crée une team unique par joueur pour afficher son préfixe LuckPerms.
     * Le nom de la team commence par un index de tri pour garder l'ordre :
     *   00_ = vanish, 10_ = staff, 20_ = joueur normal
     */
    private void setupTeams(Scoreboard board, Player viewer, Chat vaultChat) {
        boolean viewerIsStaff = mgr.isStaff(viewer);

        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean isVanished  = mgr.isVanished(p.getUniqueId());
            boolean isStaffMode = mgr.isInStaffMode(p.getUniqueId());
            boolean isStaff     = mgr.isStaff(p);

            // Cacher les vanish/staffmode aux non-staff
            if ((isVanished || isStaffMode) && !viewerIsStaff) {
                removeFromTeams(board, p.getName());
                continue;
            }

            // Préfixe de tri (pour l'ordre dans le tab)
            String sortPrefix;
            if (isVanished || isStaffMode) sortPrefix = "00_";
            else if (isStaff)              sortPrefix = "10_";
            else                           sortPrefix = "20_";

            String lpPrefix = rankPrefix(p, vaultChat);

            // Le nom de la team doit être unique par joueur et <= 16 chars.
            String teamName = teamName(sortPrefix, p.getName());

            // Ici, la limite de 16 est incontournable : elle vient du paquet
            // scoreboard. Ce préfixe-là ne sert plus qu'au nom au-dessus de la
            // tête — celui du tab passe par applyTabName(), sans limite.
            lpPrefix = safePrefix(lpPrefix, p);

            Team team = board.getTeam(teamName);
            if (team == null) {
                team = board.registerNewTeam(teamName);
            }
            team.setPrefix(lpPrefix);

            // S'assurer que le joueur est dans la bonne team
            Team current = board.getEntryTeam(p.getName());
            if (current == null || !current.getName().equals(teamName)) {
                removeFromTeams(board, p.getName());
                team.addEntry(p.getName());
            }
        }

        // Nettoyage des teams orphelines (joueurs déconnectés)
        for (Team t : board.getTeams()) {
            if (t.getEntries().isEmpty()) t.unregister();
        }
    }


    /** Limite d'un préfixe de team en 1.8. Dépasser lève une exception côté Bukkit. */
    private static final int PREFIX_LIMIT = 16;

    /** Dernier nom de tab posé par joueur, pour ne pas rediffuser le paquet toutes les 2 s. */
    private final java.util.Map<UUID, String> appliedTabNames = new java.util.HashMap<UUID, String>();

    /**
     * Écrit le nom du joueur dans le tab, préfixe de grade compris et
     * <b>sans troncature</b>.
     *
     * <p><b>Pourquoi ne pas se contenter de la team.</b> Le préfixe de team est
     * plafonné à 16 caractères par le paquet scoreboard de la 1.8 : à 17,
     * {@code §8[§7Joueur§8] §f} devait sacrifier soit l'espace, soit le {@code §f},
     * donc soit un pseudo collé au crochet, soit un pseudo gris foncé. Le nom
     * affiché du tab, lui, voyage dans le paquet PlayerInfo sous forme de
     * composant de chat : aucune limite de longueur. On y met donc le préfixe
     * entier, espace compris, et la team ne sert plus qu'au nom au-dessus de la
     * tête et au tri.
     *
     * <p>Le tri du tab n'est pas affecté : le client trie sur le nom de la team
     * ({@code 00_/10_/20_}), pas sur le nom affiché.
     *
     * <p>On ne réécrit que sur changement — {@code setPlayerListName} diffuse un
     * paquet à tous les joueurs qui voient celui-ci, et la boucle tourne toutes
     * les deux secondes.
     */
    private void applyTabName(Player p, String fullPrefix) {
        UUID id = p.getUniqueId();

        // L'anonymat retire le grade du tab : on laisse la team faire, sans nom custom.
        fr.redconflict.annonyme.AnonymeManager anon = plugin.getAnonymeManager();
        boolean anonymous = anon != null && anon.isAnonymous(p);

        String prefix = anonymous ? "" : trimTrailingCodes(fullPrefix);
        if (prefix.isEmpty()) {
            // Sans préfixe, le nom custom vaudrait le pseudo nu : autant ne rien
            // poser du tout, sinon CraftPlayer remet listName à null et on
            // rediffuserait le paquet à chaque tour.
            if (appliedTabNames.remove(id) != null) p.setPlayerListName(null);
            return;
        }

        String wanted = prefix + p.getName();
        // getPlayerListName() renvoie le pseudo nu quand rien n'est posé : c'est
        // le cas d'une reconnexion, où notre cache est en avance sur le serveur.
        boolean unset = p.getName().equals(p.getPlayerListName());
        if (unset || !wanted.equals(appliedTabNames.get(id))) {
            p.setPlayerListName(wanted);
            appliedTabNames.put(id, wanted);
        }
    }

    /** Oublie les joueurs partis, sinon la table grossit à chaque déconnexion. */
    private void purgeOfflineTabNames() {
        if (appliedTabNames.isEmpty()) return;
        java.util.Iterator<UUID> it = appliedTabNames.keySet().iterator();
        while (it.hasNext()) {
            Player p = Bukkit.getPlayer(it.next());
            if (p == null || !p.isOnline()) it.remove();
        }
    }

    /** Préfixes trop longs déjà signalés : on n'inonde pas la console. */
    private final java.util.Set<String> warnedPrefixes = new java.util.HashSet<String>();

    /**
     * Ramène un préfixe sous la limite de 16 caractères, puis enlève ce qui
     * déborderait sur le pseudo.
     *
     * <p>Le client 1.8 affiche la ligne du tab comme un seul texte,
     * {@code préfixe + pseudo} : tout ce que le préfixe laisse ouvert à la fin
     * s'applique au pseudo. Deux fins de préfixe posent problème.
     *
     * <p><b>Un {@code §} orphelin.</b> Un {@code substring(0, 16)} nu sur
     * {@code §8[§7Joueur§8] §f} (17 caractères) rendait
     * {@code §8[§7Joueur§8] §}. Le client lit alors la première lettre du pseudo
     * comme le code qui manque : un « k » passait en {@code §k}, donc illisible,
     * un « c » en {@code §c}, donc rouge, un « l » en {@code §l}, donc gras — et
     * dans tous les cas la lettre était mangée.
     *
     * <p><b>Un code de style final</b> ({@code §l}, {@code §k}, {@code §m},
     * {@code §n}, {@code §o}). Là, rien n'est tronqué et rien n'est mangé : le
     * préfixe se termine simplement par un {@code &l} écrit dans LuckPerms, et
     * le gras coule sur le pseudo. C'est le cas que la version précédente
     * laissait passer, parce qu'elle ne nettoyait qu'en cas de troncature.
     *
     * <p>Les codes de <i>couleur</i>, eux, sont conservés : c'est par eux que le
     * pseudo prend la teinte du grade, et c'est voulu.
     *
     * <p><b>Ce qu'on sacrifie quand ça dépasse.</b> Les espaces d'abord, la
     * couleur ensuite — voir {@link #squeezeSpaces}.
     */
    private String safePrefix(String prefix, Player owner) {
        String out = prefix;

        // 1. Trop long : on tente d'abord de le faire rentrer sans rien perdre
        //    d'utile, en retirant les espaces depuis la fin.
        if (out.length() > PREFIX_LIMIT) {
            String squeezed = squeezeSpaces(out, PREFIX_LIMIT);
            if (squeezed.length() <= PREFIX_LIMIT) {
                if (warnedPrefixes.add("space:" + prefix)) {
                    plugin.getLogger().info("[Nametag] Préfixe de « " + owner.getName() + " » : « " + raw(prefix)
                            + " » fait " + prefix.length() + " caractères pour une limite de " + PREFIX_LIMIT
                            + " en 1.8. Espace(s) retiré(s) → « " + raw(squeezed)
                            + " » pour le nom AU-DESSUS DE LA TÊTE ; le tab, lui, affiche le préfixe entier.");
                }
            }
            out = squeezed;
        }

        // 2. Toujours trop long : là on coupe, et le pseudo y perd sa couleur.
        boolean truncated = false;
        if (out.length() > PREFIX_LIMIT) {
            out = out.substring(0, PREFIX_LIMIT);
            truncated = true;
            if (warnedPrefixes.add(prefix)) {
                plugin.getLogger().warning("[Nametag] Préfixe de grade trop long pour "
                        + owner.getName() + " : « " + raw(prefix) + " » fait "
                        + prefix.length() + " caractères, la limite 1.8 est " + PREFIX_LIMIT
                        + " (espaces déjà retirés).");
                plugin.getLogger().warning("[Nametag] Il est tronqué pour le nom au-dessus de la tête, "
                        + "qui y perd sa couleur. Le tab n'est pas concerné. Raccourcis-le dans LuckPerms.");
            }
        }

        String trimmed = trimTrailingCodes(out);
        // Signalé seulement si la troncature n'a pas déjà tout expliqué, sinon
        // le même préfixe génère trois lignes de console d'affilée.
        if (!truncated && !trimmed.equals(out) && warnedPrefixes.add("style:" + prefix)) {
            // Dire pourquoi le gras a disparu, sinon le prochain qui édite
            // LuckPerms le remet et refait le tour.
            plugin.getLogger().info("[Nametag] Préfixe de « " + owner.getName() + " » : « "
                    + raw(out) + " » se termine par un code sans texte, "
                    + "il débordait sur le pseudo. Envoyé au client comme « "
                    + raw(trimmed) + " ».");
        }
        return trimmed;
    }

    /**
     * Retire des espaces, en partant de la fin, jusqu'à tenir dans la limite.
     *
     * <p>Un préfixe qui déborde d'un caractère déborde presque toujours à cause
     * de l'espace posé avant le dernier code : {@code §8[§7Joueur§8] §f} fait 17.
     * Couper à 16 y mange le {@code §f}, et le pseudo repart alors sur la
     * dernière couleur encore ouverte — le {@code §8} du crochet fermant, donc
     * gris foncé, presque illisible. Retirer l'espace garde la couleur voulue et
     * ne coûte qu'un pseudo collé au crochet.
     *
     * <p>Ne touche qu'au nombre d'espaces strictement nécessaire, et jamais aux
     * caractères visibles : si le préfixe déborde sans contenir d'espace, il
     * ressort tel quel et c'est la troncature qui tranchera.
     */
    private static String squeezeSpaces(String s, int limit) {
        if (s.length() <= limit) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = sb.length() - 1; i >= 0 && sb.length() > limit; i--) {
            if (sb.charAt(i) == ' ') sb.deleteCharAt(i);
        }
        return sb.toString();
    }

    /** Le préfixe tel qu'il est écrit dans LuckPerms, pour les messages console. */
    private static String raw(String s) {
        return s.replace(ChatColor.COLOR_CHAR, '&');
    }

    /**
     * Enlève, en fin de chaîne, ce qui s'appliquerait au texte suivant : un
     * marqueur de code sans sa lettre ({@code §} ou {@code &} isolé) et les codes
     * de style ({@code §l} et compagnie). Boucle, parce qu'un préfixe peut en
     * empiler plusieurs — {@code §c§l§} en laisse trois à retirer.
     *
     * <p>Un {@code &} au milieu du texte n'est pas touché : « Rock &amp; Roll »
     * reste « Rock &amp; Roll ».
     */
    static String trimTrailingCodes(String s) {
        if (s == null) return "";
        int end = s.length();
        boolean changed = true;
        while (changed && end > 0) {
            changed = false;
            char last = s.charAt(end - 1);
            if (last == ChatColor.COLOR_CHAR || last == '&') {
                end--;
                changed = true;
            } else if (end >= 2 && s.charAt(end - 2) == ChatColor.COLOR_CHAR
                    && "klmnoKLMNO".indexOf(last) >= 0) {
                end -= 2;
                changed = true;
            }
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /**
     * Nom de team unique et court.
     *
     * <p>Tronquer {@code sortPrefix + pseudo} à 16 caractères faisait collisionner
     * deux joueurs dont les noms partagent leurs 13 premières lettres : ils
     * atterrissaient dans la même team, donc avec le même préfixe. On remplace la
     * fin par une empreinte du nom complet quand il faut couper.
     */
    private static String teamName(String sortPrefix, String playerName) {
        String full = sortPrefix + playerName;
        if (full.length() <= 16) {
            return full;
        }
        String hash = Integer.toHexString(playerName.hashCode());
        int keep = 16 - sortPrefix.length() - hash.length();
        if (keep < 0) {
            keep = 0;
        }
        return sortPrefix + playerName.substring(0, Math.min(keep, playerName.length())) + hash;
    }
    private void sendTabHeader(Player p) {
        try {
            int online = countVisible(p);
            int max    = Bukkit.getMaxPlayers();
            String header = "\n\u00a7c\u00a7lRedConflict\n\u00a77Joueurs \u00a78: \u00a7f" + online + " \u00a78/ \u00a77" + max + "\n";

            // Monnaie
            Economy eco = VaultEconomy.get();
            String balanceStr = eco != null ? String.valueOf((long) eco.getBalance(p)) : "?";

            // Stats KS (kills, deaths, killstreak)
            int kills = 0;
            int deaths = 0;
            String ratio = "0.00";
            PlayerDatabase playerDatabase = plugin.getPlayerDatabase();
            if (playerDatabase != null) {
                PlayerDatabase.KsStats stats = playerDatabase.getStats(p.getUniqueId());
                if (stats != null) {
                    kills = stats.kills;
                    deaths = stats.deaths;
                    ratio = stats.ratio();
                }
            }

            // PB
            fr.redconflict.pb.PBManager pbMgr = plugin.getPBManager();
            String pbStr = pbMgr != null ? String.valueOf(pbMgr.get(p)) : "?";

            String footer = "\n"
                    + " \u00a78Monnaie \u00a78: \u00a77" + balanceStr + " \u00a78$\n"
                    + " \u00a78PB \u00a78: \u00a7e" + pbStr + " \u00a78PB\n"
                    + " \u00a78Kills \u00a78: \u00a7a" + kills
                    + " \u00a78| \u00a78Deaths \u00a78: \u00a7c" + deaths + "\n"
                    + " \u00a78Ratio K/D \u00a78: \u00a7e" + ratio + "\n";

            String ver = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> chatClass = Class.forName("net.minecraft.server." + ver + ".ChatComponentText");
            Class<?> pktClass  = Class.forName("net.minecraft.server." + ver + ".PacketPlayOutPlayerListHeaderFooter");
            Object pkt   = pktClass.newInstance();
            Object hChat = chatClass.getConstructor(String.class).newInstance(header);
            Object fChat = chatClass.getConstructor(String.class).newInstance(footer);
            java.lang.reflect.Field fa = pktClass.getDeclaredField("a");
            java.lang.reflect.Field fb = pktClass.getDeclaredField("b");
            fa.setAccessible(true); fb.setAccessible(true);
            fa.set(pkt, hChat); fb.set(pkt, fChat);
            sendPacket(p, pkt, ver);
        } catch (Exception ignored) {}
    }

    private int countVisible(Player viewer) {
        boolean isStaff = mgr.isStaff(viewer);
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isStaff && (mgr.isVanished(p.getUniqueId()) || mgr.isInStaffMode(p.getUniqueId()))) continue;
            count++;
        }
        return count;
    }


    private void removeFromTeams(Scoreboard board, String entry) {
        for (Team t : board.getTeams()) { if (t.hasEntry(entry)) t.removeEntry(entry); }
    }

    private void sendPacket(Player player, Object packet, String ver) throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object conn   = handle.getClass().getField("playerConnection").get(handle);
        conn.getClass().getMethod("sendPacket", Class.forName("net.minecraft.server." + ver + ".Packet")).invoke(conn, packet);
    }
}




