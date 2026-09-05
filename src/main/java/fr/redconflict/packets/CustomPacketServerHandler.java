package fr.redconflict.packets;

import fr.redconflict.RedConflictCore;
import fr.redconflict.anticheat.ChannelGuard;
import fr.redconflict.anticheat.Check;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Canal générique du client moddé (CUSTOM:C2S).
 *
 * <p><b>Règle du canal : le client ne décrit jamais un objet, il désigne un
 * emplacement.</b> Ce qui sort d'ici est reconstruit depuis l'inventaire tenu
 * par le serveur, jamais depuis les octets reçus. Voir {@link #handleCustomDrop}
 * pour le pourquoi.
 */
public class CustomPacketServerHandler implements PluginMessageListener {

    /** Jet d'objet custom (ids 432-470) : le client 1.8.9 ne sait pas les lâcher. */
    private static final int PACKET_CUSTOM_ITEM_DROP = 96;

    /** Rapport d'environnement du client, envoyé une fois à la connexion. */
    private static final int PACKET_CLIENT_REPORT = 97;

    /** Réponse au défi d'intégrité (voir AttestationService). */
    private static final int PACKET_ATTEST_ANSWER = 0x63;

    /** Empreinte matérielle (HWID) + indice de VM (voir HwidBanService). */
    private static final int PACKET_HWID_REPORT = 0x64;

    /** Manifeste des bibliothèques natives du client (voir NativeGuard). */
    private static final int PACKET_NATIVE_REPORT = 0x65;

    private final RedConflictCore plugin;
    private final ChannelGuard guard;

    /**
     * Dernier rapport d'environnement traité, par joueur.
     *
     * <p><b>Un état qui dure ne vaut qu'une alerte.</b> Le client renvoie son
     * rapport tant qu'un motif dur persiste — c'est ce qui permet de voir une
     * injection apparue en cours de partie. Mais un motif PERMANENT, fût-il
     * légitime, produit alors une alerte toutes les dix secondes et par joueur :
     * le staff n'a plus que ça sous les yeux, et le vrai signalement se noie.
     * On ne signale donc qu'un rapport DIFFÉRENT du précédent.
     *
     * <p>Ce garde est côté serveur exprès : il protège aussi des clients déjà
     * déployés, qu'on ne peut pas corriger à distance.
     *
     * <p>Table bornée à 256 entrées, la plus ancienne partant d'elle-même : la
     * poignée de canal peut être appelée hors du thread principal, d'où la
     * synchronisation, et on ne veut pas d'une carte qui grossit sans fin.
     */
    private final Map<java.util.UUID, String> lastReport = java.util.Collections.synchronizedMap(
            new LinkedHashMap<java.util.UUID, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<java.util.UUID, String> eldest) {
                    return size() > 256;
                }
            });

    public CustomPacketServerHandler(RedConflictCore plugin, ChannelGuard guard) {
        this.plugin = plugin;
        this.guard = guard;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!guard.accept(player, channel, message)) {
            return;
        }
        try {
            PacketReader reader = new PacketReader(message);
            int packetId = reader.readPacketId();
            if (packetId == PACKET_CUSTOM_ITEM_DROP) {
                handleCustomDrop(player, reader);
            } else if (packetId == PACKET_CLIENT_REPORT) {
                handleClientReport(player, reader);
            } else if (packetId == PACKET_ATTEST_ANSWER) {
                handleAttestation(player, reader);
            } else if (packetId == PACKET_HWID_REPORT) {
                handleHwidReport(player, reader);
            } else if (packetId == PACKET_NATIVE_REPORT) {
                handleNativeReport(player, reader);
            }
        } catch (Exception ignored) {
            // Paquet malformé : déjà compté par le garde, rien de plus à faire.
        }
    }

    /**
     * Empreinte matérielle du client (une fois par connexion) : motif de VM puis
     * composants sérialisés. On délègue tout au {@link HwidBanService}, qui
     * stocke, croise avec les comptes bannis et refuse VM / contournement. Rien
     * ici n'est cru sur parole — le service revalide types, poids et hachages.
     */
    private void handleHwidReport(Player player, PacketReader reader) throws Exception {
        String vmReason = reader.readString(64);
        // Empreinte : jusqu'à ~5 composants x plusieurs hachages de 64 hex ; une
        // borne large mais finie évite qu'un client modifié envoie un pavé.
        String fingerprint = reader.readString(4096);
        fr.redconflict.staff.HwidBanService service = plugin.getHwidBanService();
        if (service != null) {
            service.handleReport(player, vmReason, fingerprint);
        }
    }

    /**
     * Manifeste des bibliothèques natives : nom, taille et empreinte de chaque
     * fichier du dossier des natives, puis les natives chargées depuis ailleurs.
     *
     * <p>Les DLL sont le seul code du client que l'obfuscateur ne protège pas et
     * que l'attestation du jar ne couvre pas : les remplacer est le chemin le
     * plus court pour injecter du code sans toucher une classe. Le tri est fait
     * par {@link fr.redconflict.anticheat.NativeGuard}, qui revalide chaque champ
     * — rien de ce qui arrive ici n'est cru sur parole.
     */
    private void handleNativeReport(Player player, PacketReader reader) throws Exception {
        // Bornes larges mais finies : une trentaine de fichiers, un chemin par
        // native étrangère. Le garde de canal a déjà plafonné la taille totale.
        String files = reader.readString(4096);
        String foreign = reader.isReadable() ? reader.readString(1024) : "";
        // Troisieme champ, optionnel : les modules reellement mappes dans le
        // processus (ProcessModuleScan). Absent d'un ancien client — d'ou le
        // garde isReadable, comme pour foreign. Il voit une DLL injectee par
        // CreateRemoteThread + LoadLibraryA, que foreign ne peut pas voir.
        String modules = reader.isReadable() ? reader.readString(1024) : "";
        fr.redconflict.anticheat.NativeGuard guard = plugin.getAntiCheat() == null
                ? null : plugin.getAntiCheat().getNativeGuard();
        if (guard != null) {
            guard.handleReport(player, files, foreign, modules);
        }
    }

    /**
     * Lâche un objet custom tenu en main.
     *
     * <p><b>Cette poignée acceptait auparavant un {@code ItemStack} sérialisé
     * par le client, NBT compris, et le faisait tomber au sol.</b> Elle ne
     * vérifiait que la présence de {@code (id, durabilité)} dans l'inventaire —
     * ni les enchantements, ni le nom, ni le NBT. Un client modifié pouvait donc
     * tenir une épée en diamant ordinaire, annoncer une épée en diamant
     * Sharpness 32767 portant le NBT de son choix, et le serveur la lui
     * fabriquait. C'était une forge d'objets arbitraires, ouverte à quiconque
     * savait écrire sur le canal.
     *
     * <p>Le protocole ne transporte donc plus d'objet : seulement le slot visé
     * et la quantité. Le serveur lit l'objet dans SON inventaire, vérifie que
     * c'est bien un objet custom, et lâche celui-là. Plus rien de ce que le
     * client envoie ne décrit ce qui apparaît dans le monde.
     */
    private void handleCustomDrop(Player player, PacketReader reader) throws Exception {
        final int slot = reader.readVarInt();
        final int requested = reader.readVarInt();

        if (slot < 0 || slot > 8 || requested < 1 || requested > 64) {
            plugin.getServer().getScheduler().runTask(plugin, player::updateInventory);
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Le slot doit être celui que le serveur croit tenu : un client
                // ne choisit pas dans quel emplacement il puise.
                if (slot != player.getInventory().getHeldItemSlot()) {
                    player.updateInventory();
                    return;
                }
                ItemStack held = player.getInventory().getItem(slot);
                if (held == null || held.getType() == Material.AIR || held.getAmount() <= 0) {
                    player.updateInventory();
                    return;
                }
                // Les objets vanilla passent par le jet vanilla : ce canal n'existe
                // que pour ceux que le client 1.8.9 ne sait pas lâcher seul.
                if (!isCustomItem(held)) {
                    player.updateInventory();
                    return;
                }

                int amount = Math.min(requested, held.getAmount());
                ItemStack dropped = held.clone();
                dropped.setAmount(amount);

                if (held.getAmount() <= amount) {
                    player.getInventory().setItem(slot, null);
                } else {
                    held.setAmount(held.getAmount() - amount);
                    player.getInventory().setItem(slot, held);
                }

                spawnAtEye(player, dropped);
                player.updateInventory();
            } catch (Exception e) {
                player.updateInventory();
            }
        });
    }

    /**
     * Identifiant numérique NMS d'un objet, y compris pour les objets custom que
     * {@code Material} ne connaît pas.
     *
     * <p>Utilitaire en lecture seule, partagé avec le HDV et l'XP-boost : il ne
     * sert qu'à comparer deux objets déjà présents côté serveur, jamais à en
     * fabriquer un depuis des octets reçus.
     */
    public static int getNmsItemId(ItemStack item) {
        if (item == null) return 0;

        int typeId = item.getTypeId();
        if (typeId > 0) return typeId;

        // Repli par réflexion NMS : asNMSCopy -> getItem -> Item.getId(item)
        try {
            String v = Bukkit.getServer().getClass().getPackage().getName().replace(".", ",").split(",")[3];
            Class<?> craftItemClass = Class.forName("org.bukkit.craftbukkit." + v + ".inventory.CraftItemStack");
            Class<?> nmsItemStackClass = Class.forName("net.minecraft.server." + v + ".ItemStack");
            Class<?> nmsItemClass = Class.forName("net.minecraft.server." + v + ".Item");
            Class<?> registryClass = Class.forName("net.minecraft.server." + v + ".RegistryMaterials");
            Class<?> minecraftKeyClass = Class.forName("net.minecraft.server." + v + ".MinecraftKey");

            Object nmsStack = craftItemClass.getMethod("asNMSCopy", new Class[] { ItemStack.class }).invoke(null, new Object[] { item });
            if (nmsStack == null) return 0;

            Object nmsItem = nmsItemStackClass.getMethod("getItem", new Class[0]).invoke(nmsStack, new Object[0]);
            if (nmsItem == null) return 0;

            int directId = ((Integer) nmsItemClass.getMethod("getId", new Class[] { nmsItemClass }).invoke(null, new Object[] { nmsItem })).intValue();
            if (directId > 0) return directId;

            // Si directId invalide, résout via clé registre -> Material enum
            Object registry = nmsItemClass.getField("REGISTRY").get(null);
            Iterable<?> keys = (Iterable<?>) registryClass.getMethod("keySet", new Class[0]).invoke(registry, new Object[0]);
            for (Object key : keys) {
                Object regItem = registryClass.getMethod("get", Object.class).invoke(registry, key);
                if (regItem != nmsItem) continue;
                String path = (String) minecraftKeyClass.getMethod("a", new Class[0]).invoke(key, new Object[0]);
                Material mat = Material.getMaterial(path.toUpperCase(Locale.ROOT));
                if (mat != null && mat != Material.AIR) {
                    return mat.getId();
                }
                break;
            }
        } catch (Exception ignored) {
        }

        return item.getType() != null ? item.getType().getId() : 0;
    }


    /**
     * Rapport d'environnement du client : agent Java, débogueur, démarrage hors
     * launcher.
     *
     * <p><b>Signal indicatif, jamais une preuve.</b> Il vient de la machine du
     * joueur : un client modifié se contente de ne rien envoyer, ou d'envoyer
     * une liste vide. Le silence ne prouve donc rien, seule la présence d'un
     * motif apprend quelque chose — et ce qu'elle apprend, c'est qu'on a affaire
     * à un tricheur qui n'a pas pris la peine de masquer son outil, ce qui est
     * le cas le plus fréquent.
     *
     * <p>C'est pour cette raison que le contrôle correspondant est en alerte et
     * pas en expulsion : il ouvre une enquête, il ne la conclut pas.
     */
    private void handleClientReport(Player player, PacketReader reader) throws Exception {
        int count = reader.readVarInt();
        if (count <= 0 || count > 16) {
            return;
        }
        StringBuilder findings = new StringBuilder();
        boolean hard = false;
        for (int i = 0; i < count && reader.isReadable(); i++) {
            String finding = reader.readString(64);
            if (finding == null || finding.isEmpty()) {
                continue;
            }
            if (findings.length() > 0) {
                findings.append(", ");
            }
            findings.append(finding);
            hard = hard || isHard(finding);
        }
        if (findings.length() == 0) {
            return;
        }
        // Rapport identique au précédent : rien de nouveau à apprendre au staff.
        if (findings.toString().equals(lastReport.put(player.getUniqueId(), findings.toString()))) {
            return;
        }
        // Un rapport DUR (code injecté, agent) et un rapport MOU (poignée de main
        // absente…) ne méritent pas la même réponse : le premier ne se lève que
        // chez un client réellement modifié, le second chez tout le monde tant
        // que le launcher n'est pas déployé. On les sépare pour que le staff
        // puisse kicker le premier sans toucher au second.
        Check check = hard ? Check.CLIENT_INJECTION : Check.CLIENT_TAMPER;
        guard.violations().flag(player, check, findings.toString());
    }

    /**
     * Un motif est « dur » s'il ne peut venir que d'un client réellement
     * modifié : du code chargé hors du jar officiel, ou un agent
     * d'instrumentation. Ces libellés sont produits par {@code TamperScan} côté
     * client — les fragments testés ici doivent rester alignés avec eux.
     *
     * <p>« poignée de main du launcher absente » et « execution hors jar
     * (developpement) » restent MOUS : le premier se lève chez tout joueur avant
     * le déploiement du launcher, le second en développement.
     */
    private boolean isHard(String finding) {
        String f = finding.toLowerCase(java.util.Locale.ROOT);
        if (f.contains("hors du jar")           // code chargé hors du jar officiel
                || f.contains("instrumentation") // agent -javaagent / -agentpath
                || f.contains("jdwp")) {         // débogueur attaché
            return true;
        }
        // Pile réseau instrumentée et bibliothèque native étrangère : deux motifs
        // aussi solides que les trois ci-dessus EN THÉORIE — un client vanilla
        // n'ajoute aucun handler Netty et ne charge aucune native hors du jeu —
        // mais qui n'ont encore jamais tourné sur ce parc de machines. Or
        // client-injection expulse dès le premier signalement : un faux positif
        // se traduirait par un joueur honnête expulsé en boucle, toutes les
        // vingt secondes. On les laisse donc en alerte le temps de les observer,
        // et on les promeut d'une ligne de configuration quand ils ont fait
        // leurs preuves.
        boolean strict = plugin.getConfig().getBoolean(
                "anticheat.client-injection.strict-new-motifs", false);
        return strict && (f.contains("pipeline") || f.contains("native etrangere"));
    }
    /**
     * Réponse au défi d'intégrité.
     *
     * <p>Aucun retour n'est envoyé, quel que soit le résultat : c'est le point
     * central du dispositif. Voir {@code AttestationService}.
     */
    private void handleAttestation(Player player, PacketReader reader) throws Exception {
        int length = reader.readVarInt();
        if (length <= 0 || length > 64) {
            return;
        }
        byte[] answer = reader.readBytes(length);
        plugin.getAntiCheat().getAttestation().verify(player, answer);
    }

    /** Les objets custom du serveur occupent la plage d'ids 432-470. */
    private static boolean isCustomItem(ItemStack item) {
        int id = item.getTypeId();
        return id >= 432 && id <= 470;
    }

    /**
     * Fait apparaître l'objet devant le joueur, avec le délai de ramassage
     * habituel.
     *
     * <p>Un {@code dropItem} à hauteur des yeux plus une vélocité. La version
     * précédente refaisait à la main, par réflexion NMS, ce que l'API Bukkit
     * fait déjà : une trentaine d'appels réflexifs qui cassaient au moindre
     * changement de version et noyaient la logique.
     */
    private static void spawnAtEye(Player player, ItemStack stack) {
        Location eye = player.getEyeLocation().subtract(0, 0.3, 0);
        Item entity = player.getWorld().dropItem(eye, stack);
        Vector direction = eye.getDirection().multiply(0.3);
        direction.setY(direction.getY() + 0.1);
        entity.setVelocity(direction);
        entity.setPickupDelay(40);
    }
}
