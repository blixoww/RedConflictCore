package fr.originsfight.profil;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.bounty.BountyInfo;
import fr.originsfight.bounty.BountyManager;
import fr.originsfight.bounty.KillstreakManager;
import fr.originsfight.data.PlayerDatabase;
import fr.originsfight.ks.KsListener;
import fr.originsfight.packets.PacketBuilder;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /profil [joueur] — Ouvre la fiche de profil graphique d'un joueur.
 */
public class ProfilCommand implements CommandExecutor, TabCompleter {

    private static final int PROFILE_OPEN = 0x90;
    private static final int PROFILE_DATA = 0x91;
    private static final String CHANNEL = "CUSTOM:PDATA_S2C";

    // Petit cache anti-latence (3s): utile quand un joueur ouvre/ferme rapidement le menu
    private static final long CACHE_TTL_MS = 3000L;
    private final Map<UUID, CachedPayload> payloadCache = new ConcurrentHashMap<>();

    private final OriginsFightCore plugin;
    private final PlayerDatabase playerDatabase;

    public ProfilCommand(OriginsFightCore plugin) {
        this.plugin = plugin;
        this.playerDatabase = plugin.getPlayerDatabase();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cCette commande ne peut être utilisée qu'en jeu.");
            return true;
        }
        Player requester = (Player) sender;

        if (args.length == 0 || args[0].equalsIgnoreCase(requester.getName())) {
            resolveAndSend(requester, requester.getUniqueId(), requester.getName(), PROFILE_OPEN);
            return true;
        }

        String targetName = args[0];
        Player online = Bukkit.getPlayer(targetName);
        if (online != null) {
            resolveAndSend(requester, online.getUniqueId(), online.getName(), PROFILE_DATA);
            return true;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        if (!offline.hasPlayedBefore()) {
            requester.sendMessage("§cJoueur introuvable : §f" + targetName);
            return true;
        }

        String resolvedName = offline.getName() != null ? offline.getName() : targetName;
        resolveAndSend(requester, offline.getUniqueId(), resolvedName, PROFILE_DATA);
        return true;
    }

    private void resolveAndSend(Player requester, UUID targetUuid, String targetName, int packetId) {
        CachedPayload cp = payloadCache.get(targetUuid);
        long now = System.currentTimeMillis();
        if (cp != null && (now - cp.at) <= CACHE_TTL_MS) {
            sendPacket(requester, packetId, cp.payload);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ProfilePayload p = buildPayload(targetUuid, targetName);
            payloadCache.put(targetUuid, new CachedPayload(now, p));
            Bukkit.getScheduler().runTask(plugin, () -> sendPacket(requester, packetId, p));
        });
    }

    private ProfilePayload buildPayload(UUID uuid, String name) {
        // ── Kills / Deaths / Playtime — depuis PlayerDatabase (source unique) ──────
        int kills = 0, deaths = 0, ptMin = 0;
        PlayerDatabase.PlayerProfile cached = playerDatabase.getProfile(uuid);
        if (cached != null) {
            // Ajouter la session en cours (temps non encore sauvegardé en base)
            long sessionSec = 0;
            Long joinTime = KsListener.getJoinTime(uuid);
            if (joinTime != null) sessionSec = (System.currentTimeMillis() - joinTime) / 1000;
            kills  = cached.kills;
            deaths = cached.deaths;
            ptMin  = (int) ((cached.playtimeSeconds + sessionSec) / 60);
        }

        // ── Balance — fraîche depuis Vault (online) ou snapshot DB (offline) ─────
        long balance = 0L;
        boolean isOnline = Bukkit.getPlayer(uuid) != null;
        Economy eco = plugin.getEconomy();
        if (eco != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op != null) {
                try { balance = (long) eco.getBalance(op); } catch (Exception ignored) {}
            }
        }
        // Fallback offline : Vault ne supporte pas toujours getBalance(OfflinePlayer)
        // → on lit le dernier snapshot persisté dans PlayerDatabase.
        if (!isOnline && balance == 0L && cached != null && cached.balance > 0L) {
            balance = cached.balance;
        }

        // ── Rang — frais depuis Vault Chat, mise à jour en base ──────────────────
        String rank = resolveRankPrefix(uuid, name);

        // ── Faction — fraîche depuis le plugin, mise à jour en base ─────────────
        String faction = "";
        Player onlineP = Bukkit.getPlayer(uuid);
        if (onlineP != null) {
            faction = getFactionTag(onlineP);
        } else {
            faction = getFactionTagByUUID(uuid);
        }

        // ── Streak & Bounty — depuis les managers in-memory ──────────────────────
        int streak = 0;
        long bounty = 0L;
        KillstreakManager ksm = KillstreakManager.getInstance();
        if (ksm != null) streak = ksm.getStreak(uuid);
        BountyManager bm = BountyManager.getInstance();
        if (bm != null) {
            BountyInfo bi = bm.getBounty(uuid);
            if (bi != null) bounty = bi.getAmount();
        }

        // ── Persistance des snapshots dans PlayerDatabase ─────────────────────────
        // Note : on ne réécrit la balance que si on l'a lue depuis Vault, sinon on
        // écraserait un snapshot valide par 0 (ex. joueur offline).
        if (isOnline || balance > 0L) playerDatabase.updateBalance(uuid, balance);
        playerDatabase.updateRank(uuid, rank);
        playerDatabase.updateFaction(uuid, faction);
        playerDatabase.setStreak(uuid, streak);
        playerDatabase.setBounty(uuid, bounty);

        // ── PB — solde Points Boutique (toujours frais depuis la DB) ────────────
        int pb = 0;
        if (plugin.getPBManager() != null) {
            try { pb = plugin.getPBManager().get(uuid); } catch (Exception ignored) {}
        }

        // ── Métiers — niveaux depuis JobManager ──────────────────────────────
        int minerLevel = 0, farmerLevel = 0, artisanLevel = 0;
        try {
            fr.originsfight.job.JobManager jm = plugin.getJobManager();
            if (jm != null) {
                fr.originsfight.job.JobDatabase.JobData jd = jm.getData(uuid);
                if (jd != null) {
                    minerLevel   = jd.minerLevel;
                    farmerLevel  = jd.farmerLevel;
                    artisanLevel = jd.artisanLevel;
                }
            }
        } catch (Exception ignored) {}

        return new ProfilePayload(name, faction, rank, kills, deaths, ptMin, balance, streak, bounty, pb,
                minerLevel, farmerLevel, artisanLevel);
    }

    /**
     * Priorité:
     * 1) Vault Chat prefix (LuckPerms via Vault)
     * 2) PlaceholderAPI %luckperms_prefix% (si dispo + joueur en ligne)
     * 3) Groupe principal Vault
     * 4) Joueur
     */
    private String resolveRankPrefix(UUID uuid, String name) {
        String prefix = "";

        try {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp != null) {
                Chat chat = rsp.getProvider();
                Player online = Bukkit.getPlayer(uuid);

                if (online != null) {
                    prefix = nullToEmpty(chat.getPlayerPrefix(online));
                }
                if (prefix.isEmpty()) {
                    // Fallback nom/monde (API Vault legacy)
                    prefix = nullToEmpty(chat.getPlayerPrefix((String) null, name));
                }
                if (prefix.isEmpty()) {
                    String group = chat.getPrimaryGroup((String) null, name);
                    if (group != null && !group.isEmpty()) prefix = group;
                }
            }
        } catch (Exception ignored) {}

        // PlaceholderAPI fallback (via reflection, sans dépendance compile-time)
        if (prefix.isEmpty()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                try {
                    Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                    java.lang.reflect.Method m = papi.getMethod("setPlaceholders", org.bukkit.entity.Player.class, String.class);
                    Object out = m.invoke(null, online, "%luckperms_prefix%");
                    if (out instanceof String) {
                        String s = ((String) out).trim();
                        if (!s.isEmpty() && !s.equals("%luckperms_prefix%")) {
                            prefix = s;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        if (prefix.isEmpty()) return "Joueur";
        return sanitizePrefix(prefix);
    }

    private String sanitizePrefix(String raw) {
        if (raw == null) return "Joueur";
        String s = raw.trim();
        if (s.isEmpty()) return "Joueur";

        // Evite d'envoyer une string purement formatting sans texte visible
        String plain = s.replaceAll("(?i)§.", "").replaceAll("(?i)&.", "").trim();
        if (plain.isEmpty()) return "Joueur";
        return s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private void sendPacket(Player recipient, int packetId, ProfilePayload p) {
        int factionRelation = resolveRequesterRelation(recipient, p.faction);
        byte[] data = PacketBuilder.create(packetId)
                .writeString(truncate(p.name, 32))
                .writeString(truncate(p.faction, 32))
                .writeString(truncate(p.rank, 32))
                .writeVarInt(p.kills)
                .writeVarInt(p.deaths)
                .writeVarInt(p.ptMin)
                .writeLong(p.balance)
                .writeVarInt(p.streak)
                .writeLong(p.bounty)
                .writeVarInt(factionRelation)
                .writeVarInt(p.pb)
                .writeVarInt(p.minerLevel)
                .writeVarInt(p.farmerLevel)
                .writeVarInt(p.artisanLevel)
                .build();
        recipient.sendPluginMessage(plugin, CHANNEL, data);
    }

    /**
     * Résout la relation entre le joueur recipient et la faction "targetTag".
     * 0=own, 1=ally, 2=truce, 3=enemy, 4=neutral
     */
    private int resolveRequesterRelation(Player requester, String targetTag) {
        if (targetTag == null || targetTag.isEmpty()) return 4;
        try {
            if (!fr.redfaction.api.RedFactionAPI.isAvailable()) return 4;
            fr.redfaction.api.RedFactionAPI api = fr.redfaction.api.RedFactionAPI.get();

            fr.redfaction.entity.Faction pFaction = api.getPlayerFaction(requester);
            if (pFaction == null) return 4;

            String ownTag = pFaction.getTag();
            if (targetTag.equals(ownTag)) return 0; // own faction

            // Trouve la faction cible par son tag
            fr.redfaction.entity.Faction targetFaction = null;
            for (fr.redfaction.entity.Faction f : api.getAllFactions()) {
                if (targetTag.equalsIgnoreCase(f.getTag())) { targetFaction = f; break; }
            }
            if (targetFaction == null) {
                plugin.getLogger().warning("[ProfilCmd] faction tag not found: '" + targetTag + "'");
                return 4;
            }

            fr.redfaction.entity.Relation rel = api.getRelation(pFaction, targetFaction);
            if (rel == null) return 4;
            plugin.getLogger().info("[ProfilCmd] relation=" + rel.name() + " own=" + ownTag + " target=" + targetTag);
            switch (rel) {
                case SELF:  return 0;
                case ALLY:  return 1;
                case TRUCE: return 2;
                case ENEMY: return 3;
                default:    return 4;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[ProfilCmd] resolveRequesterRelation error: " + e);
        }
        return 4;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String getFactionTag(Player player) {
        return getFactionTagByUUID(player.getUniqueId());
    }

    /**
     * Récupère le tag de faction d'un joueur (en ligne ou hors ligne) par UUID,
     * via l'API RedFaction. Retourne "" si sans faction / RedFaction absent.
     */
    private String getFactionTagByUUID(UUID uuid) {
        try {
            if (!fr.redfaction.api.RedFactionAPI.isAvailable()) return "";
            fr.redfaction.entity.Faction faction = fr.redfaction.api.RedFactionAPI.get().getPlayerFaction(uuid);
            if (faction != null && faction.isNormal()) {
                String tag = faction.getTag();
                if (tag != null && !tag.isEmpty()) return tag;
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) result.add(p.getName());
            }
        }
        return result;
    }

    private static class CachedPayload {
        final long at;
        final ProfilePayload payload;
        CachedPayload(long at, ProfilePayload payload) { this.at = at; this.payload = payload; }
    }

    private static class ProfilePayload {
        final String name, faction, rank;
        final int kills, deaths, ptMin, streak, pb;
        final long balance, bounty;
        // Données métiers (tous toujours actifs)
        final int minerLevel, farmerLevel, artisanLevel;

        ProfilePayload(String name, String faction, String rank,
                       int kills, int deaths, int ptMin,
                       long balance, int streak, long bounty, int pb,
                       int minerLevel, int farmerLevel, int artisanLevel) {
            this.name = name;
            this.faction = faction;
            this.rank = rank;
            this.kills = kills;
            this.deaths = deaths;
            this.ptMin = ptMin;
            this.balance = balance;
            this.streak = streak;
            this.bounty = bounty;
            this.pb = pb;
            this.minerLevel   = minerLevel;
            this.farmerLevel  = farmerLevel;
            this.artisanLevel = artisanLevel;
        }
    }
}
