package fr.redconflict.vote;

import fr.redconflict.RedConflictCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Table de butin des votes : lecture de {@code vote/recompenses.yml}, tirage, et
 * remise.
 *
 * <p>Un vote donne toujours des PB — crédités même hors ligne, puisque le solde
 * vit en base. Le butin, lui, dépose souvent des objets : dans ce cas le lot est
 * mis de côté et remis à la connexion suivante.
 */
public final class VoteRewards {

    private final RedConflictCore plugin;
    private final VoteStorage storage;
    private final Random random = new Random();

    private FileConfiguration config;
    private Map<String, VoteLot> lots = Collections.emptyMap();
    private int poidsTotal;

    public VoteRewards(RedConflictCore plugin, VoteStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    // ── Chargement ─────────────────────────────────────────────────────────────

    public void reload() {
        File fichier = new File(plugin.getDataFolder(), "vote/recompenses.yml");
        fichier.getParentFile().mkdirs();
        if (!fichier.exists()) {
            plugin.saveResource("vote/recompenses.yml", false);
        }

        YamlConfiguration chargee = YamlConfiguration.loadConfiguration(fichier);
        InputStream defauts = plugin.getResource("vote/recompenses.yml");
        if (defauts != null) {
            chargee.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defauts, StandardCharsets.UTF_8)));
        }
        this.config = chargee;

        Map<String, VoteLot> lus = new LinkedHashMap<>();
        int somme = 0;
        List<?> entrees = config.getList("vote.lots");
        if (entrees != null) {
            for (Object brut : entrees) {
                if (!(brut instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) brut;

                String id = String.valueOf(m.getOrDefault("id", "")).trim();
                if (id.isEmpty()) continue;

                List<String> commandes = new ArrayList<>();
                Object cmds = m.get("commandes");
                if (cmds instanceof List) {
                    for (Object c : (List<?>) cmds) commandes.add(String.valueOf(c));
                }

                VoteLot lot = new VoteLot(id,
                        String.valueOf(m.getOrDefault("nom", id)),
                        asInt(m.get("poids")),
                        asInt(m.get("pb")),
                        commandes);
                lus.put(id, lot);
                somme += lot.poids;
            }
        }

        this.lots = Collections.unmodifiableMap(lus);
        this.poidsTotal = somme;

        plugin.getLogger().info("[Vote] " + lus.size() + " lot(s) chargé(s), "
                + config.getInt("vote.pb", 0) + " PB par vote.");
        if (somme <= 0 && !lus.isEmpty()) {
            plugin.getLogger().warning("[Vote] Tous les poids valent 0 : aucun lot ne sortira.");
        }
    }

    public int nombreDeLots() {
        return lots.size();
    }

    // ── Remise ─────────────────────────────────────────────────────────────────

    /**
     * Récompense un vote. À appeler sur le thread principal.
     *
     * @param nom pseudo tel que le site l'a transmis
     */
    public void recompenser(String nom) {
        @SuppressWarnings("deprecation")
        OfflinePlayer cible = Bukkit.getOfflinePlayer(nom);
        UUID uuid = cible.getUniqueId();
        Player enLigne = Bukkit.getPlayerExact(nom);

        int total = storage.isAvailable() ? storage.enregistrerVote(uuid, nom) : 0;

        List<VoteLot> tires = new ArrayList<>();
        for (int i = 0, n = Math.max(1, config.getInt("vote.tirages", 1)); i < n; i++) {
            VoteLot lot = tirer();
            if (lot != null) tires.add(lot);
        }

        // Garantie de fidélité : elle s'ajoute au tirage, elle ne le remplace pas.
        int tousLes = config.getInt("vote.garantie.tous_les", 0);
        VoteLot garanti = null;
        if (tousLes > 0 && total > 0 && total % tousLes == 0) {
            garanti = lots.get(config.getString("vote.garantie.lot", ""));
            if (garanti != null) tires.add(garanti);
        }

        // ── PB : toujours crédités, même hors ligne ──
        int pb = config.getInt("vote.pb", 0);
        for (VoteLot lot : tires) pb += lot.pb;

        if (pb > 0 && plugin.getPBManager() != null) {
            plugin.getPBManager().add(cible, pb, "VOTE", "game");
        }

        // ── Lots : remis maintenant, ou mis de côté ──
        List<String> noms = new ArrayList<>();
        for (VoteLot lot : tires) {
            noms.add(ChatColor.translateAlternateColorCodes('&', lot.nom));

            if (lot.commandes.isEmpty()) continue;

            if (lot.exigeConnecte() && enLigne == null) {
                if (storage.isAvailable()) {
                    storage.mettreEnAttente(uuid, nom, lot.id);
                    plugin.getLogger().info("[Vote] " + nom + " hors ligne : lot « "
                            + lot.id + " » mis de côté.");
                } else {
                    plugin.getLogger().warning("[Vote] " + nom + " hors ligne et base H2"
                            + " indisponible : lot « " + lot.id + " » PERDU.");
                }
                continue;
            }
            donner(nom, uuid, lot);
        }

        annoncer(nom, enLigne, pb, noms, garanti, total);

        // Le vote vient d'être consommé : l'encart du HUD doit disparaître sans
        // attendre le tour de relecture. Quelques secondes de marge pour que le
        // site ait recalculé sa ligne dans rc_vote_status.
        if (enLigne != null && plugin.getVoteModule() != null) {
            plugin.getVoteModule().rafraichirStatut(enLigne, 20L * 5L);
        }
    }

    /** Remet les lots mis de côté. Appelée à la connexion du joueur. */
    public int remettreEnAttente(Player joueur) {
        if (!storage.isAvailable()) return 0;

        List<String> ids = storage.retirerEnAttente(joueur.getUniqueId());
        int remis = 0;
        for (String id : ids) {
            VoteLot lot = lots.get(id);
            if (lot == null) {
                // Le lot a disparu du YAML entre-temps : on le signale plutôt
                // que de le laisser filer sans trace.
                plugin.getLogger().warning("[Vote] Lot « " + id + " » en attente pour "
                        + joueur.getName() + " mais absent de la configuration.");
                continue;
            }
            donner(joueur.getName(), joueur.getUniqueId(), lot);
            joueur.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&a✔ Récompense de vote : &f" + lot.nom));
            remis++;
        }
        return remis;
    }

    // ── Interne ────────────────────────────────────────────────────────────────

    /** Tirage pondéré. {@code null} si aucun lot n'est utilisable. */
    private VoteLot tirer() {
        if (poidsTotal <= 0) return null;
        int seuil = random.nextInt(poidsTotal);
        for (VoteLot lot : lots.values()) {
            seuil -= lot.poids;
            if (seuil < 0) return lot;
        }
        return null;
    }

    private void donner(String nom, UUID uuid, VoteLot lot) {
        for (String ligne : lot.commandes) {
            plugin.getRewardDispatcher().dispatch(nom, uuid, ligne);
        }
    }

    private void annoncer(String nom, Player enLigne, int pb, List<String> noms,
                          VoteLot garanti, int total) {
        String resume = noms.isEmpty() ? "" : "— " + String.join(", ", noms);

        if (enLigne != null) {
            String message = config.getString("vote.message", "");
            if (message != null && !message.isEmpty()) {
                enLigne.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        message.replace("%pb%", String.valueOf(pb)).replace("%lots%", resume)));
            }
            if (garanti != null) {
                String m = config.getString("vote.garantie.message", "");
                if (m != null && !m.isEmpty()) {
                    enLigne.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            m.replace("%lot%", garanti.nom).replace("%total%", String.valueOf(total))));
                }
            }
        }

        String annonce = config.getString("vote.annonce", "");
        if (annonce != null && !annonce.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                    annonce.replace("%player%", nom).replace("%pb%", String.valueOf(pb))));
        }

        plugin.getLogger().info("[Vote] " + nom + " récompensé : +" + pb + " PB "
                + resume + " (vote n°" + total + ")");
    }

    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
}
