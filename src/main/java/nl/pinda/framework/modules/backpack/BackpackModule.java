package nl.pinda.framework.modules.backpack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>Er gaat nooit iets verloren als de rugtas kleiner wordt ingesteld: een rugtas is altijd
 * groot genoeg voor wat erin zit.
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

    /** Hoe een rugtas wordt ingedeeld: hoe groot, waar de gouden staaf zit en wat waar ligt. */
    private record Layout(int size, int moneySlot, ItemStack[] slots, List<ItemStack> leftover) {
    }

    private BackpackStore store;
    private NamespacedKey moneyKey;
    private final Map<UUID, ItemStack[]> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Inventory> open = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingCash = new ConcurrentHashMap<>();
    private final Map<UUID, DroppedBackpack> dropped = new LinkedHashMap<>();
    /** Wanneer een rugtas voor het laatst is opgeslagen (oplopend nummer), tegen oude gegevens bij het inloggen. */
    private final Map<UUID, Long> savedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> preloadedAt = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private EconomyService hooked;
    private int ticks;

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

    /** Het vakje van de gouden staaf in een nieuwe rugtas (het laatste), of -1. */
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
        if (!isGameModeAllowed(player.getGameMode())) {
            plugin.lang().send(player, "backpack.not-allowed");
            return false;
        }
        return true;
    }

    /**
     * Deelt een rugtas in. Hij is minstens zo groot als ingesteld, maar groter als er meer in zit
     * (bijv. nadat de rugtas kleiner is ingesteld). Items houden hun vakje waar dat kan.
     */
    private Layout layout(ItemStack[] items, boolean money) {
        int highest = -1;
        int count = 0;
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot] != null && !items[slot].isEmpty()) {
                highest = slot;
                count++;
            }
        }
        int needed = Math.max(rows() * 9, Math.max(highest + 1, count) + (money ? 1 : 0));
        int size = Math.min(54, ((needed + 8) / 9) * 9);
        int moneySlot = money ? size - 1 : -1;
        ItemStack[] slots = new ItemStack[size];
        List<ItemStack> overflow = new ArrayList<>();
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (slot < size && slot != moneySlot) {
                slots[slot] = item;
            } else {
                overflow.add(item);
            }
        }
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack item : overflow) {
            int free = -1;
            for (int slot = 0; slot < size; slot++) {
                if (slot != moneySlot && slots[slot] == null) {
                    free = slot;
                    break;
                }
            }
            if (free >= 0) {
                slots[free] = item;
            } else if (moneySlot >= 0) {
                // Echt vol: dan deze keer geen gouden staaf, maar het item
                slots[moneySlot] = item;
                moneySlot = -1;
            } else {
                leftover.add(item);
            }
        }
        return new Layout(size, moneySlot, slots, leftover);
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
            showLive(viewer, owner, live);
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
            Inventory now = open.get(owner);
            if (now != null) {
                showLive(viewer, owner, now);
                return;
            }
            ItemStack[] current = cache.get(owner);
            show(viewer, owner, ownerName, current != null ? current : loaded);
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon de rugtas van " + ownerName + " niet laden", error);
            return null;
        });
    }

    private void showLive(Player viewer, UUID owner, Inventory live) {
        if (viewer.getOpenInventory().getTopInventory() == live) {
            return; // staat al open: niet opnieuw openen (dat zou hem eerst 'sluiten')
        }
        viewer.openInventory(live);
        open.put(owner, live);
        plugin.theme().play(viewer, "menu-open");
    }

    private void show(Player viewer, UUID owner, String ownerName, ItemStack[] items) {
        LanguageManager lang = plugin.lang();
        boolean self = viewer.getUniqueId().equals(owner);
        Layout layout = layout(items, moneyShown());
        BackpackHolder holder = new BackpackHolder(owner, ownerName, layout.moneySlot());
        Component title = self ? lang.component(viewer, "backpack.title")
                : lang.component(viewer, "backpack.title-other", Text.p("player", ownerName));
        Inventory live = plugin.getServer().createInventory(holder, layout.size(), title);
        holder.inventory(live);
        for (int slot = 0; slot < layout.size(); slot++) {
            if (layout.slots()[slot] != null) {
                live.setItem(slot, layout.slots()[slot]);
            }
        }
        if (layout.moneySlot() >= 0) {
            live.setItem(layout.moneySlot(), moneyItem(lang.languageOf(viewer), owner, ownerName, self));
        }
        if (!layout.leftover().isEmpty()) {
            // Kan eigenlijk niet (een rugtas is nooit groter dan 54 vakjes), maar nooit iets weggooien
            Player ownerPlayer = plugin.getServer().getPlayer(owner);
            Player target = ownerPlayer != null ? ownerPlayer : viewer;
            for (ItemStack item : layout.leftover()) {
                target.getInventory().addItem(item).values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
            }
            holder.dirty = true;
        }
        open.put(owner, live);
        viewer.openInventory(live);
        open.put(owner, live);
        plugin.theme().play(viewer, "menu-open");
    }

    /** De inhoud van een open rugtas, zonder de gouden staaf. */
    private static ItemStack[] read(Inventory inventory, int moneySlot) {
        ItemStack[] items = new ItemStack[inventory.getSize()];
        for (int slot = 0; slot < items.length; slot++) {
            if (slot == moneySlot) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            items[slot] = item == null || item.isEmpty() ? null : item.clone();
        }
        return items;
    }

    private static ItemStack[] read(Inventory inventory) {
        return read(inventory, inventory.getHolder(false) instanceof BackpackHolder holder ? holder.moneySlot : -1);
    }

    /** Slaat een rugtas op en onthoudt wanneer (tegen oude gegevens bij het inloggen). */
    private CompletableFuture<Void> persist(UUID uuid, ItemStack[] items) {
        savedAt.put(uuid, version.incrementAndGet());
        return store.save(uuid, items).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon de rugtas van " + uuid + " niet opslaan", error);
            return null;
        });
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

    /** Elke seconde: geld bijwerken, open rugtassen tussendoor opslaan en de gevallen rugtassen bijhouden. */
    private void tick() {
        for (Map.Entry<UUID, Inventory> entry : open.entrySet()) {
            Inventory inventory = entry.getValue();
            if (!(inventory.getHolder(false) instanceof BackpackHolder holder)) {
                continue;
            }
            if (holder.moneySlot >= 0 && !inventory.getViewers().isEmpty() && economy() != null) {
                HumanEntity viewer = inventory.getViewers().get(0);
                ItemStack fresh = moneyItem(plugin.lang().languageOf(viewer), holder.owner, holder.ownerName,
                        viewer.getUniqueId().equals(holder.owner));
                if (!fresh.equals(inventory.getItem(holder.moneySlot))) {
                    inventory.setItem(holder.moneySlot, fresh);
                }
            }
            if (holder.dirty) {
                // Tussendoor opslaan: zo gaat er bij een crash niets verloren of dubbel
                holder.dirty = false;
                ItemStack[] items = read(inventory, holder.moneySlot);
                cache.put(holder.owner, items);
                persist(holder.owner, items);
            }
        }
        long now = System.currentTimeMillis();
        for (DroppedBackpack bag : new ArrayList<>(dropped.values())) {
            try {
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
            } catch (RuntimeException e) {
                // Bijv. een wereld die er niet meer is: deze rugtas opgeven, de rest gaat gewoon door
                plugin.getLogger().log(Level.WARNING, "Gevallen rugtas van " + bag.ownerName + " opgeruimd", e);
                dropped.values().remove(bag);
                bag.despawn();
            }
        }
        if (++ticks % 60 == 0) {
            cleanup();
        }
    }

    /** Rugtassen van spelers die niet (meer) online zijn uit het geheugen halen. */
    private void cleanup() {
        long limit = System.currentTimeMillis() - 60_000L;
        for (UUID uuid : new ArrayList<>(cache.keySet())) {
            if (plugin.getServer().getPlayer(uuid) == null && !open.containsKey(uuid)
                    && preloadedAt.getOrDefault(uuid, 0L) < limit) {
                cache.remove(uuid);
                preloadedAt.remove(uuid);
            }
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
            bag.dirty = true;
        } else if (holder instanceof DroppedBackpack bag) {
            clickDropped(event, viewer, bag, top);
        }
    }

    private void clickOwn(InventoryClickEvent event, Player viewer, BackpackHolder bag, Inventory top) {
        int raw = event.getRawSlot();
        boolean inTop = raw >= 0 && raw < top.getSize();
        boolean self = viewer.getUniqueId().equals(bag.owner);
        if (inTop && raw == bag.moneySlot) {
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
        if (raw == bag.moneySlot) {
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
        top.setItem(bag.moneySlot, lootMoneyItem(plugin.lang().languageOf(viewer), 0));
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
        int moneySlot = holder instanceof BackpackHolder bag ? bag.moneySlot : ((DroppedBackpack) holder).moneySlot;
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize() && (!editable || raw == moneySlot)) {
                event.setCancelled(true);
                return;
            }
        }
        if (holder instanceof BackpackHolder bag) {
            bag.dirty = true;
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof BackpackHolder bag) {
            ItemStack[] items = read(inventory, bag.moneySlot);
            boolean last = inventory.getViewers().size() <= 1;
            Player owner = plugin.getServer().getPlayer(bag.owner);
            if (owner != null || !last) {
                cache.put(bag.owner, items);
            } else {
                cache.remove(bag.owner);
            }
            if (last) {
                open.remove(bag.owner, inventory); // alleen als het echt deze rugtas is
            }
            bag.dirty = false;
            persist(bag.owner, items);
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
        Long pending = pendingCash.remove(uuid);
        long cash = pending == null ? 0 : pending;
        if (event.isCancelled() || !dropsOnDeath(event)) {
            // Een andere plugin heeft de dood tegengehouden of keepInventory aangezet: geld terug
            refund(uuid, cash);
            return;
        }
        ItemStack[] items = takeContents(player);
        boolean any = false;
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                any = true;
                break;
            }
        }
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

    private void refund(UUID uuid, long cash) {
        EconomyService economy = economy();
        if (economy != null && cash > 0) {
            economy.give(uuid, cash, false, "refund", "rugtas: dood tegengehouden of keepInventory");
        }
    }

    /** Haalt alles uit iemands rugtas (ook als die net open is, bij hem of bij staff) en geeft het terug. */
    private ItemStack[] takeContents(Player player) {
        UUID uuid = player.getUniqueId();
        List<Inventory> live = new ArrayList<>();
        Inventory registered = open.get(uuid);
        if (registered != null) {
            live.add(registered);
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof BackpackHolder holder && holder.owner.equals(uuid) && !live.contains(top)) {
            live.add(top);
        }
        ItemStack[] items;
        if (!live.isEmpty()) {
            List<ItemStack> all = new ArrayList<>();
            for (Inventory inventory : live) {
                int moneySlot = inventory.getHolder(false) instanceof BackpackHolder holder ? holder.moneySlot : -1;
                for (ItemStack item : read(inventory, moneySlot)) {
                    if (item != null) {
                        all.add(item);
                    }
                }
                for (int slot = 0; slot < inventory.getSize(); slot++) {
                    if (slot != moneySlot) {
                        inventory.setItem(slot, null);
                    }
                }
                if (inventory.getHolder(false) instanceof BackpackHolder holder) {
                    holder.dirty = false;
                }
            }
            items = all.toArray(new ItemStack[0]);
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
        ItemStack[] empty = new ItemStack[0];
        cache.put(uuid, empty);
        persist(uuid, empty);
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
            EconomyService economy = economy();
            Layout layout = layout(bag.items, economy != null && (moneyShown() || bag.cash > 0));
            bag.inventory = plugin.getServer().createInventory(bag, layout.size(),
                    plugin.lang().component(code, "backpack.title-dropped", Text.p("player", bag.ownerName)));
            bag.moneySlot = layout.moneySlot();
            for (int slot = 0; slot < layout.size(); slot++) {
                if (layout.slots()[slot] != null) {
                    bag.inventory.setItem(slot, layout.slots()[slot]);
                }
            }
            if (bag.moneySlot >= 0) {
                bag.inventory.setItem(bag.moneySlot, lootMoneyItem(code, bag.cash));
            }
            // Wat er (in een uitzonderlijk geval) niet in past, en geld zonder vakje: gewoon op de grond
            World world = bag.location.getWorld();
            for (ItemStack item : layout.leftover()) {
                world.dropItemNaturally(bag.location, item);
            }
            if (bag.moneySlot < 0 && bag.cash > 0 && economy != null) {
                world.dropItemNaturally(bag.location, economy.moneyItem(bag.cash));
                bag.cash = 0;
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
        dropped.values().remove(bag);
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
        bag.despawn();
        List<ItemStack> items = new ArrayList<>();
        if (bag.inventory != null) {
            for (HumanEntity viewer : new ArrayList<>(bag.inventory.getViewers())) {
                viewer.closeInventory(); // niemand houdt een werkend venster over
            }
            for (int slot = 0; slot < bag.inventory.getSize(); slot++) {
                ItemStack item = bag.inventory.getItem(slot);
                if (slot != bag.moneySlot && item != null && !item.isEmpty()) {
                    items.add(item.clone());
                }
            }
            bag.inventory.clear();
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
            bag.cash = 0;
        }
        try {
            World world = bag.location.getWorld();
            if (world == null) {
                return;
            }
            world.getChunkAt(bag.location); // zorgen dat de chunk geladen is, zodat de items ook bewaard worden
            for (ItemStack item : items) {
                world.dropItemNaturally(bag.location, item);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Kon de gevallen rugtas van " + bag.ownerName + " niet leeggooien", e);
        }
    }

    // ============================================================ in- en uitloggen

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        long before = version.get();
        try {
            ItemStack[] items = store.load(uuid).get(10, TimeUnit.SECONDS);
            // Intussen opgeslagen (bijv. door staff) of nog open? Dan zijn deze gegevens al oud.
            if (!open.containsKey(uuid) && savedAt.getOrDefault(uuid, 0L) <= before) {
                cache.put(uuid, items);
                preloadedAt.put(uuid, System.currentTimeMillis());
            }
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
        long quit = System.currentTimeMillis();
        Long pending = pendingCash.remove(uuid);
        if (pending != null) {
            refund(uuid, pending);
        }
        // Het sluiten (bij uitloggen) heeft de rugtas al opgeslagen; houd hem alleen vast als staff hem nog open heeft
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!open.containsKey(uuid) && plugin.getServer().getPlayer(uuid) == null
                    && preloadedAt.getOrDefault(uuid, 0L) < quit) {
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
        if (slot < 0 || slot >= 54) {
            return CompletableFuture.completedFuture(null);
        }
        Inventory live = open.get(uuid);
        if (live != null) {
            int moneySlot = live.getHolder(false) instanceof BackpackHolder holder ? holder.moneySlot : -1;
            if (slot >= live.getSize() || slot == moneySlot) {
                return CompletableFuture.completedFuture(null);
            }
            ItemStack item = live.getItem(slot);
            live.setItem(slot, null);
            ItemStack[] items = read(live, moneySlot);
            cache.put(uuid, items);
            persist(uuid, items);
            return CompletableFuture.completedFuture(item);
        }
        ItemStack[] cached = cache.get(uuid);
        if (cached != null) {
            if (slot >= cached.length) {
                return CompletableFuture.completedFuture(null);
            }
            ItemStack item = cached[slot];
            cached[slot] = null;
            persist(uuid, cached);
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
                if (bag.inventory != null) {
                    for (int slot = 0; slot < bag.inventory.getSize(); slot++) {
                        ItemStack item = bag.inventory.getItem(slot);
                        if (slot != bag.moneySlot && item != null && !item.isEmpty()) {
                            count++;
                        }
                    }
                } else {
                    for (ItemStack item : bag.items) {
                        if (item != null && !item.isEmpty()) {
                            count++;
                        }
                    }
                }
                World world = bag.location.getWorld();
                return new DroppedInfo(world == null ? "?" : world.getName(), bag.location.getBlockX(), bag.location.getBlockY(),
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
