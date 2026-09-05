package fr.redconflict.staff;

import fr.redconflict.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ban HWID + refus des machines virtuelles.
 *
 * <p><b>Où ça se joue.</b> Le client rapporte son empreinte matérielle une fois
 * après le join (paquet {@code HWID_REPORT}, canal signé). Ce service la stocke,
 * puis cherche un <b>compte banni actif</b> qui partage assez de matériel — un
 * changement de compte sur le même PC — et, si demandé, refuse les VM. Le kick
 * réutilise l'écran de ban existant.
 *
 * <p><b>Ce qu'on ne croit pas au client.</b> L'empreinte est déclarée par la
 * machine du joueur : un spoofer peut mentir. Deux garde-fous ici : on
 * <b>n'accepte que des types de composants connus</b> et on <b>fixe le poids
 * côté serveur</b> (un client modifié ne peut pas gonfler son score), et on ne
 * garde que des hachages bien formés. Le vrai verrou reste le ban de compte et
 * d'IP, vus par le serveur lui-même. Le HWID élève le coût du contournement, il
 * ne le rend pas impossible.
 */
public final class HwidBanService {

    /**
     * Poids par type, <b>décidés par le serveur</b> (le client ne les impose
     * pas). Un type inconnu est ignoré. Reflète la fiabilité mesurée :
     * machine-id et disque discriminent vraiment, la carte mère beaucoup moins.
     */
    private static final Map<String, Integer> WEIGHTS = new HashMap<String, Integer>();
    static {
        WEIGHTS.put("machineId", 3);
        WEIGHTS.put("disk", 3);
        WEIGHTS.put("smbiosUuid", 2);
        WEIGHTS.put("mac", 1);
        WEIGHTS.put("baseboard", 1);
    }

    private final RedConflictCore plugin;
    private final StaffDatabase db;

    private boolean enabled;
    private boolean blockVms;
    private int threshold;

    public HwidBanService(RedConflictCore plugin, StaffDatabase db) {
        this.plugin = plugin;
        this.db = db;
        reload();
    }

    /**
     * Relit la configuration (appelé au démarrage et sur reload).
     *
     * <p><b>On n'écrit jamais dans {@code config.yml}.</b> La tentation était
     * d'y semer les clés manquantes avec {@code addDefault} +
     * {@code copyDefaults} + {@code saveConfig} — sauf que {@code saveConfig}
     * réécrit le fichier depuis le modèle en mémoire, et que ce modèle ne
     * contient pas les commentaires : les 450 lignes d'explications de
     * {@code config.yml} disparaîtraient au premier démarrage, sans prévenir et
     * sans retour en arrière possible.
     *
     * <p>Les valeurs par défaut sont donc portées deux fois, et c'est voulu :
     * dans le {@code config.yml} livré avec le jar (documentées, pour une
     * installation neuve) et dans les appels ci-dessous (pour un serveur déjà
     * en service, dont le fichier n'a pas la section). Ajouter la section à la
     * main sur un serveur existant est décrit dans le runbook.
     */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();

        this.enabled = cfg.getBoolean("anticheat.ban.hwid.enabled", false);
        this.blockVms = cfg.getBoolean("anticheat.ban.hwid.block-vms", true);
        this.threshold = cfg.getInt("anticheat.ban.hwid.threshold", 4);
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Réglages relus par le miroir vers la base du site : le launcher applique
     * <b>la même politique</b> que le serveur, sans qu'on ait à la resaisir dans
     * l'administration du site. Une seule source, celle-ci.
     */
    public boolean isBlockVms()  { return blockVms; }
    public int getThreshold()    { return threshold; }

    /**
     * Traite un rapport HWID reçu du client. Appelé depuis la poignée de paquet
     * (thread principal) : la partie base de données part en asynchrone, et le
     * kick éventuel revient sur le thread principal.
     *
     * @param vmReason motif de VM détecté par le client, ou chaîne vide
     * @param fingerprint composants sérialisés ({@code type|poids|hash,hash})
     */
    public void handleReport(final Player player, final String vmReason, String fingerprint) {
        if (!enabled) return;

        final String uuid = player.getUniqueId().toString();
        final String name = player.getName();
        final List<StaffDatabase.HwidComponent> comps = parse(fingerprint);
        final boolean isVm = vmReason != null && !vmReason.isEmpty();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                try {
                    if (!comps.isEmpty()) {
                        db.saveHwid(uuid, name, comps);
                    }

                    // 1. Machine virtuelle : refus si demandé (contournement le
                    //    plus propre du ban HWID — repartir d'un PC « neuf »).
                    if (isVm && blockVms) {
                        // Tracé : c'est la seule façon de distinguer un vrai refus
                        // d'un faux positif quand un joueur dit « je n'ai pas de VM ».
                        plugin.getLogger().info("[HWID] " + name + " refusé : machine "
                                + "virtuelle détectée (" + safeReason(vmReason) + ").");
                        kick(player, StaffFormatter.banScreen(
                                "Machine virtuelle non autorisée",
                                "Permanent", "AntiCheat"));
                        return;
                    }

                    // 2. Contournement de ban : un compte banni partage ce matériel.
                    if (!comps.isEmpty()) {
                        StaffDatabase.HwidHit hit = db.findHwidBanEvasion(comps, threshold);
                        if (hit != null) {
                            StaffDatabase.Sanction ban = db.getActiveSanction(
                                    hit.uuid, StaffDatabase.SanctionType.BAN);
                            String reason = ban != null ? ban.reason : "Contournement de ban";
                            String expiry = (ban != null && !ban.isPermanent())
                                    ? StaffFormatter.formatDate(ban.expiresAt) : "Permanent";
                            String staff = ban != null ? ban.staff : "AntiCheat";
                            kick(player, StaffFormatter.banScreen(
                                    "[HWID-BAN] " + reason, expiry, staff));
                        }
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[HWID] handleReport: " + t.getMessage());
                }
            }
        });
    }

    /**
     * Motif rendu inoffensif pour la console : il vient du client, donc il peut
     * contenir n'importe quoi — sauts de ligne compris, qui maquilleraient le
     * journal.
     */
    private static String safeReason(String reason) {
        if (reason == null) return "";
        String s = reason.replaceAll("[^a-zA-Z0-9:_. -]", "");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private void kick(final Player player, final String screen) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (player.isOnline()) player.kickPlayer(screen);
            }
        });
    }

    /**
     * Analyse la charge utile du client, en <b>rejetant tout ce qui ne colle
     * pas</b> : types inconnus, hachages mal formés, poids client (remplacé par
     * le poids serveur). Un client modifié ne peut donc pas inventer un score.
     */
    private List<StaffDatabase.HwidComponent> parse(String fingerprint) {
        List<StaffDatabase.HwidComponent> out = new ArrayList<StaffDatabase.HwidComponent>();
        if (fingerprint == null || fingerprint.isEmpty()) return out;

        for (String line : fingerprint.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;

            String type = parts[0].trim();
            Integer weight = WEIGHTS.get(type);
            if (weight == null) continue;                 // type inconnu : ignoré

            List<String> hashes = new ArrayList<String>();
            for (String h : parts[2].split(",")) {
                String hh = h.trim().toLowerCase(Locale.ROOT);
                if (hh.length() == 64 && hh.matches("[0-9a-f]{64}")) {
                    if (!hashes.contains(hh)) hashes.add(hh);
                }
            }
            // Garde-fou anti-inondation : jamais plus de 16 empreintes par type.
            while (hashes.size() > 16) hashes.remove(hashes.size() - 1);

            if (!hashes.isEmpty()) {
                out.add(new StaffDatabase.HwidComponent(type, weight, hashes));
            }
        }
        return out;
    }
}
