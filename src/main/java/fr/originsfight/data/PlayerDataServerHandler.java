package fr.originsfight.data;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.data.PlayerDatabase;
import fr.originsfight.ks.KsListener;
import fr.originsfight.packets.PacketBuilder;
import fr.originsfight.packets.PacketReader;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import java.io.IOException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class PlayerDataServerHandler implements PluginMessageListener {
    private final OriginsFightCore plugin;

    public PlayerDataServerHandler(OriginsFightCore plugin) {
        this.plugin = plugin;
    }

    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            PacketReader reader = new PacketReader(message);
            int packetId = reader.readVarInt();
            if (packetId == 88) {          // 0x58 – PLAYER_DATA_REQUEST
                sendAllPlayerData(player);
            } else if (packetId == 89) {   // 0x59 – PROFILE_REQUEST_OWN
                sendProfileOpen(player);
            }
        } catch (IOException e) {
            this.plugin.getLogger().severe("[PlayerData] Erreur : " + e.getMessage());
        }
    }

    /**
     * Répond à PROFILE_REQUEST_OWN (0x59) en envoyant un paquet PROFILE_OPEN (0x90)
     * avec les données fraîches du joueur.
     */
    private void sendProfileOpen(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerDatabase db = plugin.getPlayerDatabase();

            // ── Kills / Deaths / Playtime — depuis PlayerDatabase ────────────────
            int kills = 0, deaths = 0, ptMin = 0;
            PlayerDatabase.PlayerProfile cached = db != null ? db.getProfile(player.getUniqueId()) : null;
            if (cached != null) {
                long sessionSec = 0;
                Long joinTime = KsListener.getJoinTime(player.getUniqueId());
                if (joinTime != null) sessionSec = (System.currentTimeMillis() - joinTime) / 1000;
                kills  = cached.kills;
                deaths = cached.deaths;
                ptMin  = (int) ((cached.playtimeSeconds + sessionSec) / 60);
            }

            // ── Balance — fraîche depuis Vault ───────────────────────────────────
            long balance = 0L;
            Economy eco = plugin.getEconomy();
            if (eco != null) {
                try { balance = (long) eco.getBalance(player); } catch (Exception ignored) {}
            }

            // ── Rang — frais depuis Vault Chat ───────────────────────────────────
            String rank = "Joueur";
            try {
                RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
                if (rsp != null) {
                    Chat chat = rsp.getProvider();
                    String prefix = chat.getPlayerPrefix(player);
                    if (prefix == null || prefix.isEmpty()) {
                        String group = chat.getPrimaryGroup(player);
                        if (group != null && !group.isEmpty()) prefix = group;
                    }
                    if (prefix != null && !prefix.trim().isEmpty()) {
                        String plain = prefix.replaceAll("(?i)§.", "").replaceAll("(?i)&.", "").trim();
                        if (!plain.isEmpty()) rank = prefix.trim();
                    }
                }
            } catch (Exception ignored) {}

            // ── Faction — fraîche depuis Factions ────────────────────────────────
            String faction = "";
            try {
                Class<?> fpClass = Class.forName("com.massivecraft.factions.FPlayers");
                Object fpAll = fpClass.getMethod("getInstance").invoke(null);
                Object fp = fpAll.getClass().getMethod("getByPlayer", Player.class).invoke(fpAll, player);
                if (fp != null) {
                    Object fac = fp.getClass().getMethod("getFaction").invoke(fp);
                    if (fac != null) {
                        Boolean w = tryBoolean(fac, "isWilderness");
                        if (!Boolean.TRUE.equals(w)) {
                            String tag = (String) fac.getClass().getMethod("getTag").invoke(fac);
                            if (tag != null && !tag.isEmpty()) faction = tag;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // ── Streak & Bounty — managers in-memory ─────────────────────────────
            int streak = 0;
            long bounty = 0L;
            fr.originsfight.bounty.KillstreakManager ksm = fr.originsfight.bounty.KillstreakManager.getInstance();
            if (ksm != null) streak = ksm.getStreak(player.getUniqueId());
            fr.originsfight.bounty.BountyManager bm = fr.originsfight.bounty.BountyManager.getInstance();
            if (bm != null) {
                fr.originsfight.bounty.BountyInfo bi = bm.getBounty(player.getUniqueId());
                if (bi != null) bounty = bi.getAmount();
            }

            // ── Persistance des snapshots dans PlayerDatabase ─────────────────────
            if (db != null) {
                db.updateBalance(player.getUniqueId(), balance);
                db.updateRank(player.getUniqueId(), rank);
                db.updateFaction(player.getUniqueId(), faction);
                db.setStreak(player.getUniqueId(), streak);
                db.setBounty(player.getUniqueId(), bounty);
            }

            final String fFaction = faction, fRank = rank;
            final int fKills = kills, fDeaths = deaths, fPtMin = ptMin, fStreak = streak;
            final long fBalance = balance, fBounty = bounty;

            Bukkit.getScheduler().runTask(plugin, () -> {
                byte[] data = PacketBuilder.create(0x90)
                        .writeString(truncate(player.getName(), 32))
                        .writeString(truncate(fFaction, 32))
                        .writeString(truncate(fRank, 32))
                        .writeVarInt(fKills)
                        .writeVarInt(fDeaths)
                        .writeVarInt(fPtMin)
                        .writeLong(fBalance)
                        .writeVarInt(fStreak)
                        .writeLong(fBounty)
                        .writeVarInt(0) // factionRelation = 0 (own)
                        .build();
                player.sendPluginMessage(plugin, "CUSTOM:PDATA_S2C", data);
            });
        });
    }

    private Boolean tryBoolean(Object obj, String method) {
        try { return (Boolean) obj.getClass().getMethod(method).invoke(obj); }
        catch (Exception e) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static void sendAllPlayerData(Player player) {
        OriginsFightCore plugin = OriginsFightCore.getInstance();
        PlayerDatabase db = plugin.getPlayerDatabase();

        // ── Rang — frais depuis Vault Chat ───────────────────────────────────────
        String rank = "Joueur";
        try {
            RegisteredServiceProvider<Chat> rsp = Bukkit.getServicesManager().getRegistration(Chat.class);
            if (rsp != null) {
                Chat chat = rsp.getProvider();
                String prefix = chat.getPlayerPrefix(player);
                if (prefix == null || prefix.isEmpty()) {
                    String group = chat.getPrimaryGroup(player);
                    if (group != null && !group.isEmpty()) prefix = group;
                }
                if (prefix != null && !prefix.trim().isEmpty()) {
                    String plain = prefix.replaceAll("(?i)§.", "").replaceAll("(?i)&.", "").trim();
                    if (!plain.isEmpty()) rank = prefix.trim();
                }
            }
        } catch (Exception ignored) {}

        if ("Joueur".equals(rank)) {
            try {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                java.lang.reflect.Method m = papi.getMethod("setPlaceholders", org.bukkit.entity.Player.class, String.class);
                Object out = m.invoke(null, player, "%luckperms_prefix%");
                if (out instanceof String) {
                    String s = ((String) out).trim();
                    if (!s.isEmpty() && !s.equals("%luckperms_prefix%")) {
                        String plain = s.replaceAll("(?i)§.", "").replaceAll("(?i)&.", "").trim();
                        if (!plain.isEmpty()) rank = s;
                    }
                }
            } catch (Exception ignored) {}
        }

        // ── Balance — fraîche depuis Vault ───────────────────────────────────────
        long balance = 0L;
        Economy eco = plugin.getEconomy();
        if (eco != null) {
            balance = (long) eco.getBalance(player);
        }

        // ── Kills / Deaths / Playtime — depuis PlayerDatabase ────────────────────
        int kills = 0, deaths = 0, playTimeMin = 0;
        if (db != null) {
            PlayerDatabase.PlayerProfile cached = db.getProfile(player.getUniqueId());
            if (cached != null) {
                long sessionSec = 0;
                Long joinTime = KsListener.getJoinTime(player.getUniqueId());
                if (joinTime != null) sessionSec = (System.currentTimeMillis() - joinTime) / 1000;
                kills       = cached.kills;
                deaths      = cached.deaths;
                playTimeMin = (int) ((cached.playtimeSeconds + sessionSec) / 60);
            }
        }

        // ── Persistance des snapshots ─────────────────────────────────────────────
        if (db != null) {
            db.updateBalance(player.getUniqueId(), balance);
            db.updateRank(player.getUniqueId(), rank);
        }

        // PB — solde Points Boutique
        int pb = 0;
        if (plugin.getPBManager() != null) {
            try { pb = plugin.getPBManager().get(player); } catch (Exception ignored) {}
        }
        byte[] data = PacketBuilder.create(82)
                .writeString(rank).writeLong(balance)
                .writeVarInt(kills).writeVarInt(deaths).writeVarInt(playTimeMin)
                .writeVarInt(pb)
                .build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }

    public static void sendBalance(Player player, long balance) {
        OriginsFightCore plugin = OriginsFightCore.getInstance();
        byte[] data = PacketBuilder.create(80).writeLong(balance).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }

    public static void sendRank(Player player, String rank) {
        OriginsFightCore plugin = OriginsFightCore.getInstance();
        byte[] data = PacketBuilder.create(81).writeString(rank).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }

    /** Envoie le solde PB courant du joueur (packet PLAYER_PB = 0x53). */
    public static void sendPB(Player player, int pb) {
        OriginsFightCore plugin = OriginsFightCore.getInstance();
        byte[] data = PacketBuilder.create(0x53).writeVarInt(pb).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }
}
