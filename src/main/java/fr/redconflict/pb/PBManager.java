package fr.redconflict.pb;

import fr.redconflict.RedConflictCore;
import fr.redconflict.data.PlayerDataServerHandler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Façade synchrone autour du {@link PBLedger} pour la monnaie Points Boutique.
 * Toutes les écritures passent ici : journal fichier, journal en base, alertes
 * staff et rafraîchissement de l'affichage client s'y font en un seul endroit.
 *
 * <p>Échelle officielle : 1 € = 10 PB.
 *
 * <p><b>Le solde est partagé avec le site.</b> Depuis la bascule du ledger vers
 * la base d'Azuriom, la boutique web débite exactement la même ligne. Deux
 * conséquences pour l'appelant :
 *
 * <ul>
 *   <li>{@link #has} est <b>indicatif</b>. Entre le test et le débit, un achat
 *       web peut passer. Ne jamais s'en servir pour décider seul de livrer
 *       quelque chose : c'est le {@code false} de {@link #remove} qui fait foi.</li>
 *   <li>Les lectures touchent le réseau. Sur le thread principal ça reste une
 *       requête locale à quelques millisecondes — mais on évite d'en faire une
 *       par tick.</li>
 * </ul>
 */
public class PBManager {

    /** Origine d'un mouvement, pour le journal partagé avec le site. */
    public static final String SOURCE_GAME = "game";
    public static final String SOURCE_ADMIN = "admin";

    private final RedConflictCore plugin;
    private final PBLedger ledger;
    private final PBLogger logger;
    private final StaffAlertManager alerts;

    public PBManager(RedConflictCore plugin, PBLedger ledger, PBLogger logger, StaffAlertManager alerts) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.logger = logger;
        this.alerts = alerts;
    }

    /** Le stockage réellement utilisé — {@code H2} ou {@code site}. */
    public PBLedger getLedger() {
        return ledger;
    }

    /**
     * {@code false} quand le stockage est injoignable. La boutique doit alors
     * refuser l'achat plutôt que de le tenter : avec le ledger partagé, une
     * écriture perdue serait un article livré et jamais payé.
     */
    public boolean isAvailable() {
        return ledger != null && ledger.isAvailable();
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

    public int get(UUID uuid) { return ledger.get(uuid); }

    public int get(OfflinePlayer p) { return ledger.get(p.getUniqueId()); }

    /**
     * Indicatif seulement — voir l'avertissement en tête de classe. Utile pour
     * griser un bouton ou afficher un message, jamais pour autoriser une
     * livraison.
     */
    public boolean has(OfflinePlayer p, int amount) { return get(p) >= amount; }

    // ── Écriture ─────────────────────────────────────────────────────────────

    public synchronized boolean add(OfflinePlayer p, int amount, String reason) {
        return add(p, amount, reason, SOURCE_GAME);
    }

    public synchronized boolean add(OfflinePlayer p, int amount, String reason, String source) {
        if (amount <= 0) return false;
        ledger.ensure(p.getUniqueId(), p.getName());
        boolean ok = ledger.add(p.getUniqueId(), amount);
        if (ok) {
            int bal = ledger.get(p.getUniqueId());
            record("ADD", p, amount, bal, reason, source);
            pushBalance(p.getUniqueId(), bal);
        }
        return ok;
    }

    public synchronized boolean remove(OfflinePlayer p, int amount, String reason) {
        return remove(p, amount, reason, SOURCE_GAME);
    }

    public synchronized boolean remove(OfflinePlayer p, int amount, String reason, String source) {
        if (amount <= 0) return false;
        ledger.ensure(p.getUniqueId(), p.getName());
        boolean ok = ledger.remove(p.getUniqueId(), amount);
        int bal = ledger.get(p.getUniqueId());
        if (ok) {
            record("REMOVE", p, -amount, bal, reason, source);
            pushBalance(p.getUniqueId(), bal);
        } else {
            // Échec journalisé au fichier seulement : un refus n'est pas un
            // mouvement, il n'a rien à faire dans le journal des soldes.
            logger.log("REMOVE_FAIL", p.getName(), amount, bal, reason);
        }
        return ok;
    }

    public synchronized boolean set(OfflinePlayer p, int amount, String reason) {
        return set(p, amount, reason, SOURCE_ADMIN);
    }

    public synchronized boolean set(OfflinePlayer p, int amount, String reason, String source) {
        if (amount < 0) return false;
        ledger.ensure(p.getUniqueId(), p.getName());
        ledger.set(p.getUniqueId(), amount);
        record("SET", p, amount, amount, reason, source);
        pushBalance(p.getUniqueId(), amount);
        return true;
    }

    /**
     * Transfert atomique entre deux joueurs. Rollback automatique si la seconde
     * opération échoue. Utilisé par /trade et l'HDV.
     */
    public synchronized boolean transfer(OfflinePlayer from, OfflinePlayer to, int amount, String reason) {
        if (amount <= 0) return false;
        if (!remove(from, amount, "TRANSFER_OUT->" + to.getName() + ":" + reason)) return false;
        if (!add(to, amount, "TRANSFER_IN<-" + from.getName() + ":" + reason)) {
            // Le crédit a échoué : on rend au débiteur ce qu'on lui a pris. Passe
            // par le ledger sans repasser par add(), pour que le remboursement
            // n'apparaisse pas comme un gain dans les alertes staff.
            ledger.add(from.getUniqueId(), amount);
            int bal = ledger.get(from.getUniqueId());
            logger.log("ROLLBACK", from.getName(), amount, bal, reason);
            pushBalance(from.getUniqueId(), bal);
            return false;
        }
        return true;
    }

    // ── Journalisation ───────────────────────────────────────────────────────

    /** Journal fichier + journal en base + alerte staff, en une fois. */
    private void record(String action, OfflinePlayer p, int delta, int balance, String reason, String source) {
        int magnitude = Math.abs(delta);
        logger.log(action, p.getName(), magnitude, balance, reason);
        alerts.notify(action, p.getName(), magnitude, reason);
        if (ledger instanceof SitePBLedger) {
            ((SitePBLedger) ledger).journal(p.getUniqueId(), p.getName(), delta, balance, source, reason);
        }
    }

    /** Pousse le nouveau solde PB au client si le joueur est en ligne (packet 0x53). */
    private void pushBalance(UUID uuid, int newBalance) {
        try {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                PlayerDataServerHandler.sendPB(online, newBalance);
            }
        } catch (Exception ignored) { }
    }
}
