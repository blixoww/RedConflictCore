package fr.originsfight.pb;

import fr.originsfight.OriginsFightCore;
import fr.originsfight.data.PlayerDatabase;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * Façade synchrone autour de PlayerDatabase pour la monnaie Points Boutique (PB).
 * Toutes les écritures passent ici et sont loggées + déclenchent les alertes staff.
 *
 * Échelle officielle : 1€ = 10 PB.
 */
public class PBManager {

    private final OriginsFightCore plugin;
    private final PlayerDatabase db;
    private final PBLogger logger;
    private final StaffAlertManager alerts;

    public PBManager(OriginsFightCore plugin, PlayerDatabase db, PBLogger logger, StaffAlertManager alerts) {
        this.plugin = plugin;
        this.db = db;
        this.logger = logger;
        this.alerts = alerts;
    }

    // ── Conversion ───────────────────────────────────────────────────────────

    /** Convertit un montant en monnaie vers PB (1€ = 10 PB). */
    public static int moneyToPB(double money) {
        return (int) Math.round(money * 10D);
    }

    /** Convertit un montant en PB vers monnaie (10 PB = 1€). */
    public static double pbToMoney(int pb) {
        return pb / 10D;
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    public int get(UUID uuid) { return db.getPB(uuid); }

    public int get(OfflinePlayer p) {
        db.ensurePlayer(p.getUniqueId(), p.getName());
        return db.getPB(p.getUniqueId());
    }

    public boolean has(OfflinePlayer p, int amount) { return get(p) >= amount; }

    // ── Écriture ─────────────────────────────────────────────────────────────

    public synchronized boolean add(OfflinePlayer p, int amount, String reason) {
        if (amount <= 0) return false;
        db.ensurePlayer(p.getUniqueId(), p.getName());
        boolean ok = db.addPB(p.getUniqueId(), amount);
        if (ok) {
            int bal = db.getPB(p.getUniqueId());
            logger.log("ADD", p.getName(), amount, bal, reason);
            alerts.notify("ADD", p.getName(), amount, reason);
        }
        return ok;
    }

    public synchronized boolean remove(OfflinePlayer p, int amount, String reason) {
        if (amount <= 0) return false;
        db.ensurePlayer(p.getUniqueId(), p.getName());
        boolean ok = db.removePB(p.getUniqueId(), amount);
        int bal = db.getPB(p.getUniqueId());
        logger.log(ok ? "REMOVE" : "REMOVE_FAIL", p.getName(), amount, bal, reason);
        if (ok) alerts.notify("REMOVE", p.getName(), amount, reason);
        return ok;
    }

    public synchronized boolean set(OfflinePlayer p, int amount, String reason) {
        if (amount < 0) return false;
        db.ensurePlayer(p.getUniqueId(), p.getName());
        db.setPB(p.getUniqueId(), amount);
        logger.log("SET", p.getName(), amount, amount, reason);
        alerts.notify("SET", p.getName(), amount, reason);
        return true;
    }

    /**
     * Transfert atomique entre deux joueurs. Rollback automatique si la seconde
     * opération échoue. Utilisé par /trade et l'HDV (intégration future).
     */
    public synchronized boolean transfer(OfflinePlayer from, OfflinePlayer to, int amount, String reason) {
        if (amount <= 0) return false;
        if (!remove(from, amount, "TRANSFER_OUT->" + to.getName() + ":" + reason)) return false;
        if (!add(to, amount, "TRANSFER_IN<-" + from.getName() + ":" + reason)) {
            db.addPB(from.getUniqueId(), amount);
            logger.log("ROLLBACK", from.getName(), amount, db.getPB(from.getUniqueId()), reason);
            return false;
        }
        return true;
    }
}
