package nl.pinda.framework.modules.itemlimits;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * De item-limiet: hoeveel één speler van bepaalde items mag hebben (en hoeveel er op de hele
 * server zijn). Elk beperkt item krijgt een nummer en een eigenaar; zo telt een item in een kist,
 * de enderkist of de rugtas gewoon mee. Gaat een item op of kapot, dan mag je er weer een.
 *
 * <p>Tegenhouden gebeurt waar items binnenkomen (oppakken, uit een kist halen, craften, kopen);
 * daarna kijkt de module in de inventory en geeft nieuwe items een nummer.
 */
public final class ItemLimitModule extends PindaModule {

    public static final String BYPASS = "pinda.itemlimits.bypass";
    public static final String USE = "pinda.itemlimits.use";
    public static final String OTHERS = "pinda.itemlimits.others";

    /** De databasetabellen van deze module (ook gebruikt bij het omzetten naar MySQL). */
    public static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_item_limits (
                        serial TEXT PRIMARY KEY,
                        rule TEXT NOT NULL,
                        owner TEXT,
                        owner_name TEXT,
                        status TEXT NOT NULL,
                        location TEXT,
                        reason TEXT,
                        created INTEGER NOT NULL,
                        updated INTEGER NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_item_limits_owner ON pinda_item_limits (owner)",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_item_limits_status ON pinda_item_limits (status, rule, updated)"
            )
    );

    /** Wat er gebeurt als iemand meer heeft dan mag (van vóór de limiet, via /give, ...). */
    public enum OverLimit { KEEP, REMOVE }

    /** Waar een item ligt (zo staat het in de database; het paneel maakt er tekst van). */
    static final String IN_INVENTORY = "player";
    static final String IN_ENDERCHEST = "enderchest";
    static final String IN_SHOP = "shop";

    /** Inventories waar niets blijft liggen (bij sluiten gaat alles terug naar de speler). */
    private static final Set<InventoryType> TRANSIENT = java.util.EnumSet.of(InventoryType.CRAFTING, InventoryType.WORKBENCH,
            InventoryType.ANVIL, InventoryType.GRINDSTONE, InventoryType.SMITHING, InventoryType.STONECUTTER, InventoryType.LOOM,
            InventoryType.CARTOGRAPHY, InventoryType.ENCHANTING, InventoryType.MERCHANT, InventoryType.BEACON,
            InventoryType.PLAYER, InventoryType.CREATIVE);

    private static final char[] SERIAL_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final long MESSAGE_GAP_MS = 2_000L;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, LimitRule> rules = new LinkedHashMap<>();
    private final Map<Material, List<LimitRule>> byMaterial = new EnumMap<>(Material.class);
    /** Items die net binnenkomen maar nog geen nummer hebben (tot de volgende controle). */
    private final Map<UUID, Map<String, Integer>> pending = new HashMap<>();
    private final Set<UUID> scanQueued = new HashSet<>();
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private LimitTags tags;
    private Ledger ledger;
    private OverLimit overLimit = OverLimit.KEEP;
    private boolean markItems;
    private boolean notifyFreed;

    public ItemLimitModule(PindaFramework plugin) {
        super(plugin, "itemlimits");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("itemlimits", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de tabel van de item-limiet niet aanmaken", e);
        }
        tags = new LimitTags(plugin);
        ledger = new Ledger(plugin);
        try {
            ledger.load();
        } catch (Exception e) {
            throw new IllegalStateException("Kon de item-limiet niet laden", e);
        }
        loadSettings();
        listen(new LimitListener(plugin, this));
        command(new LimitsCommand(plugin, this));
        // Af en toe in alle inventories kijken: vangt alles wat niet langs een event kwam (/give, andere plugins).
        repeat(this::scanOnline, 40L, 20L * 10);
    }

    @Override
    protected void onDisable() {
        pending.clear();
        scanQueued.clear();
        lastMessage.clear();
    }

    @Override
    protected void onReload() {
        loadSettings();
        scanOnline();
    }

    private void loadSettings() {
        rules.clear();
        byMaterial.clear();
        ConfigurationSection items = config().getConfigurationSection("items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(id);
                if (section == null || !LimitRule.ID.matcher(id.toLowerCase(Locale.ROOT)).matches()) {
                    continue;
                }
                LimitRule rule = LimitRule.parse(id, section);
                if (rule == null) {
                    plugin.getLogger().warning("Item-limiet: '" + id + "' overgeslagen (onbekend item of betovering).");
                    continue;
                }
                rules.put(rule.id(), rule);
            }
        }
        for (LimitRule rule : rules.values()) {
            if (rule.enabled()) {
                byMaterial.computeIfAbsent(rule.material(), key -> new ArrayList<>()).add(rule);
            }
        }
        // Eerst de regels met een betovering: die zijn specifieker (Mending-boek voor "elk boek").
        for (List<LimitRule> list : byMaterial.values()) {
            list.sort((a, b) -> Boolean.compare(a.enchantment() == null, b.enchantment() == null));
        }
        overLimit = "remove".equalsIgnoreCase(config().getString("over-limit", "keep")) ? OverLimit.REMOVE : OverLimit.KEEP;
        markItems = config().getBoolean("mark-items", true);
        notifyFreed = config().getBoolean("notify-freed", true);
    }

    // ============================================================ regels

    /** Alle regels, ook die uit staan (op volgorde van de config). */
    public Collection<LimitRule> rules() {
        return rules.values();
    }

    public LimitRule rule(String id) {
        return id == null ? null : rules.get(id);
    }

    /** De regel waar dit item onder valt (alleen regels die aan staan), of null. */
    public LimitRule ruleFor(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        List<LimitRule> list = byMaterial.get(item.getType());
        if (list == null) {
            return null;
        }
        for (LimitRule rule : list) {
            if (rule.matches(item)) {
                return rule;
            }
        }
        return null;
    }

    /** Is dit een soort item met een limiet? Heel snel (voor hoppers). */
    boolean limitedType(Material type) {
        return byMaterial.containsKey(type);
    }

    /** Kan dit item (of iets erin) onder de limiet vallen? Snel, om events over te slaan. */
    boolean mayInvolve(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        Material type = item.getType();
        return byMaterial.containsKey(type) || isContainer(type) || tags.serial(item) != null;
    }

    private static boolean isContainer(Material type) {
        return type == Material.BUNDLE || type.name().endsWith("_BUNDLE") || type.name().endsWith("SHULKER_BOX");
    }

    /** Zit er (ook in een doos of bundel) een beperkt item in? */
    public boolean involves(ItemStack item) {
        if (!mayInvolve(item)) {
            return false;
        }
        boolean[] found = {false};
        LimitTags.forEach(item, leaf -> {
            if (!found[0] && (ruleFor(leaf) != null || tags.serial(leaf) != null)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    OverLimit overLimit() {
        return overLimit;
    }

    boolean markItems() {
        return markItems;
    }

    boolean notifyFreed() {
        return notifyFreed;
    }

    LimitTags tags() {
        return tags;
    }

    Ledger ledger() {
        return ledger;
    }

    // ============================================================ hoeveel mag er nog bij

    /** Mag deze speler zonder limiet (permissie, of creative/toeschouwer)? */
    public boolean bypass(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.hasPermission(BYPASS);
    }

    /** Hoeveel iemand er van deze regel heeft (met wat er net binnenkomt). */
    public int owned(UUID player, LimitRule rule) {
        return ledger.owned(player, rule.id());
    }

    /** Hoeveel er nog bij mogen. fresh = nieuwe items voor de server (zonder nummer). */
    int room(Player player, LimitRule rule, boolean fresh) {
        int room = playerRoom(player, rule);
        return fresh ? Math.min(room, serverRoom(rule)) : room;
    }

    private int playerRoom(Player player, LimitRule rule) {
        if (rule.perPlayer() <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, rule.perPlayer() - ledger.owned(player.getUniqueId(), rule.id()) - pending(player.getUniqueId(), rule.id()));
    }

    private int serverRoom(LimitRule rule) {
        if (rule.serverMax() <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, rule.serverMax() - ledger.total(rule.id()) - pendingTotal(rule.id()));
    }

    private int pending(UUID player, String rule) {
        Map<String, Integer> map = pending.get(player);
        return map == null ? 0 : map.getOrDefault(rule, 0);
    }

    private int pendingTotal(String rule) {
        int total = 0;
        for (Map<String, Integer> map : pending.values()) {
            total += map.getOrDefault(rule, 0);
        }
        return total;
    }

    /**
     * Waarom iemand iets (nu) niet mag krijgen.
     *
     * @param rule    de regel die het tegenhoudt (null = het mag)
     * @param server  het maximum van de server is bereikt (anders dat van de speler)
     * @param partial bij een gewone stapel zonder nummers: hoeveel er nog wel bij mogen
     * @param needs   per regel: hoeveel er bij komen (om te reserveren)
     */
    record Check(LimitRule rule, boolean server, int partial, Map<String, Integer> needs) {
        boolean allowed() {
            return rule == null;
        }
    }

    /** Mag deze speler dit item (met alles erin) erbij krijgen? */
    Check check(Player player, ItemStack incoming) {
        Map<String, Integer> needs = new LinkedHashMap<>();
        Map<String, Integer> fresh = new HashMap<>();
        UUID uuid = player.getUniqueId();
        LimitTags.forEach(incoming, leaf -> {
            LimitRule rule = ruleFor(leaf);
            if (rule == null) {
                return;
            }
            String serial = tags.serial(leaf);
            boolean tagged = serial != null && rule.id().equals(tags.rule(leaf));
            Ledger.Entry entry = tagged ? ledger.get(serial) : null;
            if (entry != null && uuid.equals(entry.owner)) {
                return; // is al van hem
            }
            int amount = tagged ? 1 : leaf.getAmount();
            needs.merge(rule.id(), amount, Integer::sum);
            if (entry == null) {
                fresh.merge(rule.id(), amount, Integer::sum);
            }
        });
        for (Map.Entry<String, Integer> need : needs.entrySet()) {
            LimitRule rule = rules.get(need.getKey());
            if (need.getValue() > playerRoom(player, rule)) {
                return new Check(rule, false, partial(player, incoming, rule), needs);
            }
            if (fresh.getOrDefault(rule.id(), 0) > serverRoom(rule)) {
                return new Check(rule, true, partial(player, incoming, rule), needs);
            }
        }
        return new Check(null, false, 0, needs);
    }

    /** Een gewone stapel zonder nummer en zonder iets erin: hoeveel mogen er wel bij? */
    private int partial(Player player, ItemStack incoming, LimitRule rule) {
        if (incoming.getAmount() <= 1 || tags.serial(incoming) != null || !rule.equals(ruleFor(incoming)) || isContainer(incoming.getType())) {
            return 0;
        }
        return Math.min(incoming.getAmount(), room(player, rule, true));
    }

    /** Houdt plek vrij voor items die net binnenkomen (tot de inventory weer bekeken is). */
    void reserve(Player player, Check check) {
        if (check.needs().isEmpty()) {
            return;
        }
        Map<String, Integer> map = pending.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>());
        check.needs().forEach((rule, amount) -> map.merge(rule, amount, Integer::sum));
        claimSoon(player);
    }

    /** Hoeveel exemplaren van dit item iemand nog mag krijgen (voor de shop). */
    public int room(Player player, ItemStack template) {
        if (!isEnabled() || !involves(template) || bypass(player)) {
            return Integer.MAX_VALUE;
        }
        LimitRule rule = ruleFor(template);
        if (rule != null && tags.serial(template) == null && !isContainer(template.getType())) {
            return room(player, rule, true);
        }
        return check(player, template).allowed() ? 1 : 0;
    }

    /** Iemand kreeg items buiten de events om (bijv. gekocht): straks in zijn inventory nummeren. */
    public void received(Player player, ItemStack template, int amount) {
        if (!isEnabled() || !involves(template)) {
            return;
        }
        ItemStack copy = template.clone();
        copy.setAmount(Math.max(1, Math.min(amount, 6400)));
        if (!bypass(player)) {
            reserve(player, check(player, copy));
        }
        claimSoon(player);
    }

    /** Laat de speler weten waarom iets niet kan (niet vaker dan eens per paar seconden). */
    void explain(Player player, Check check, int taken) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_GAP_MS) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        LimitRule rule = check.rule();
        Component item = rule.displayName();
        if (taken > 0) {
            plugin.lang().send(player, "itemlimits.partial", Text.p("amount", taken), Text.c("item", item));
        } else if (check.server()) {
            plugin.lang().send(player, "itemlimits.server-full", Text.c("item", item), Text.p("max", rule.serverMax()));
        } else {
            plugin.lang().send(player, "itemlimits.blocked", Text.c("item", item),
                    Text.p("count", owned(player.getUniqueId(), rule)), Text.p("max", rule.perPlayer()));
        }
        plugin.theme().play(player, "error");
    }

    /** Uitleg waarom iets niet kan, voor de shop (die zelf al weet dat het niet kan). */
    public void explainLimit(Player player, ItemStack template) {
        Check check = check(player, template);
        if (!check.allowed()) {
            lastMessage.remove(player.getUniqueId());
            explain(player, check, 0);
        }
    }

    void craftOneByOne(Player player, LimitRule rule) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_GAP_MS) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        plugin.lang().send(player, "itemlimits.craft-one", Text.c("item", rule.displayName()));
        plugin.theme().play(player, "error");
    }

    // ============================================================ nummers geven

    /** Een nieuw nummer, bijv. 7KQ2X9HDM4. */
    String newSerial() {
        for (;;) {
            char[] chars = new char[10];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = SERIAL_CHARS[random.nextInt(SERIAL_CHARS.length)];
            }
            String serial = new String(chars);
            if (ledger.get(serial) == null) {
                return serial;
            }
        }
    }

    private Component mark() {
        return markItems ? plugin.lang().component(plugin.lang().defaultLanguage(), "itemlimits.mark") : null;
    }

    /**
     * Maakt van een stapel (zonder nummer) losse items met een nummer, van deze speler. Voor het
     * gedeeltelijk oppakken of uit een kist halen.
     */
    List<ItemStack> claimNew(Player player, ItemStack template, LimitRule rule, int amount, String location) {
        List<ItemStack> units = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            ItemStack unit = template.clone();
            unit.setAmount(1);
            String serial = newSerial();
            tags.tag(unit, rule.id(), serial, mark());
            ledger.register(serial, rule.id(), player.getUniqueId(), player.getName(), location);
            units.add(unit);
        }
        return units;
    }

    // ============================================================ in de inventory kijken

    /** Kijkt over één tick in de inventory (één keer, ook als het vaker gevraagd wordt). */
    public void claimSoon(Player player) {
        if (!isEnabled() || !scanQueued.add(player.getUniqueId())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            scanQueued.remove(player.getUniqueId());
            if (isEnabled() && player.isOnline()) {
                scan(player);
            }
        });
    }

    void scanOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            scan(player);
        }
    }

    /**
     * Kijkt in de inventory (en op de cursor): nieuwe beperkte items krijgen een nummer en worden
     * van de speler; items van een ander worden van hem (als het mag); wat niet meer beperkt is,
     * wordt weer een gewoon item. Hetzelfde nummer twee keer (een dupe): de tweede krijgt een nieuw nummer.
     */
    void scan(Player player) {
        pending.remove(player.getUniqueId());
        PlayerInventory inventory = player.getInventory();
        Set<String> seen = new HashSet<>();
        List<ItemStack> overflow = new ArrayList<>();
        Map<String, Integer> removedPerRule = new HashMap<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!mayInvolve(item)) {
                continue;
            }
            List<ItemStack> result = LimitTags.map(item, leaf -> claim(player, leaf, IN_INVENTORY, seen, removedPerRule));
            if (result == null) {
                continue;
            }
            inventory.setItem(slot, result.isEmpty() ? null : result.get(0));
            overflow.addAll(result.subList(Math.min(1, result.size()), result.size()));
        }
        ItemStack cursor = player.getItemOnCursor();
        if (mayInvolve(cursor)) {
            List<ItemStack> result = LimitTags.map(cursor, leaf -> claim(player, leaf, IN_INVENTORY, seen, removedPerRule));
            if (result != null) {
                player.setItemOnCursor(result.isEmpty() ? null : result.get(0));
                overflow.addAll(result.subList(Math.min(1, result.size()), result.size()));
            }
        }
        if (!overflow.isEmpty()) {
            for (ItemStack rest : inventory.addItem(overflow.toArray(ItemStack[]::new)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        removedPerRule.forEach((id, amount) -> {
            LimitRule rule = rules.get(id);
            plugin.lang().send(player, "itemlimits.removed", Text.p("amount", amount), Text.c("item", rule.displayName()),
                    Text.p("max", rule.perPlayer()));
            plugin.getLogger().info("[Item-limiet] " + amount + "x " + rule.id() + " weggehaald bij " + player.getName()
                    + " (meer dan " + rule.perPlayer() + ").");
        });
        if (!removedPerRule.isEmpty()) {
            plugin.theme().play(player, "error");
        }
    }

    /** Wat er met één item in de inventory moet gebeuren (zie {@link #scan}). */
    private List<ItemStack> claim(Player player, ItemStack item, String location, Set<String> seen, Map<String, Integer> removed) {
        LimitRule rule = ruleFor(item);
        String serial = tags.serial(item);
        UUID uuid = player.getUniqueId();
        if (rule == null) {
            if (serial == null) {
                return null;
            }
            // Niet (meer) beperkt: weer een gewoon item. Dat nummer bestaat dan nergens meer.
            ledger.gone(serial, "niet meer beperkt");
            ItemStack plain = item.clone();
            tags.untag(plain);
            return List.of(plain);
        }
        boolean free = bypass(player) || overLimit == OverLimit.KEEP;
        if (serial != null && rule.id().equals(tags.rule(item)) && item.getAmount() == 1 && seen.add(serial)) {
            Ledger.Entry entry = ledger.get(serial);
            if (entry != null && uuid.equals(entry.owner)) {
                ledger.locate(entry, location);
                return null;
            }
            if (free || room(player, rule, entry == null) > 0) {
                if (entry == null) {
                    ledger.register(serial, rule.id(), uuid, player.getName(), location);
                } else {
                    ledger.transfer(entry, uuid, player.getName(), location);
                }
                return null;
            }
            removed.merge(rule.id(), 1, Integer::sum);
            if (entry == null) {
                ledger.register(serial, rule.id(), uuid, player.getName(), location);
            }
            ledger.gone(serial, "boven het maximum, weggehaald");
            return List.of();
        }
        // Zonder nummer (of een dubbel nummer, een stapel, of onder een andere regel): nieuwe nummers.
        ItemStack base = item.clone();
        if (serial != null) {
            if (!rule.id().equals(tags.rule(item))) {
                ledger.gone(serial, "onder een andere regel");
            } else if (item.getAmount() > 1) {
                ledger.gone(serial, "gestapeld");
            } else {
                // Het eerste exemplaar houdt het nummer.
                plugin.getLogger().warning("[Item-limiet] " + player.getName() + " heeft item " + serial
                        + " dubbel (mogelijk een dupe); het tweede exemplaar krijgt een nieuw nummer.");
            }
            tags.untag(base);
        }
        int amount = base.getAmount();
        int allowed = free ? amount : Math.min(amount, room(player, rule, true));
        if (allowed < amount) {
            removed.merge(rule.id(), amount - allowed, Integer::sum);
        }
        return claimNew(player, base, rule, allowed, location);
    }

    // ============================================================ verdwenen en verplaatst

    /** Dit item (en wat erin zat) bestaat niet meer: de eigenaren mogen er weer een. */
    public void destroyed(ItemStack item, String reason) {
        if (!isEnabled() || !mayInvolve(item)) {
            return;
        }
        LimitTags.forEach(item, leaf -> gone(tags.serial(leaf), reason, false));
    }

    /** Hetzelfde, voor een hele inventory (bijv. een rugtas die verdwijnt). */
    public void destroyed(ItemStack[] items, String reason) {
        if (items != null) {
            for (ItemStack item : items) {
                destroyed(item, reason);
            }
        }
    }

    /**
     * Alleen dit item zelf (niet wat erin zit) is weg: opgebruikt, kapot, verbrand. Met tell krijgt
     * de eigenaar (als die online is) te horen dat hij er weer een mag.
     */
    void used(ItemStack item, String reason, boolean tell) {
        if (item != null && !item.isEmpty()) {
            gone(tags.serial(item), reason, tell && notifyFreed);
        }
    }

    /** Een nummer is weg (bijv. verwerkt in een aambeeld of crafting table). */
    void consumed(String serial, String reason) {
        gone(serial, reason, false);
    }

    /** Wat er in dit item zit (niet het item zelf) ligt nu hier: een geplaatste shulkerdoos. */
    void locatedInside(ItemStack item, String location) {
        if (!isEnabled() || location == null || item == null || !item.hasItemMeta()) {
            return;
        }
        LimitTags.forEach(item, leaf -> {
            if (leaf == item) {
                return;
            }
            Ledger.Entry entry = ledger.get(tags.serial(leaf));
            if (entry != null) {
                ledger.locate(entry, location);
            }
        });
    }

    void joined(Player player) {
        ledger.rename(player.getUniqueId(), player.getName());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (isEnabled() && player.isOnline()) {
                scan(player);
            }
        }, 20L);
    }

    void quit(UUID player) {
        pending.remove(player);
        scanQueued.remove(player);
        lastMessage.remove(player);
    }

    private void gone(String serial, String reason, boolean tell) {
        if (serial == null) {
            return;
        }
        Ledger.Entry entry = ledger.gone(serial, reason);
        if (entry == null || !tell || entry.owner == null) {
            return;
        }
        LimitRule rule = rules.get(entry.rule);
        Player owner = plugin.getServer().getPlayer(entry.owner);
        if (rule != null && owner != null && rule.perPlayer() > 0) {
            plugin.lang().send(owner, "itemlimits.freed", Text.c("item", rule.displayName()),
                    Text.p("count", ledger.owned(entry.owner, rule.id())), Text.p("max", rule.perPlayer()));
        }
    }

    /** Dit item (en wat erin zit) ligt nu hier. De eigenaar blijft dezelfde. */
    public void located(ItemStack item, String location) {
        if (!isEnabled() || location == null || !mayInvolve(item)) {
            return;
        }
        LimitTags.forEach(item, leaf -> {
            Ledger.Entry entry = ledger.get(tags.serial(leaf));
            if (entry != null) {
                ledger.locate(entry, location);
            }
        });
    }

    /** Een admin geeft een item vrij in het paneel: het telt niet meer mee. */
    public boolean release(String serial, String by) {
        return ledger.gone(serial, "vrijgegeven door " + by) != null;
    }

    /**
     * Een regel is weggehaald: de items tellen nergens meer mee. Duiken ze later op, dan worden
     * het gewone items (of, als de regel terugkomt, nieuwe items met een nummer).
     */
    public int forget(String rule, String reason) {
        List<Ledger.Entry> entries = ledger.ofRule(rule);
        for (Ledger.Entry entry : entries) {
            ledger.gone(entry.serial, reason);
        }
        return entries.size();
    }

    // ============================================================ plaatsen

    static String at(Location location) {
        World world = location.getWorld();
        return (world == null ? "?" : world.getName()) + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    /** Waar de items van deze inventory liggen, of null als het geen echte plek is (een menu). */
    String where(Inventory inventory, Player viewer) {
        if (inventory instanceof PlayerInventory own) {
            return own.getHolder() != null && own.getHolder().equals(viewer) ? IN_INVENTORY : null;
        }
        if (inventory.getType() == InventoryType.ENDER_CHEST) {
            return IN_ENDERCHEST;
        }
        if (TRANSIENT.contains(inventory.getType())) {
            return null; // een werkbank, aambeeld, ...: wat erin ligt, gaat bij sluiten terug
        }
        InventoryHolder holder = inventory.getHolder(false);
        String backpack = nl.pinda.framework.modules.backpack.BackpackModule.describe(holder);
        if (backpack != null) {
            return backpack;
        }
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return "container:" + inventory.getType().name().toLowerCase(Locale.ROOT) + ":" + at(location);
    }

    /** Een plek als tekst, in de taal van de lezer (zie itemlimits.where in de taalbestanden). */
    public Component describe(String location, String code) {
        if (location == null || location.isBlank()) {
            return plugin.lang().component(code, "itemlimits.where.unknown");
        }
        String[] parts = location.split(":");
        String kind = parts[0];
        switch (kind) {
            case IN_INVENTORY, IN_ENDERCHEST, IN_SHOP -> {
                return plugin.lang().component(code, "itemlimits.where." + kind);
            }
            case "backpack" -> {
                return plugin.lang().component(code, "itemlimits.where.backpack",
                        Text.p("player", parts.length > 1 ? location.substring(kind.length() + 1) : "?"));
            }
            default -> {
                // soort[:type]:wereld:x:y:z
                if (parts.length < 5 || !List.of("container", "ground", "frame", "stand", "entity", "dropped-backpack").contains(kind)) {
                    return plugin.lang().component(code, "itemlimits.where.unknown");
                }
                int n = parts.length;
                String type = n >= 6 ? parts[1].replace('_', ' ') : "";
                return plugin.lang().component(code, "itemlimits.where." + kind, Text.p("type", type),
                        Text.p("world", parts[n - 4]), Text.p("x", parts[n - 3]), Text.p("y", parts[n - 2]), Text.p("z", parts[n - 1]));
            }
        }
    }

    // ============================================================ paneel

    /** Opslaan vanuit het paneel (de regel staat al in de config); daarna alles opnieuw inlezen. */
    public void saveAndReload() {
        saveConfig();
        loadSettings();
        scanOnline();
    }

    public org.bukkit.configuration.file.YamlConfiguration settings() {
        return config();
    }

    /** Overzicht per regel: hoeveel er zijn en bij hoeveel spelers. */
    public Map<String, int[]> totals() {
        Map<String, int[]> totals = new HashMap<>();
        Map<String, Set<UUID>> owners = new HashMap<>();
        for (Ledger.Entry entry : ledger.all()) {
            totals.computeIfAbsent(entry.rule, key -> new int[2])[0]++;
            if (entry.owner != null) {
                owners.computeIfAbsent(entry.rule, key -> new HashSet<>()).add(entry.owner);
            }
        }
        owners.forEach((rule, set) -> totals.computeIfAbsent(rule, key -> new int[2])[1] = set.size());
        return totals;
    }

    /** De items van één speler (voor /limieten en het spelersprofiel). */
    public List<LimitedItem> itemsOf(UUID player) {
        return ledger.of(player).stream().map(Ledger.Entry::view).toList();
    }

    public List<LimitedItem> itemsOfRule(String rule) {
        return ledger.ofRule(rule).stream().map(Ledger.Entry::view).toList();
    }

    public CompletableFuture<List<LimitedItem.Gone>> recentlyGone(String rule, int limit) {
        return ledger.recentlyGone(rule, limit);
    }

    void logError(String message, Throwable error) {
        plugin.getLogger().log(Level.WARNING, message, error);
    }
}
