package fr.originsfight.essentials.economy;

import fr.originsfight.essentials.repository.AccountRepository;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider Vault Economy natif RedConflict : remplace l'économie d'EssentialsX.
 *
 * <p>Les soldes vivent dans la base H2 centrale ({@code ess_accounts}) et sont
 * donc partagés entre tous les serveurs du cluster. Les joueurs en ligne sont
 * servis depuis un cache mémoire ; chaque transaction est écrite immédiatement
 * en base (write-through) pour rester durable et cohérente inter-serveurs.
 *
 * <p>Les banques Vault ne sont pas supportées (aucun usage sur le serveur).
 */
public class CoreEconomyProvider implements Economy {

    private static final String NOT_IMPLEMENTED_BANKS = "Les banques ne sont pas supportées.";

    private final AccountRepository repository;
    private final double startingBalance;
    private final String currencySymbol;
    private final DecimalFormat format;

    /** Soldes des joueurs en ligne (uuid → solde). */
    private final Map<UUID, Double> cache = new ConcurrentHashMap<>();

    public CoreEconomyProvider(AccountRepository repository, double startingBalance, String currencySymbol) {
        this.repository = repository;
        this.startingBalance = startingBalance;
        this.currencySymbol = currencySymbol;
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        this.format = new DecimalFormat("#,##0.00", symbols);
    }

    // ── Cycle de vie (appelé par ConnectionListener) ───────────────────────────

    /** Crée le compte si besoin et charge le solde en cache. */
    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        Double balance = repository.findBalance(uuid);
        if (balance == null) {
            balance = startingBalance;
            repository.save(uuid, player.getName(), balance);
        } else {
            // Rafraîchit le dernier nom connu (résolution hors ligne par nom).
            repository.save(uuid, player.getName(), balance);
        }
        cache.put(uuid, balance);
    }

    public void handleQuit(Player player) {
        cache.remove(player.getUniqueId());
    }

    // ── Cœur : accès aux soldes par UUID ───────────────────────────────────────

    private double balanceOf(UUID uuid) {
        Double cached = cache.get(uuid);
        if (cached != null) return cached;
        Double stored = repository.findBalance(uuid);
        return stored != null ? stored : 0.0;
    }

    private boolean accountExists(UUID uuid) {
        return cache.containsKey(uuid) || repository.exists(uuid);
    }

    private synchronized EconomyResponse change(UUID uuid, String name, double delta) {
        double balance = balanceOf(uuid);
        double updated = balance + delta;
        if (updated < 0) {
            return new EconomyResponse(0.0, balance, ResponseType.FAILURE, "Fonds insuffisants.");
        }
        persist(uuid, name, updated);
        return new EconomyResponse(Math.abs(delta), updated, ResponseType.SUCCESS, null);
    }

    /** Écrit le nouveau solde (base + cache si le joueur est en ligne). */
    private synchronized void persist(UUID uuid, String name, double balance) {
        String knownName = name;
        if (knownName == null) knownName = repository.findName(uuid);
        repository.save(uuid, knownName != null ? knownName : "", balance);
        if (cache.containsKey(uuid)) {
            cache.put(uuid, balance);
        }
    }

    /** Fixe un solde absolu (utilisé par /eco set). */
    public void setBalance(OfflinePlayer player, double balance) {
        persist(player.getUniqueId(), player.getName(), Math.max(0.0, balance));
    }

    /** Résout un nom : joueur en ligne, sinon comptes connus, sinon UUID hors ligne Bukkit. */
    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        UUID known = repository.findUuidByName(name);
        if (known != null) return Bukkit.getOfflinePlayer(known);
        return Bukkit.getOfflinePlayer(name);
    }

    // ── Identité du provider ───────────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "RedConflict";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return format.format(amount) + currencySymbol;
    }

    @Override
    public String currencyNamePlural() {
        return currencySymbol;
    }

    @Override
    public String currencyNameSingular() {
        return currencySymbol;
    }

    // ── Comptes ────────────────────────────────────────────────────────────────

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return accountExists(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName) {
        return hasAccount(resolve(playerName));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (accountExists(player.getUniqueId())) return false;
        persist(player.getUniqueId(), player.getName(), startingBalance);
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(resolve(playerName));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    // ── Soldes ─────────────────────────────────────────────────────────────────

    @Override
    public double getBalance(OfflinePlayer player) {
        return balanceOf(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(resolve(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    // ── Transactions ───────────────────────────────────────────────────────────

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0.0, getBalance(player), ResponseType.FAILURE, "Montant négatif.");
        }
        return change(player.getUniqueId(), player.getName(), -amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(resolve(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0.0, getBalance(player), ResponseType.FAILURE, "Montant négatif.");
        }
        return change(player.getUniqueId(), player.getName(), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(resolve(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    // ── Banques : non supportées ───────────────────────────────────────────────

    @Override
    public EconomyResponse createBank(String name, String player) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return banksNotSupported();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return banksNotSupported();
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }

    private EconomyResponse banksNotSupported() {
        return new EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, NOT_IMPLEMENTED_BANKS);
    }
}
