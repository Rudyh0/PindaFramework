package nl.pinda.framework.modules.backpack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.economy.Account;
import nl.pinda.framework.modules.economy.EconomyModule;
import nl.pinda.framework.modules.economy.EconomyService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * De rugtas (/backpack, /bp, /rugtas, /rugzak, /rt, /rz): een extra inventory van 3 rijen.
 * Het laatste vakje is een vaste gouden staaf die laat zien hoeveel contant geld je hebt.
 *
 * <p>Ga je dood, dan ligt je rugtas met je contante geld op die plek. De eerste minuten kan
 * alleen jij hem openen, daarna iedereen. Wie hem na het looten sluit, laat hem in rook
 * opgaan; wat erin bleef zitten is dan weg. Niemand geweest? Dan verdwijnt hij na een tijd.
 */
public final class BackpackModule extends PindaModule implements Listener {

    public static final String USE = "pinda.backpack.use";
    public static final String OTHERS = "pinda.backpack.others";
    public static final String OTHERS_EDIT = "pinda.backpack.others.edit";
    public static final String LOOT = "pinda.backpack.loot";

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_backpacks (
                        uuid TEXT PRIMARY KEY,
                        items BLOB,
                        updated INTEGER NOT NULL DEFAULT 0
                    )"""
            )
    );

    /** Een gevallen rugtas, voor het paneel. */
    public record DroppedInfo(String world, int x, int y, int z, long ownerOnlyUntil, long expiresAt, int items, long cash) {
    }

    private BackpackStore store;
    private NamespacedKey moneyKey;
    private final Map<UUID, ItemStack[]> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Inventory> open = new HashMap<>();
    private final Map<UUID, Long> pendingCash = new HashMap<>();
    private final Map<UUID, DroppedBackpack> dropped = new LinkedHashMap<>();
    private EconomyService hooked;

    public BackpackModule(PindaFramework plugin) {
        super(plugin, "backpack");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("backpack", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de rugtas-tabel niet aanmaken", e);
        }
        store = new BackpackStore(plugin);
        moneyKey = new NamespacedKey(plugin, "backpack_money");
        listen(this);
        command(new BackpackCommand(plugin, this));
        hooked = economy();
        if (hooked != null) {
            hooked.setDeathCashHandler(this::catchCash);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            store.load(uuid).thenAccept(items -> cache.putIfAbsent(uuid, items));
        }
        repeat(this::tick, 20L, 20L);
    }

    @Override
    protected void onDisable() {
        if (hooked != null) {
            hooked.setDeathCashHandler(null);
            hooked = null;
        }
        // Open rugtassen sluiten (dat slaat ze op)
        for (Inventory inventory : new ArrayList<>(open.values())) {
            for (HumanEntity viewer : new ArrayList<>(inventory.getViewers())) {
                viewer.closeInventory();
            }
        }
        // Gevallen rugtassen: bij een herstart niets laten verdwijnen, maar op de grond leggen
        for (DroppedBackpack bag : new ArrayList<>(dropped.values())) {
            spill(bag);
        }
        dropped.clear();
        open.clear();
        pendingCash.clear();
        cache.clear();
    }

    // ============================================================ instellingen

    public int rows() {
        return Math.max(1, Math.min(6, config().getInt("rows", 3)));
    }

    /** Laat de rugtas het contante geld zien (gouden staaf)? */
    public boolean moneyShown() {
        return economy() != null && config().getBoolean("money.enabled", true);
    }

    /** Het vakje van de gouden staaf (het laatste), of -1. */
    public int moneySlot() {
        return moneyShown() ? rows() * 9 - 1 : -1;
    }

    /** Hoeveel vakjes je echt kunt gebruiken. */
    public int capacity() {
        return rows() * 9 - (moneyShown() ? 1 : 0);
    }

    private EconomyService economy() {
        EconomyModule module = plugin.modules().get(EconomyModule.class);
        return module != null && module.isEnabled() ? module.service() : null;
    }

    private boolean allowedHere(Player player) {
        for (String world : config().getStringList("disabled-worlds")) {
            if (world.equalsIgnoreCase(player.getWorld().getName())) {
                plugin.lang().send(player, "backpack.disabled-world");
                return false;
            }
        }
        List<String> modes = config().getStringList("allowed-gamemodes");
        if (!modes.isEmpty() && modes.stream().noneMatch(mode -> mode.equalsIgnoreCase(player.getGameMode().name()))) {
            plugin.lang().send(player, "backpack.not-allowed");
            return false;
        }
        return true;
    }

    // ============================================================ openen

    /** /backpack: je eigen rugtas. */
    void openOwn(Player player) {
        if (allowedHere(player)) {
            open(player, player.getUniqueId(), player.getName());
        }
    }

    /** Opent een rugtas (van jezelf of van iemand anders). */
    void open(Player viewer, UUID owner, String ownerName) {
        Inventory live = open.get(owner);
        if (live != null) {
            viewer.openInventory(live);
            plugin.theme().play(viewer, "menu-open");
            return;
        }
        ItemStack[] items = cache.get(owner);
        if (items != null) {
            show(viewer, owner, ownerName, items);
            return;
        }
        store.load(owner).thenAccept(loaded -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline() || !isEnabled()) {
                return;
            }
            ItemStack[] current = cache.get(owner);
            show(viewer, owner, ownerName, current != null ? current : loaded);
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon de rugtas van " + ownerName + " niet laden", error);
            return null;
        });
    }

    private void show(Player viewer, UUID owner, String ownerName, ItemStack[] items) {
        Inventory live = open.get(owner);
        if (live == null) {
            LanguageManager lang = plugin.lang();
            boolean self = viewer.getUniqueId().equals(owner);
            BackpackHolder holder = new BackpackHolder(owner, ownerName);
            Component title = self ? lang.component(viewer, "backpack.title")
                    : lang.component(viewer, "backpack.title-other", Text.p("player", ownerName));
            live = plugin.getServer().createInventory(holder, rows() * 9, title);
            holder.inventory(live);
            int capacity = capacity();
            List<ItemStack> overflow = new ArrayList<>();
            for (int slot = 0; slot < items.length; slot++) {
                if (items[slot] == null || items[slot].isEmpty()) {
                    continue;
                }
                if (slot < capacity) {
                    live.setItem(slot, items[slot]);
                } else {
                    overflow.add(items[slot]);
                }
            }
            // De rugtas is kleiner geworden: wat niet meer past, past waarschijnlijk wel op een lege plek
            for (ItemStack item : overflow) {
                Map<Integer, ItemStack> left = addToBackpack(live, item);
                Player ownerPlayer = plugin.getServer().getPlayer(owner);
                for (ItemStack rest : left.values()) {
                    if (ownerPlayer != null) {
                        ownerPlayer.getInventory().addItem(rest).values()
                                .forEach(drop -> ownerPlayer.getWorld().dropItemNaturally(ownerPlayer.getLocation(), drop));
                    }
                }
            }
            if (moneyShown()) {
                live.setItem(moneySlot(), moneyItem(lang.languageOf(viewer), owner, ownerName, self));
            }
            open.put(owner, live);
        }
        viewer.openInventory(live);
        plugin.theme().play(viewer, "menu-open");
    }

    private Map<Integer, ItemStack> addToBackpack(Inventory inventory, ItemStack item) {
        Map<Integer, ItemStack> left = new HashMap<>();
        int capacity = capacity();
        for (int slot = 0; slot < capacity; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.isEmpty()) {
                inventory.setItem(slot, item);
                return left;
            }
        }
        left.put(0, item);
        return left;
    }

    /** De inhoud van een open rugtas, zonder de gouden staaf. */
    private ItemStack[] read(Inventory inventory) {
        ItemStack[] items = new ItemStack[capacity()];
        for (int slot = 0; slot < items.length && slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            items[slot] = item == null || item.isEmpty() ? null : item.clone();
        }
        return items;
    }

    // ============================================================ de gouden staaf

    private ItemStack moneyItem(String code, UUID owner, String ownerName, boolean self) {
        EconomyService economy = economy();
        Account account = economy == null ? null : economy.cached(owner);
        String amount = account == null ? "?" : economy.format(account.cash());
        Material material = Material.matchMaterial(config().getString("money.item", "GOLD_INGOT"));
        LanguageManager lang = plugin.lang();
        ItemStack item = ItemBuilder.of(material == null || !material.isItem() ? Material.GOLD_INGOT : material)
                .name(lang.component(code, "backpack.money.name", Text.p("amount", amount)))
                .lore(lang.components(code, self ? "backpack.money.lore" : "backpack.money.lore-other", Text.p("player", ownerName)))
                .build();
        item.editMeta(meta -> meta.getPersistentDataContainer().set(moneyKey, PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    private ItemStack lootMoneyItem(String code, long cents) {
        EconomyService economy = economy();
        LanguageManager lang = plugin.lang();
        if (economy == null || cents <= 0) {
            return ItemBuilder.of(Material.GRAY_DYE).name(lang.component(code, "backpack.loot-money.empty")).build();
        }
        ItemStack item = ItemBuilder.of(Material.GOLD_INGOT)
                .name(lang.component(code, "backpack.loot-money.name", Text.p("amount", economy.format(cents))))
                .lore(lang.components(code, "backpack.loot-money.lore"))
                .glint(true)
                .build();
        item.editMeta(meta -> meta.getPersistentDataContainer().set(moneyKey, PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    /** Elke seconde: het geld in open rugtassen bijwerken en de gevallen rugtassen bijhouden. */
    private void tick() {
        if (moneyShown()) {
            for (Map.Entry<UUID, Inventory> entry : open.entrySet()) {
                Inventory inventory = entry.getValue();
                if (inventory.getViewers().isEmpty() || !(inventory.getHolder(false) instanceof BackpackHolder holder)) {
                    continue;
                }
                HumanEntity viewer = inventory.getViewers().get(0);
                ItemStack fresh = moneyItem(plugin.lang().languageOf(viewer), holder.owner, holder.ownerName,
                        viewer.getUniqueId().equals(holder.owner));
                if (!fresh.equals(inventory.getItem(moneySlot()))) {
                    inventory.setItem(moneySlot(), fresh);
                }
            }
        }
        long now = System.currentTimeMillis();
        for (DroppedBackpack bag : new ArrayList<>(dropped.values())) {
            if (now >= bag.expiresAt) {
                poof(bag); // niemand geweest: alles is weg
                continue;
            }
            // Was de chunk even niet geladen? Dan zijn de zwevende rugtas en de naam weg: opnieuw neerzetten
            World world = bag.location.getWorld();
            if (bag.needsSpawn() && world != null
                    && world.isChunkLoaded(bag.location.getBlockX() >> 4, bag.location.getBlockZ() >> 4)) {
                dropped.remove(bag.hitboxId());
                bag.spawn(icon());
                dropped.put(bag.hitboxId(), bag);
            }
            bag.tick(label(bag, now));
        }
    }

    // ============================================================ klikken in een rugtas

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder(false);
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (holder instanceof BackpackHolder bag) {
            clickOwn(event, viewer, bag, top);
        } else if (holder instanceof DroppedBackpack bag) {
            clickDropped(event, viewer, bag, top);
        }
    }

    private void clickOwn(InventoryClickEvent event, Player viewer, BackpackHolder bag, Inventory top) {
        int raw = event.getRawSlot();
        boolean inTop = raw >= 0 && raw < top.getSize();
        boolean self = viewer.getUniqueId().equals(bag.owner);
        if (inTop && raw == moneySlot()) {
            event.setCancelled(true); // de gouden staaf zit vast
            if (self && viewer.hasPermission(EconomyModule.BANK)) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    viewer.closeInventory();
                    viewer.performCommand("bank");
                });
            }
            return;
        }
        if (!self && !viewer.hasPermission(OTHERS_EDIT)
                && (inTop || event.isShiftClick() || event.getAction().name().equals("COLLECT_TO_CURSOR"))) {
            event.setCancelled(true);
            plugin.lang().send(viewer, "backpack.read-only");
        }
    }

    private void clickDropped(InventoryClickEvent event, Player viewer, DroppedBackpack bag, Inventory top) {
        int raw = event.getRawSlot();
        boolean inTop = raw >= 0 && raw < top.getSize();
        if (!inTop) {
            if (event.isShiftClick()) {
                event.setCancelled(true); // niets in een gevallen rugtas stoppen
            }
            return;
        }
        if (raw == moneySlot()) {
            event.setCancelled(true);
            takeMoney(viewer, bag, top);
            return;
        }
        // Alleen pakken, niet erin leggen
        switch (event.getAction().name()) {
            case "PICKUP_ALL", "PICKUP_HALF", "PICKUP_ONE", "PICKUP_SOME", "MOVE_TO_OTHER_INVENTORY",
                 "DROP_ALL_SLOT", "DROP_ONE_SLOT", "COLLECT_TO_CURSOR" -> {
                // prima
            }
            case "HOTBAR_SWAP" -> {
                int button = event.getHotbarButton();
                ItemStack target = button >= 0 ? viewer.getInventory().getItem(button) : viewer.getInventory().getItemInOffHand();
                if (target != null && !target.isEmpty()) {
                    event.setCancelled(true);
                }
            }
            default -> event.setCancelled(true);
        }
    }

    private void takeMoney(Player viewer, DroppedBackpack bag, Inventory top) {
        EconomyService economy = economy();
        if (economy == null || bag.cash <= 0) {
            return;
        }
        long amount = bag.cash;
        bag.cash = 0;
        economy.give(viewer.getUniqueId(), amount, false, "backpack-loot", "rugtas van " + bag.ownerName);
        top.setItem(moneySlot(), lootMoneyItem(plugin.lang().languageOf(viewer), 0));
        plugin.lang().send(viewer, "backpack.looted-money", Text.p("amount", economy.format(amount)), Text.p("player", bag.ownerName));
        plugin.theme().play(viewer, "success");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder(false);
        if (!(holder instanceof BackpackHolder) && !(holder instanceof DroppedBackpack)) {
            return;
        }
        boolean editable = holder instanceof BackpackHolder bag
                && (event.getWhoClicked().getUniqueId().equals(bag.owner) || event.getWhoClicked().hasPermission(OTHERS_EDIT));
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize() && (!editable || raw == moneySlot())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof BackpackHolder bag) {
            ItemStack[] items = read(inventory);
            boolean last = inventory.getViewers().size() <= 1;
            Player owner = plugin.getServer().getPlayer(bag.owner);
            if (owner != null || !last) {
                cache.put(bag.owner, items);
            } else {
                cache.remove(bag.owner);
            }
            if (last) {
                open.remove(bag.owner);
            }
            store.save(bag.owner, items).exceptionally(error -> {
                plugin.getLogger().log(Level.SEVERE, "Kon de rugtas van " + bag.ownerName + " niet opslaan", error);
                return null;
            });
        } else if (holder instanceof DroppedBackpack bag && !bag.removed) {
            // Gelooted en weer dicht: de rugtas gaat in rook op (wat er nog in zat, is weg)
            plugin.getServer().getScheduler().runTask(plugin, () -> poof(bag));
        }
    }

    // ============================================================ doodgaan

    /** Vangt het contante geld op dat iemand verliest (aangeroepen door de economy). */
    private boolean catchCash(PlayerDeathEvent event, Long lost) {
        if (!dropsOnDeath(event)) {
            return false;
        }
        pendingCash.merge(event.getEntity().getUniqueId(), lost, Long::sum);
        return true;
    }

    private boolean dropsOnDeath(PlayerDeathEvent event) {
        if (!config().getBoolean("death.drop", true)) {
            return false;
        }
        return !(event.getKeepInventory() && config().getBoolean("death.honor-keep-inventory", true));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        long cash = pendingCash.getOrDefault(uuid, 0L);
        pendingCash.remove(uuid);
        if (!dropsOnDeath(event)) {
            return;
        }
        ItemStack[] items = takeContents(uuid);
        boolean any = Arrays.stream(items).anyMatch(item -> item != null && !item.isEmpty());
        if (!any && cash <= 0) {
            return;
        }
        Location spot = spot(player.getLocation());
        EconomyService economy = economy();
        String amount = economy == null ? "" : economy.format(cash);
        if (spot == null) {
            plugin.lang().send(player, "backpack.lost", Text.p("amount", amount));
            return;
        }
        long ownerOnly = Math.max(0, config().getLong("death.owner-only-seconds", 120)) * 1000L;
        long life = Math.max(1, config().getLong("death.despawn-minutes", 15)) * 60_000L;
        DroppedBackpack bag = new DroppedBackpack(uuid, player.getName(), spot, items, cash, ownerOnly, life);
        bag.spawn(icon());
        dropped.put(bag.hitboxId(), bag);
        bag.tick(label(bag, System.currentTimeMillis()));
        plugin.lang().send(player, cash > 0 ? "backpack.dropped-cash" : "backpack.dropped",
                Text.p("x", spot.getBlockX()), Text.p("y", spot.getBlockY()), Text.p("z", spot.getBlockZ()),
                Text.p("amount", amount), Text.p("minutes", Math.max(1, ownerOnly / 60_000L)),
                Text.p("despawn", life / 60_000L));
    }

    /** Haalt alles uit iemands rugtas (ook als die net open is) en geeft het terug. */
    private ItemStack[] takeContents(UUID uuid) {
        ItemStack[] items;
        Inventory live = open.get(uuid);
        if (live != null) {
            items = read(live);
            for (int slot = 0; slot < capacity(); slot++) {
                live.setItem(slot, null);
            }
        } else {
            items = cache.get(uuid);
            if (items == null) {
                try {
                    items = store.load(uuid).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    plugin.getLogger().log(Level.WARNING, "Kon de rugtas niet laden bij doodgaan", e);
                    return new ItemStack[0];
                }
            }
        }
        ItemStack[] empty = new ItemStack[capacity()];
        cache.put(uuid, empty);
        store.save(uuid, empty);
        return items == null ? new ItemStack[0] : items;
    }

    /** Een plek voor de gevallen rugtas: niet in een blok of in lava. Null in de leegte. */
    private static Location spot(Location location) {
        World world = location.getWorld();
        if (world == null || location.getY() < world.getMinHeight()) {
            return null;
        }
        Block block = world.getBlockAt(location.getBlockX(), Math.min(world.getMaxHeight() - 2, location.getBlockY()), location.getBlockZ());
        for (int up = 0; up < 12 && (block.isLiquid() || block.getType().isSolid()); up++) {
            block = block.getRelative(0, 1, 0);
        }
        return block.getLocation().add(0.5, 0, 0.5);
    }

    private Material icon() {
        Material icon = Material.matchMaterial(config().getString("death.icon", "BUNDLE"));
        return icon == null || !icon.isItem() ? Material.BUNDLE : icon;
    }

    private Component label(DroppedBackpack bag, long now) {
        LanguageManager lang = plugin.lang();
        String code = lang.defaultLanguage();
        long left = bag.ownerOnlyUntil - now;
        Component line = left > 0
                ? lang.component(code, "backpack.label-owner-only", Text.p("time", time(left)), Text.p("player", bag.ownerName))
                : lang.component(code, "backpack.label-hint", Text.p("time", time(bag.expiresAt - now)));
        return lang.component(code, "backpack.label", Text.p("player", bag.ownerName)).appendNewline().append(line);
    }

    private static String time(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        return seconds >= 60 ? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60) : seconds + "s";
    }

    // ============================================================ een gevallen rugtas openen

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        DroppedBackpack bag = dropped.get(event.getRightClicked().getUniqueId());
        if (bag == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || bag.removed) {
            return;
        }
        Player player = event.getPlayer();
        boolean owner = player.getUniqueId().equals(bag.owner);
        if (!owner) {
            if (!player.hasPermission(LOOT)) {
                plugin.lang().send(player, "general.no-permission");
                return;
            }
            long left = bag.ownerOnlyUntil - System.currentTimeMillis();
            if (left > 0) {
                plugin.lang().send(player, "backpack.owner-only", Text.p("time", time(left)), Text.p("player", bag.ownerName));
                plugin.theme().play(player, "error");
                return;
            }
        }
        if (bag.inventory == null) {
            String code = plugin.lang().languageOf(player);
            bag.inventory = plugin.getServer().createInventory(bag, rows() * 9,
                    plugin.lang().component(code, "backpack.title-dropped", Text.p("player", bag.ownerName)));
            int capacity = capacity();
            List<ItemStack> overflow = new ArrayList<>();
            for (int slot = 0; slot < bag.items.length; slot++) {
                ItemStack item = bag.items[slot];
                if (item == null || item.isEmpty()) {
                    continue;
                }
                if (slot < capacity) {
                    bag.inventory.setItem(slot, item);
                } else {
                    overflow.add(item);
                }
            }
            for (ItemStack item : overflow) {
                addToBackpack(bag.inventory, item);
            }
            if (moneyShown()) {
                bag.inventory.setItem(moneySlot(), lootMoneyItem(code, bag.cash));
            }
        }
        player.openInventory(bag.inventory);
        plugin.theme().play(player, "menu-open");
    }

    /** Laat de rugtas in rook opgaan. Wat er nog in zat, is weg. */
    private void poof(DroppedBackpack bag) {
        if (bag.removed) {
            return;
        }
        dropped.remove(bag.hitboxId());
        bag.despawn();
        Location location = bag.location.clone().add(0, 0.6, 0);
        World world = location.getWorld();
        if (world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            world.spawnParticle(Particle.LARGE_SMOKE, location, 25, 0.25, 0.35, 0.25, 0.02);
            world.spawnParticle(Particle.SMOKE, location, 20, 0.3, 0.3, 0.3, 0.03);
            world.playSound(location, "block.fire.extinguish", 0.7f, 1.3f);
        }
        if (bag.inventory != null) {
            for (HumanEntity viewer : new ArrayList<>(bag.inventory.getViewers())) {
                viewer.closeInventory();
            }
            bag.inventory.clear();
        }
    }

    /** Bij een herstart: de spullen en het geld op de grond leggen in plaats van ze kwijt te raken. */
    private void spill(DroppedBackpack bag) {
        World world = bag.location.getWorld();
        bag.despawn();
        if (world == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        if (bag.inventory != null) {
            for (int slot = 0; slot < capacity() && slot < bag.inventory.getSize(); slot++) {
                ItemStack item = bag.inventory.getItem(slot);
                if (item != null && !item.isEmpty()) {
                    items.add(item);
                }
            }
        } else {
            for (ItemStack item : bag.items) {
                if (item != null && !item.isEmpty()) {
                    items.add(item);
                }
            }
        }
        EconomyService economy = economy();
        if (economy != null && bag.cash > 0) {
            items.add(economy.moneyItem(bag.cash));
        }
        for (ItemStack item : items) {
            world.dropItemNaturally(bag.location, item);
        }
    }

    // ============================================================ in- en uitloggen

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            ItemStack[] items = store.load(event.getUniqueId()).get(10, TimeUnit.SECONDS);
            cache.put(event.getUniqueId(), items);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.WARNING, "Kon de rugtas van " + event.getName() + " niet vooraf laden", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingCash.remove(uuid);
        // Het sluiten (bij uitloggen) heeft de rugtas al opgeslagen; houd hem alleen vast als staff hem nog open heeft
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!open.containsKey(uuid) && plugin.getServer().getPlayer(uuid) == null) {
                cache.remove(uuid);
            }
        });
    }

    // ============================================================ voor het paneel

    /** De inhoud van iemands rugtas (online of offline), zonder de gouden staaf. Aanroepen op de hoofdthread. */
    public CompletableFuture<ItemStack[]> contents(UUID uuid) {
        Inventory live = open.get(uuid);
        if (live != null) {
            return CompletableFuture.completedFuture(read(live));
        }
        ItemStack[] cached = cache.get(uuid);
        if (cached != null) {
            ItemStack[] copy = new ItemStack[cached.length];
            for (int slot = 0; slot < cached.length; slot++) {
                copy[slot] = cached[slot] == null ? null : cached[slot].clone();
            }
            return CompletableFuture.completedFuture(copy);
        }
        return store.load(uuid);
    }

    /** Haalt één item uit iemands rugtas. Geeft het weggehaalde item terug (of null). Aanroepen op de hoofdthread. */
    public CompletableFuture<ItemStack> removeItem(UUID uuid, int slot) {
        if (slot < 0 || slot >= capacity() + 64) {
            return CompletableFuture.completedFuture(null);
        }
        Inventory live = open.get(uuid);
        if (live != null) {
            if (slot >= capacity()) {
                return CompletableFuture.completedFuture(null);
            }
            ItemStack item = live.getItem(slot);
            live.setItem(slot, null);
            store.save(uuid, read(live));
            return CompletableFuture.completedFuture(item);
        }
        ItemStack[] cached = cache.get(uuid);
        if (cached != null) {
            if (slot >= cached.length) {
                return CompletableFuture.completedFuture(null);
            }
            ItemStack item = cached[slot];
            cached[slot] = null;
            store.save(uuid, cached);
            return CompletableFuture.completedFuture(item);
        }
        return store.load(uuid).thenCompose(items -> {
            if (slot >= items.length || items[slot] == null) {
                return CompletableFuture.completedFuture(null);
            }
            ItemStack item = items[slot];
            items[slot] = null;
            return store.save(uuid, items).thenApply(ignored -> item);
        });
    }

    /** De gevallen rugtas van iemand (of null). */
    public DroppedInfo droppedOf(UUID owner) {
        for (DroppedBackpack bag : dropped.values()) {
            if (bag.owner.equals(owner) && !bag.removed) {
                int count = 0;
                for (ItemStack item : bag.inventory != null ? bag.inventory.getContents() : bag.items) {
                    if (item != null && !item.isEmpty() && (moneyKey == null || !item.hasItemMeta()
                            || !item.getItemMeta().getPersistentDataContainer().has(moneyKey))) {
                        count++;
                    }
                }
                return new DroppedInfo(bag.location.getWorld().getName(), bag.location.getBlockX(), bag.location.getBlockY(),
                        bag.location.getBlockZ(), bag.ownerOnlyUntil, bag.expiresAt, count, bag.cash);
            }
        }
        return null;
    }

    boolean isGameModeAllowed(GameMode mode) {
        List<String> modes = config().getStringList("allowed-gamemodes");
        return modes.isEmpty() || modes.stream().anyMatch(name -> name.equalsIgnoreCase(mode.name()));
    }
}
