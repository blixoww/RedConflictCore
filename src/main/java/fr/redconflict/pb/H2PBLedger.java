package fr.redconflict.pb;

import fr.redconflict.data.PlayerDatabase;

import java.util.UUID;

/**
 * Ledger historique : le solde vit dans {@code player_profiles.pb} (H2).
 *
 * <p>Conservé comme filet de repli ({@code pb.ledger: h2}) et parce que c'est la
 * source de la migration vers {@link SitePBLedger}. Dans cette configuration la
 * boutique du site ne peut pas fonctionner : elle n'a aucun accès à H2.
 */
public final class H2PBLedger implements PBLedger {

    private final PlayerDatabase db;

    public H2PBLedger(PlayerDatabase db) {
        this.db = db;
    }

    @Override public String getName() { return "H2"; }

    @Override public boolean isAvailable() { return db != null; }

    @Override public void ensure(UUID uuid, String name) { db.ensurePlayer(uuid, name); }

    @Override public int get(UUID uuid) { return db.getPB(uuid); }

    @Override public boolean add(UUID uuid, int amount) { return db.addPB(uuid, amount); }

    @Override public boolean remove(UUID uuid, int amount) { return db.removePB(uuid, amount); }

    @Override public void set(UUID uuid, int amount) { db.setPB(uuid, amount); }
}
