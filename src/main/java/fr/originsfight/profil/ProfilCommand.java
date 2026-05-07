package fr.originsfight.profil;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.bounty.BountyInfo;
import fr.originsfight.bounty.BountyManager;
import fr.originsfight.bounty.KillstreakManager;
import fr.originsfight.ks.KsDatabase;
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
    private final KsDatabase ksDatabase;

    public ProfilCommand(OriginsFightCore plugin) {
        this.plugin = plugin;
        this.ksDatabase = plugin.getKsDatabase();
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
        int kills = 0;
        int deaths = 0;
        int ptMin = 0;
        if (ksDatabase != null) {
            KsDatabase.KsStats stats = ksDatabase.getStats(uuid);
            if (stats != null) {
                long sessionSec = 0;
                Long joinTime = fr.originsfight.ks.KsListener.getJoinTime(uuid);
                if (joinTime != null) sessionSec = (System.currentTimeMillis() - joinTime) / 1000;
                
                kills = stats.kills;
                deaths = stats.deaths;
                ptMin = (int) ((stats.playtimeSeconds + sessionSec) / 60);
            }
        }

        long balance = 0L;
        Economy eco = plugin.getEconomy();
        if (eco != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op != null) {
                try {
                    balance = (long) eco.getBalance(op);
                } catch (Exception ignored) {}
            }
        }

        String rank = resolveRankPrefix(uuid, name);

        String faction = "";
        Player onlineP = Bukkit.getPlayer(uuid);
        if (onlineP != null) {
            faction = getFactionTag(onlineP);
        } else {
            // Joueur hors-ligne : lookup par UUID directement dans l'API Factions
            faction = getFactionTagByUUID(uuid);
        }

        int streak = 0;
        long bounty = 0L;
        KillstreakManager ksm = KillstreakManager.getInstance();
        if (ksm != null) streak = ksm.getStreak(uuid);
        BountyManager bm = BountyManager.getInstance();
        if (bm != null) {
            BountyInfo bi = bm.getBounty(uuid);
            if (bi != null) bounty = bi.getAmount();
        }

        return new ProfilePayload(name, faction, rank, kills, deaths, ptMin, balance, streak, bounty);
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
            Class<?> fpClass   = Class.forName("com.massivecraft.factions.FPlayers");
            Object   fpAll     = fpClass.getMethod("getInstance").invoke(null);
            Object   fp        = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, requester);
            if (fp == null) return 4;
            Object pFaction    = fp.getClass().getMethod("getFaction").invoke(fp);
            if (pFaction == null) return 4;
            if (Boolean.TRUE.equals(tryBoolean(pFaction, "isWilderness"))) return 4;

            String ownTag = (String) pFaction.getClass().getMethod("getTag").invoke(pFaction);
            if (targetTag.equals(ownTag)) return 0; // own faction

            // Find target faction
            Class<?> factionsClass = Class.forName("com.massivecraft.factions.Factions");
            Object   factionsAll   = factionsClass.getMethod("getInstance").invoke(null);
            @SuppressWarnings("unchecked")
            java.util.Collection<?> allFactions =
                (java.util.Collection<?>) factionsAll.getClass().getMethod("getAllFactions").invoke(factionsAll);
            Object targetFaction = null;
            for (Object f : allFactions) {
                String tag = (String) f.getClass().getMethod("getTag").invoke(f);
                if (targetTag.equalsIgnoreCase(tag)) { targetFaction = f; break; }
            }
            if (targetFaction == null) {
                plugin.getLogger().warning("[ProfilCmd] faction tag not found among " + allFactions.size() + " factions: '" + targetTag + "'");
                return 4;
            }

            // Cherche getRelationTo(Faction) — on filtre par isInstance pour éviter de prendre getRelationTo(FPlayer)
            java.lang.reflect.Method relMethod = null;
            for (java.lang.reflect.Method m : fp.getClass().getMethods()) {
                if (m.getName().equals("getRelationTo") && m.getParameterCount() == 1) {
                    if (m.getParameterTypes()[0].isInstance(targetFaction)) {
                        relMethod = m;
                        break;
                    }
                }
            }
            // Fallback : prend n'importe quelle surcharge à 1 param si aucune n'accepte directement targetFaction
            if (relMethod == null) {
                for (java.lang.reflect.Method m : fp.getClass().getMethods()) {
                    if (m.getName().equals("getRelationTo") && m.getParameterCount() == 1) {
                        relMethod = m; break;
                    }
                }
            }
            if (relMethod == null) return 4;
            Object rel = null;
            try { rel = relMethod.invoke(fp, targetFaction); } catch (Exception ignored) {}
            if (rel == null) return 4;
            // toString() est overridé dans Saber-Factions (retourne "§7ennemi" etc.)
            // name() retourne le nom de la constante enum : ENEMY, ALLY, TRUCE, MEMBER
            String relName;
            try { relName = (String) rel.getClass().getMethod("name").invoke(rel); }
            catch (Exception e) { relName = rel.toString(); }
            plugin.getLogger().info("[ProfilCmd] relation=" + relName + " own=" + ownTag + " target=" + targetTag);
            if (relName.equalsIgnoreCase("MEMBER"))  return 0;
            if (relName.equalsIgnoreCase("ALLY"))    return 1;
            if (relName.equalsIgnoreCase("TRUCE"))   return 2;
            if (relName.equalsIgnoreCase("ENEMY"))   return 3;
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
     * Récupère le tag de faction d'un joueur (en ligne ou hors ligne) par UUID.
     * Plusieurs tentatives pour couvrir toutes les versions de Factions.
     */
    private String getFactionTagByUUID(UUID uuid) {
        // Tentative 1 : FPlayers (FactionsUUID)
        try {
            Class<?> fpClass = Class.forName("com.massivecraft.factions.FPlayers");
            Object fpAll = fpClass.getMethod("getInstance").invoke(null);

            Object fp = null;
            // a) getById(String)
            try {
                fp = fpAll.getClass().getMethod("getById", String.class).invoke(fpAll, uuid.toString());
            } catch (Exception ignored) {}
            // b) getByPlayer(Player)
            if (fp == null && Bukkit.getPlayer(uuid) != null) {
                try {
                    fp = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, Bukkit.getPlayer(uuid));
                } catch (Exception ignored) {}
            }
            // c) getByOfflinePlayer(OfflinePlayer)
            if (fp == null) {
                try {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                    fp = fpAll.getClass().getMethod("getByOfflinePlayer", org.bukkit.OfflinePlayer.class).invoke(fpAll, op);
                } catch (Exception ignored) {}
            }

            // d) Itération sur getAllFPlayers
            if (fp == null) {
                try {
                    java.util.Collection<?> col = (java.util.Collection<?>) fpAll.getClass().getMethod("getAllFPlayers").invoke(fpAll);
                    for (Object obj : col) {
                        try {
                            String id = (String) obj.getClass().getMethod("getId").invoke(obj);
                            if (uuid.toString().equals(id)) {
                                fp = obj; break;
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }

            if (fp != null) {
                Object faction = fp.getClass().getMethod("getFaction").invoke(fp);
                if (faction != null) {
                    if (!Boolean.TRUE.equals(tryBoolean(faction, "isSafeZone")) &&
                        !Boolean.TRUE.equals(tryBoolean(faction, "isWarZone")) &&
                        !Boolean.TRUE.equals(tryBoolean(faction, "isWilderness"))) {
                        String tag = (String) faction.getClass().getMethod("getTag").invoke(faction);
                        if (tag != null && !tag.isEmpty()) return tag;
                    }
                }
            }
        } catch (Exception ignored) {}

        // Tentative 2 : MPlayer (Factions moderne)
        try {
            Class<?> mpClass = Class.forName("com.massivecraft.factions.entity.MPlayer");
            Object mp = mpClass.getMethod("get", UUID.class).invoke(null, uuid);
            if (mp != null) {
                Object faction = mp.getClass().getMethod("getFaction").invoke(mp);
                if (faction != null) {
                    String n = (String) faction.getClass().getMethod("getName").invoke(faction);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {}

        return "";
    }

    private Boolean tryBoolean(Object obj, String method) {
        try { return (Boolean) obj.getClass().getMethod(method).invoke(obj); }
        catch (Exception e) { return null; }
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
        final int kills, deaths, ptMin, streak;
        final long balance, bounty;

        ProfilePayload(String name, String faction, String rank,
                       int kills, int deaths, int ptMin,
                       long balance, int streak, long bounty) {
            this.name = name;
            this.faction = faction;
            this.rank = rank;
            this.kills = kills;
            this.deaths = deaths;
            this.ptMin = ptMin;
            this.balance = balance;
            this.streak = streak;
            this.bounty = bounty;
        }
    }
}
