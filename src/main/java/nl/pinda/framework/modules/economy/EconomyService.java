package nl.pinda.framework.modules.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.economy.Economy;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.modules.afk.AfkModule;
import nl.pinda.framework.storage.Database;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Alle geldzaken: saldo's (contant en bank), betalen, storten, geld verliezen bij doodgaan
 * en de online-bonus. Is ook de {@link Economy} die de rest van het framework gebruikt.
 */
public final class EconomyService implements Economy, Listener {

    public static final String KEEP_CASH = "pinda.eco.keep-cash";
    private static final long PRELOAD_TIMEOUT_MS = 60_000L;

    private final PindaFramework plugin;
    private final EconomyModule module;
    private final Map<UUID, Account> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loadedAt = new ConcurrentHashMap<>();
    private final NamespacedKey moneyKey;
    private final NamespacedKey moneyIdKey;

    /** Resultaat van een storting: het bedrag, de kosten en wat er op de bank kwam. */
    public record Deposit(long amount, long fee, long credited) {
    }

    /** Een regel in /baltop. */
    public record TopEntry(String name, long total) {
    }

    public EconomyService(PindaFramework plugin, EconomyModule module) {
        this.plugin = plugin;
        this.module = module;
        this.moneyKey = new NamespacedKey(plugin, "money");
        this.moneyIdKey = new NamespacedKey(plugin, "money_id");
    }

    private YamlConfiguration cfg() {
        return module.cfg();
    }

    // ============================================================ opmaak

    /** Een bedrag in centen als tekst, bijv. "1.250 PindaCredits" of "2,50 PindaCredits". */
    public String format(long cents) {
        NumberFormat number = NumberFormat.getNumberInstance(Locale.forLanguageTag(plugin.lang().defaultLanguage()));
        boolean whole = cents % 100 == 0;
        number.setMinimumFractionDigits(whole ? 0 : 2);
        number.setMaximumFractionDigits(whole ? 0 : 2);
        String name = cents == 100
                ? cfg().getString("currency.singular", "PindaCredit")
                : cfg().getString("currency.plural", "PindaCredits");
        return number.format(cents / 100.0) + " " + name;
    }

    /** Alleen het getal, zonder valutanaam. */
    public String formatNumber(long cents) {
        NumberFormat number = NumberFormat.getNumberInstance(Locale.forLanguageTag(plugin.lang().defaultLanguage()));
        boolean whole = cents % 100 == 0;
        number.setMinimumFractionDigits(whole ? 0 : 2);
        number.setMaximumFractionDigits(whole ? 0 : 2);
        return number.format(cents / 100.0);
    }

    // ============================================================ rekeningen

    /** De rekening van een online speler. Laadt hem direct als dat nog niet gebeurd is. */
    public Account account(Player player) {
        Account account = cache.get(player.getUniqueId());
        if (account == null) {
            try {
                account = loadOrCreate(player.getUniqueId()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Kon de rekening van " + player.getName() + " niet laden", e);
            }
            cache.put(player.getUniqueId(), account);
            loadedAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
        return account;
    }

    /** De rekening als hij al geladen is (online spelers), anders null. Laadt nooit iets; veilig vanaf elke thread. */
    public Account cached(UUID uuid) {
        return cache.get(uuid);
    }

    /** Leest een rekening zonder hem aan te maken. Null als de speler nog geen rekening heeft. */
    public CompletableFuture<Account> peek(UUID uuid) {
        Account cached = cache.get(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT cash, bank, playtime FROM pinda_economy WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return new Account(uuid, result.getLong("cash"), result.getLong("bank"), result.getInt("playtime"));
                    }
                    return null;
                }
            }
        });
    }

    private CompletableFuture<Account> loadOrCreate(UUID uuid) {
        long startCash = Money.toCents(cfg().getDouble("starting-balance.cash", 0));
        long startBank = Money.toCents(cfg().getDouble("starting-balance.bank", 100));
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT cash, bank, playtime FROM pinda_economy WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return new Account(uuid, result.getLong("cash"), result.getLong("bank"), result.getInt("playtime"));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO pinda_economy (uuid, cash, bank, playtime) VALUES (?, ?, ?, 0)")) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, startCash);
                statement.setLong(3, startBank);
                statement.executeUpdate();
            }
            insertLog(connection, uuid, "start", startCash + startBank, startCash, startBank, null);
            return new Account(uuid, startCash, startBank, 0);
        });
    }

    private void save(Account account) {
        final long cash = account.cash();
        final long bank = account.bank();
        final int playtime = account.playtime();
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pinda_economy SET cash = ?, bank = ?, playtime = ? WHERE uuid = ?")) {
                statement.setLong(1, cash);
                statement.setLong(2, bank);
                statement.setInt(3, playtime);
                statement.setString(4, account.uuid().toString());
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon saldo van " + account.uuid() + " niet opslaan", error);
            return null;
        });
    }

    /** Schrijft een regel in het transactielog (handig om later fouten of misbruik terug te zien). */
    private void log(UUID uuid, String type, long amount, Account after, String note) {
        final Long cash = after == null ? null : after.cash();
        final Long bank = after == null ? null : after.bank();
        plugin.database().execute(connection -> insertLog(connection, uuid, type, amount, cash, bank, note))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Kon transactie niet loggen", error);
                    return null;
                });
    }

    private static void insertLog(java.sql.Connection connection, UUID uuid, String type, long amount,
                                  Long cash, Long bank, String note) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pinda_economy_log (time, uuid, type, amount, cash_after, bank_after, note) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, uuid.toString());
            statement.setString(3, type);
            statement.setLong(4, amount);
            if (cash == null) {
                statement.setNull(5, Types.INTEGER);
            } else {
                statement.setLong(5, cash);
            }
            if (bank == null) {
                statement.setNull(6, Types.INTEGER);
            } else {
                statement.setLong(6, bank);
            }
            statement.setString(7, note);
            statement.executeUpdate();
        }
    }

    // ============================================================ geld bewegen

    /** Hoeveel iemand kan uitgeven: contant, plus de bank als dat aan staat. */
    public long spendable(Account account) {
        return account.cash() + (cfg().getBoolean("spend-from-bank", true) ? account.bank() : 0);
    }

    /** Haalt geld weg: eerst contant, daarna van de bank. False als er te weinig is. */
    public boolean take(Player player, long cents, String type, String note) {
        if (cents <= 0) {
            return true;
        }
        Account account = account(player);
        synchronized (account) {
            if (spendable(account) < cents) {
                return false;
            }
            long fromCash = Math.min(account.cash(), cents);
            account.addCash(-fromCash);
            long rest = cents - fromCash;
            if (rest > 0) {
                account.addBank(-rest);
            }
        }
        save(account);
        log(player.getUniqueId(), type, -cents, account, note);
        return true;
    }

    /**
     * Geeft geld aan een speler, online of offline.
     *
     * @param toBank true = op de bank, false = contant
     */
    public CompletableFuture<Void> give(UUID uuid, long cents, boolean toBank, String type, String note) {
        Account cached = cache.get(uuid);
        if (cached != null) {
            synchronized (cached) {
                if (toBank) {
                    cached.bank(Math.max(0, cached.bank() + cents));
                } else {
                    cached.cash(Math.max(0, cached.cash() + cents));
                }
            }
            save(cached);
            log(uuid, type, cents, cached, note);
            return CompletableFuture.completedFuture(null);
        }
        long startCash = Money.toCents(cfg().getDouble("starting-balance.cash", 0));
        long startBank = Money.toCents(cfg().getDouble("starting-balance.bank", 100));
        String column = toBank ? "bank" : "cash";
        return plugin.database().execute(connection -> Database.transaction(connection, tx -> {
            try (PreparedStatement statement = tx.prepareStatement(
                    "INSERT OR IGNORE INTO pinda_economy (uuid, cash, bank, playtime) VALUES (?, ?, ?, 0)")) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, startCash);
                statement.setLong(3, startBank);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = tx.prepareStatement(
                    "UPDATE pinda_economy SET " + column + " = MAX(0, " + column + " + ?) WHERE uuid = ?")) {
                statement.setLong(1, cents);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            insertLog(tx, uuid, type, cents, null, null, note);
        }));
    }

    /** Zet het saldo (contant of bank) op een vast bedrag. Voor beheerders. */
    public CompletableFuture<Void> set(UUID uuid, long cents, boolean bank, String note) {
        Account cached = cache.get(uuid);
        if (cached != null) {
            if (bank) {
                cached.bank(cents);
            } else {
                cached.cash(cents);
            }
            save(cached);
            log(uuid, "admin-set", cents, cached, note);
            return CompletableFuture.completedFuture(null);
        }
        String column = bank ? "bank" : "cash";
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_economy (uuid, cash, bank, playtime) VALUES (?, 0, 0, 0) "
                            + "ON CONFLICT(uuid) DO NOTHING")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pinda_economy SET " + column + " = ? WHERE uuid = ?")) {
                statement.setLong(1, cents);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            insertLog(connection, uuid, "admin-set", cents, null, null, note);
        });
    }

    /** De stortkosten voor een bedrag. */
    public long depositFee(long cents) {
        long fee = Math.max(Money.toCents(cfg().getDouble("bank.minimum-fee", 0)),
                Money.percentage(cents, cfg().getDouble("bank.deposit-fee-percent", 2.0)));
        return Math.min(fee, cents);
    }

    public double depositFeePercent() {
        return cfg().getDouble("bank.deposit-fee-percent", 2.0);
    }

    /** Het stortpercentage als tekst, bijv. "2" of "2,5". */
    public String formatPercent(double percent) {
        NumberFormat number = NumberFormat.getNumberInstance(Locale.forLanguageTag(plugin.lang().defaultLanguage()));
        number.setMaximumFractionDigits(2);
        return number.format(percent);
    }

    /** Minimaal bedrag voor /pay, in centen. */
    public long payMinimum() {
        return Math.max(1, Money.toCents(cfg().getDouble("pay.minimum", 1)));
    }

    /** Stort contant geld op de bank, minus de stortkosten. Null als er te weinig contant is. */
    public Deposit deposit(Player player, long cents) {
        Account account = account(player);
        long fee;
        long credited;
        synchronized (account) {
            if (cents <= 0 || account.cash() < cents) {
                return null;
            }
            fee = depositFee(cents);
            credited = cents - fee;
            account.addCash(-cents);
            account.addBank(credited);
        }
        save(account);
        log(player.getUniqueId(), "deposit", cents, account, "kosten " + fee);
        return new Deposit(cents, fee, credited);
    }

    /** Haalt geld van de bank naar contant. False als er te weinig op de bank staat. */
    public boolean withdrawFromBank(Player player, long cents) {
        Account account = account(player);
        synchronized (account) {
            if (cents <= 0 || account.bank() < cents) {
                return false;
            }
            account.addBank(-cents);
            account.addCash(cents);
        }
        save(account);
        log(player.getUniqueId(), "withdraw", cents, account, null);
        return true;
    }

    /** De rijkste spelers (contant + bank). Let op: wordt niet op de hoofdthread afgerond. */
    public CompletableFuture<List<TopEntry>> top(int limit) {
        return plugin.database().query(connection -> {
            List<TopEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(p.name, e.uuid) AS name, e.cash + e.bank AS total
                    FROM pinda_economy e LEFT JOIN pinda_players p ON p.uuid = e.uuid
                    WHERE e.cash + e.bank > 0
                    ORDER BY total DESC LIMIT ?""")) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        entries.add(new TopEntry(result.getString("name"), result.getLong("total")));
                    }
                }
            }
            return entries;
        });
    }

    // ============================================================ Economy (voor de rest van het framework)

    @Override
    public boolean isEnabled() {
        return module.isEnabled();
    }

    @Override
    public boolean has(UUID player, double amount) {
        Account account = cache.get(player);
        return account != null && spendable(account) >= Money.toCents(amount);
    }

    @Override
    public boolean withdraw(UUID player, double amount) {
        Player online = plugin.getServer().getPlayer(player);
        return online != null && take(online, Money.toCents(amount), "spend", null);
    }

    @Override
    public void deposit(UUID player, double amount) {
        give(player, Money.toCents(amount), false, "income", null);
    }

    @Override
    public String format(double amount) {
        return format(Money.toCents(amount));
    }

    // ============================================================ online-bonus

    /** Wordt elke minuut aangeroepen: telt actieve speeltijd en geeft de bonus. */
    public void playtimeTick() {
        if (!cfg().getBoolean("playtime-bonus.enabled", true)) {
            return;
        }
        int minutes = Math.max(1, cfg().getInt("playtime-bonus.minutes", 60));
        long amount = Money.toCents(cfg().getDouble("playtime-bonus.amount", 25));
        boolean toBank = "bank".equalsIgnoreCase(cfg().getString("playtime-bonus.to", "cash"));
        boolean skipAfk = cfg().getBoolean("playtime-bonus.skip-afk", true);
        AfkModule afk = plugin.modules().get(AfkModule.class);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (skipAfk && afk != null && afk.isEnabled() && afk.isAfk(player)) {
                continue;
            }
            Account account = account(player);
            int played = account.playtime() + 1;
            if (played >= minutes) {
                played = 0;
                if (toBank) {
                    account.addBank(amount);
                } else {
                    account.addCash(amount);
                }
                log(player.getUniqueId(), "playtime", amount, account, null);
                plugin.lang().send(player, "economy.playtime-bonus",
                        Text.p("amount", format(amount)), Text.p("minutes", minutes));
                plugin.theme().play(player, "success");
            }
            account.playtime(played);
            save(account);
        }
    }

    // ============================================================ geld als item (bij doodgaan)

    public ItemStack moneyItem(long cents) {
        Material material = Material.matchMaterial(cfg().getString("death.item", "GOLD_NUGGET"));
        if (material == null || !material.isItem()) {
            material = Material.GOLD_NUGGET;
        }
        ItemStack item = ItemBuilder.of(material)
                .name(plugin.lang().component(plugin.lang().defaultLanguage(), "economy.money-item",
                        Text.p("amount", format(cents))))
                .glint(true)
                .build();
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(moneyKey, PersistentDataType.LONG, cents);
            meta.getPersistentDataContainer().set(moneyIdKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        });
        return item;
    }

    /** Het bedrag van een geld-item, of null als het geen geld is. */
    public Long moneyValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(moneyKey, PersistentDataType.LONG);
    }

    // ============================================================ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        double percent = Math.min(100, cfg().getDouble("death.lose-cash-percent", 100));
        if (percent <= 0 || player.hasPermission(KEEP_CASH)) {
            return;
        }
        Account account = account(player);
        long lost;
        synchronized (account) {
            lost = Money.percentage(account.cash(), percent);
            if (lost <= 0) {
                return;
            }
            account.addCash(-lost);
        }
        save(account);
        log(player.getUniqueId(), "death", -lost, account, null);

        boolean drop = cfg().getBoolean("death.drop-as-item", true);
        if (drop) {
            player.getWorld().dropItemNaturally(player.getLocation(), moneyItem(lost));
        }
        plugin.lang().send(player, drop ? "economy.death-dropped" : "economy.death-lost",
                Text.p("amount", format(lost)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Item entity = event.getItem();
        Long cents = moneyValue(entity.getItemStack());
        if (cents == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getEntity() instanceof Player player) || entity.isDead() || !entity.isValid()) {
            return;
        }
        entity.remove();
        if (cents <= 0) {
            return;
        }
        Account account = account(player);
        account.addCash(cents);
        save(account);
        log(player.getUniqueId(), "pickup", cents, account, null);
        plugin.lang().send(player, "economy.money-picked-up", Text.p("amount", format(cents)));
        plugin.theme().play(player, "success");
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        if (moneyValue(event.getItem().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        if (moneyValue(event.getEntity().getItemStack()) != null || moneyValue(event.getTarget().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            cache.put(event.getUniqueId(), loadOrCreate(event.getUniqueId()).get(10, TimeUnit.SECONDS));
            loadedAt.put(event.getUniqueId(), System.currentTimeMillis());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.SEVERE, "Kon de rekening van " + event.getName() + " niet laden", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.lang().component(plugin.lang().defaultLanguage(), "general.data-load-failed"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
        loadedAt.remove(event.getPlayer().getUniqueId());
    }

    /** Ruimt rekeningen op van spelers die wel geladen zijn maar niet online kwamen. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<UUID> iterator = cache.keySet().iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            if (plugin.getServer().getPlayer(uuid) != null) {
                continue;
            }
            Long loaded = loadedAt.get(uuid);
            if (loaded == null || now - loaded > PRELOAD_TIMEOUT_MS) {
                iterator.remove();
                loadedAt.remove(uuid);
            }
        }
    }

    public void loadOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            account(player);
        }
    }

    public void clear() {
        cache.clear();
        loadedAt.clear();
    }
}
