package fr.originsfight.job;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.packets.PacketBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Envoie tous les packets job serveur → client.
 *
 * Canaux : CUSTOM:JOB_S2C
 *
 * PacketIds (S2C) :
 *   0xE0 JOB_DATA     — snapshot complet du joueur
 *   0xE1 JOB_LEVELUP  — animation niveau
 *   0xE2 JOB_XP_GAIN  — action bar XP
 *   0xE3 JOB_TOP      — classement
 *   0xE4 JOB_INIT     — config complète (50 niveaux × 3 métiers)
 *   0xE5 JOB_OPEN     — ouvre le GUI
 */
public class JobPacketSender {

    public static final String CHANNEL_S2C = "CUSTOM:JOB_S2C";

    private static final int PKT_JOB_DATA    = 0xE0;
    private static final int PKT_JOB_LEVELUP = 0xE1;
    private static final int PKT_JOB_XP_GAIN = 0xE2;
    private static final int PKT_JOB_TOP     = 0xE3;
    private static final int PKT_JOB_INIT    = 0xE4;
    private static final int PKT_JOB_OPEN       = 0xE5;
    private static final int PKT_JOB_XP_SOURCES = 0xE6;

    private final OriginsFightCore plugin;
    private final JobConfig        config;
    private final JobDatabase      database;

    public JobPacketSender(OriginsFightCore plugin, JobConfig config, JobDatabase database) {
        this.plugin   = plugin;
        this.config   = config;
        this.database = database;
    }

    // ── JOB_INIT ─────────────────────────────────────────────────────────────

    /**
     * Envoie la configuration complète des niveaux (récompenses, XP requis).
     * Appelé une fois à la connexion.
     */
    public void sendJobInit(Player player) {
        int max = config.getMaxLevels();
        PacketBuilder pb = PacketBuilder.create(PKT_JOB_INIT);
        pb.writeVarInt(max);
        for (JobType jt : new JobType[]{JobType.MINER, JobType.FARMER, JobType.ARTISAN}) {
            for (int lvl = 1; lvl <= max; lvl++) {
                pb.writeVarInt(config.getXpRequired(lvl));
                pb.writeLong(config.getMoneyReward(jt, lvl));
                pb.writeString(buildItemsString(config.getItemRewards(jt, lvl)));
            }
        }
        send(player, pb.build());
    }

    // ── JOB_DATA ─────────────────────────────────────────────────────────────

    public void sendJobData(Player player, JobDatabase.JobData d) {
        byte[] pkt = PacketBuilder.create(PKT_JOB_DATA)
                .writeVarInt(d.minerLevel)
                .writeVarInt(d.minerXp)
                .writeVarInt(d.farmerLevel)
                .writeVarInt(d.farmerXp)
                .writeVarInt(d.artisanLevel)
                .writeVarInt(d.artisanXp)
                .build();
        send(player, pkt);
    }

    // ── JOB_LEVELUP ──────────────────────────────────────────────────────────

    public void sendLevelUp(Player player, JobType job, int newLevel,
                            long moneyReward, List<ItemStack> items, String rewardString) {
        byte[] pkt = PacketBuilder.create(PKT_JOB_LEVELUP)
                .writeString(job.name())
                .writeVarInt(newLevel)
                .writeLong(moneyReward)
                .writeString(rewardString)
                .build();
        send(player, pkt);
    }

    // ── JOB_XP_GAIN ──────────────────────────────────────────────────────────

    public void sendXpGain(Player player, JobType job, int xpGained,
                           int currentXp, int currentLevel, int xpForNext) {
        byte[] pkt = PacketBuilder.create(PKT_JOB_XP_GAIN)
                .writeString(job.name())
                .writeVarInt(xpGained)
                .writeVarInt(currentXp)
                .writeVarInt(currentLevel)
                .writeVarInt(xpForNext)
                .build();
        send(player, pkt);
    }

    // ── JOB_TOP ──────────────────────────────────────────────────────────────

    public void sendTop(Player player, String jobKey, List<JobDatabase.TopEntry> entries) {
        PacketBuilder pb = PacketBuilder.create(PKT_JOB_TOP);
        pb.writeString(jobKey);
        pb.writeVarInt(entries.size());
        for (JobDatabase.TopEntry e : entries) {
            pb.writeString(e.name);
            pb.writeString(e.job.name());
            pb.writeVarInt(e.level);
            pb.writeVarInt(e.xp);
        }
        send(player, pb.build());
    }

    // ── JOB_OPEN ─────────────────────────────────────────────────────────────

    public void sendJobOpen(Player player) {
        send(player, PacketBuilder.create(PKT_JOB_OPEN).build());
    }

    // ── JOB_XP_SOURCES ───────────────────────────────────────────────────────

    public void sendXpSources(Player player) {
        JobType[] jobs = {JobType.MINER, JobType.FARMER, JobType.ARTISAN};
        PacketBuilder pb = PacketBuilder.create(PKT_JOB_XP_SOURCES);
        pb.writeVarInt(jobs.length);
        for (JobType jt : jobs) {
            List<JobConfig.XpSourceEntry> sources = config.getXpSources(jt);
            pb.writeString(jt.name());
            pb.writeVarInt(sources.size());
            for (JobConfig.XpSourceEntry s : sources) {
                pb.writeString(s.category);
                pb.writeString(s.label);
                pb.writeVarInt(s.xp);
            }
        }
        send(player, pb.build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void send(Player player, byte[] data) {
        player.sendPluginMessage((Plugin) plugin, CHANNEL_S2C, data);
    }

    private String buildItemsString(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ItemStack is : items) {
            if (is == null) continue;
            if (sb.length() > 0) sb.append("|");
            sb.append(is.getType().name()).append(":").append(is.getAmount());
        }
        return sb.toString();
    }
}


