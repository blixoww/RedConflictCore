package fr.redconflict.data;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.RankResolver;
import fr.redconflict.core.economy.VaultEconomy;
import fr.redconflict.data.PlayerDatabase;
import fr.redconflict.ks.KsListener;
import fr.redconflict.packets.PacketBuilder;
import fr.redconflict.packets.PacketReader;
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
    private final RedConflictCore plugin;

    public PlayerDataServerHandler(RedConflictCore plugin) {
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
            Economy eco = VaultEconomy.get();
            if (eco != null) {
                try { balance = (long) eco.getBalance(player); } catch (Exception ignored) {}
            }

            // Rang frais (Vault Chat, secours PlaceholderAPI).
            String rank = RankResolver.resolve(player);

            // ── Faction — fraîche depuis RedFaction ───────────────────────────────
            String faction = resolveFactionTag(player);

            // ── Streak & Bounty — managers in-memory ─────────────────────────────
            int streak = 0;
            long bounty = 0L;
            fr.redconflict.bounty.BountyManager bm = fr.redconflict.bounty.BountyManager.getInstance();
            if (bm != null) {
                streak = bm.getKillstreaks().getStreak(player.getUniqueId());
                fr.redconflict.bounty.BountyInfo bi = bm.getBounty(player.getUniqueId());
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

    /**
     * Tag de faction du joueur, ou {@code ""} si l'intégration RedFaction est
     * coupée ({@link fr.redconflict.faction.FactionHook}) ou s'il n'a pas de faction.
     * Les appels RedFaction restent isolés ici : sur un serveur sans RedFaction,
     * cette méthode retourne avant d'avoir à charger la moindre classe faction.
     */
    private static String resolveFactionTag(Player player) {
        if (!fr.redconflict.faction.FactionHook.isEnabled()) return "";
        try {
            if (fr.redfaction.api.RedFactionAPI.isAvailable()) {
                fr.redfaction.entity.Faction fac =
                    fr.redfaction.api.RedFactionAPI.get().getPlayerFaction(player);
                if (fac != null && fac.isNormal()) {
                    String tag = fac.getTag();
                    if (tag != null && !tag.isEmpty()) return tag;
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static void sendAllPlayerData(Player player) {
        RedConflictCore plugin = RedConflictCore.getInstance();
        PlayerDatabase db = plugin.getPlayerDatabase();

        // Rang frais (Vault Chat, secours PlaceholderAPI).
        String rank = RankResolver.resolve(player);

        // ── Balance — fraîche depuis Vault ───────────────────────────────────────
        long balance = 0L;
        Economy eco = VaultEconomy.get();
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
        RedConflictCore plugin = RedConflictCore.getInstance();
        byte[] data = PacketBuilder.create(80).writeLong(balance).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }

    public static void sendRank(Player player, String rank) {
        RedConflictCore plugin = RedConflictCore.getInstance();
        byte[] data = PacketBuilder.create(81).writeString(rank).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }

    /** Envoie le solde PB courant du joueur (packet PLAYER_PB = 0x53). */
    public static void sendPB(Player player, int pb) {
        RedConflictCore plugin = RedConflictCore.getInstance();
        byte[] data = PacketBuilder.create(0x53).writeVarInt(pb).build();
        player.sendPluginMessage((Plugin)plugin, "CUSTOM:PDATA_S2C", data);
    }
}
