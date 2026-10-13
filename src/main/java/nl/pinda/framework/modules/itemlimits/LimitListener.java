package nl.pinda.framework.modules.itemlimits;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Waar beperkte items binnenkomen (tegenhouden), verdwijnen (de eigenaar mag er weer een) en
 * heen gaan (waar ligt het nu).
 */
final class LimitListener implements Listener {

    private final PindaFramework plugin;
    private final ItemLimitModule module;

    LimitListener(PindaFramework plugin, ItemLimitModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    // ============================================================ cadeaus: /give en dergelijke

    /** Staff gebruikt /give (of een vergelijkbaar commando): wat er nu opduikt, is een cadeau. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (module.isGiftCommand(event.getMessage()) && (player.isOp() || player.hasPermission("minecraft.command.give")
                || player.hasPermission("essentials.give") || player.hasPermission("essentials.item"))) {
            module.giftCommand();
        }
    }

    /** Hetzelfde vanuit de console of RCON (bijv. het dev-paneel). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (module.isGiftCommand(event.getCommand())) {
            module.giftCommand();
        }
    }

    // ============================================================ binnenkomen: oppakken

    // Tegenhouden op HIGHEST: na bescherming (regio's, menu's van plugins) die het al tegenhield.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack();
        if (!(event.getEntity() instanceof Player player)) {
            // Een vos, allay of zombie pakt het op: het ligt nu bij dat dier.
            if (module.mayInvolve(stack)) {
                module.located(stack, "entity:" + event.getEntity().getType().name().toLowerCase(Locale.ROOT) + ":"
                        + ItemLimitModule.at(event.getEntity().getLocation()));
            }
            return;
        }
        if (!module.involves(stack)) {
            return;
        }
        if (module.bypass(player)) {
            module.claimSoon(player);
            return;
        }
        ItemLimitModule.Check check = module.check(player, stack);
        if (check.allowed()) {
            module.reserve(player, check);
            return;
        }
        event.setCancelled(true);
        if (check.partial() > 0) {
            // Een stapel: pak alleen wat nog mag (een tick later, buiten dit event om).
            int wanted = check.partial();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                int taken = takeFromGround(player, entity, check.rule(), wanted);
                module.explain(player, check, taken);
            });
            return;
        }
        module.explain(player, check, 0);
    }

    private int takeFromGround(Player player, Item entity, LimitRule rule, int wanted) {
        if (!player.isOnline() || !entity.isValid() || entity.isDead()) {
            return 0;
        }
        ItemStack stack = entity.getItemStack();
        if (!rule.equals(module.ruleFor(stack)) || module.tags().serial(stack) != null) {
            return 0;
        }
        int amount = Math.min(Math.min(wanted, stack.getAmount()), freeSlots(player.getInventory()));
        amount = Math.min(amount, module.room(player, rule, true));
        if (amount <= 0) {
            return 0;
        }
        List<ItemStack> units = module.claimNew(player, stack, rule, amount, ItemLimitModule.IN_INVENTORY);
        player.getInventory().addItem(units.toArray(ItemStack[]::new));
        player.playPickupItemAnimation(entity, amount);
        if (stack.getAmount() <= amount) {
            entity.remove();
        } else {
            stack.setAmount(stack.getAmount() - amount);
            entity.setItemStack(stack);
        }
        return amount;
    }

    private static int freeSlots(PlayerInventory inventory) {
        int free = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    // ============================================================ binnenkomen: uit een kist, rugtas, craften

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event instanceof InventoryCreativeEvent
                || event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            return; // een menu van het framework: daar gaat niets echt de inventory in
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        InventoryAction action = event.getAction();
        if (action == InventoryAction.COLLECT_TO_CURSOR && module.involves(event.getCursor()) && !module.bypass(player)) {
            // Dubbelklikken pakt ook uit kisten: niet voor beperkte items (één voor één is te tellen).
            event.setCancelled(true);
            return;
        }
        InventoryView view = event.getView();
        if (!clicked.equals(view.getTopInventory())) {
            return; // in de eigen inventory: daar komt niets bij
        }
        boolean result = event.getSlotType() == InventoryType.SlotType.RESULT;
        ItemStack incoming = switch (action) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, MOVE_TO_OTHER_INVENTORY, HOTBAR_SWAP,
                 SWAP_WITH_CURSOR, PICKUP_FROM_BUNDLE, PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE -> event.getCurrentItem();
            default -> null; // neerleggen, laten vallen (dan telt het oppakken), niets
        };
        if (result && action != InventoryAction.NOTHING) {
            watchInputs(player, view);
        }
        if (incoming == null || incoming.isEmpty() || !module.involves(incoming)) {
            return;
        }
        if (module.bypass(player)) {
            module.claimSoon(player);
            return;
        }
        LimitRule resultRule = result ? module.ruleFor(incoming) : null;
        if (resultRule != null && module.tags().serial(incoming) == null
                && (event.isShiftClick() || action == InventoryAction.MOVE_TO_OTHER_INVENTORY)) {
            // Shift-klik maakt er zoveel als kan: niet te tellen. Eén voor één gaat wel.
            event.setCancelled(true);
            module.craftOneByOne(player, resultRule);
            return;
        }
        ItemLimitModule.Check check = module.check(player, incoming);
        if (check.allowed()) {
            module.reserve(player, check);
            return;
        }
        event.setCancelled(true);
        boolean plainTake = switch (action) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, MOVE_TO_OTHER_INVENTORY -> true;
            default -> false;
        };
        if (!result && plainTake && check.partial() > 0) {
            int slot = event.getSlot();
            ItemStack snapshot = incoming.clone();
            int wanted = check.partial();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                int taken = takeFromSlot(player, clicked, slot, snapshot, check.rule(), wanted);
                module.explain(player, check, taken);
            });
            return;
        }
        module.explain(player, check, 0);
    }

    /** Haalt een deel van een stapel (zonder nummers) uit een kist: wat nog mag, met nummers. */
    private int takeFromSlot(Player player, Inventory inventory, int slot, ItemStack expected, LimitRule rule, int wanted) {
        if (!player.isOnline() || !player.getOpenInventory().getTopInventory().equals(inventory)) {
            return 0;
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || !stack.isSimilar(expected) || module.tags().serial(stack) != null) {
            return 0;
        }
        int amount = Math.min(Math.min(wanted, stack.getAmount()), freeSlots(player.getInventory()));
        amount = Math.min(amount, module.room(player, rule, true));
        if (amount <= 0) {
            return 0;
        }
        List<ItemStack> units = module.claimNew(player, stack, rule, amount, ItemLimitModule.IN_INVENTORY);
        if (stack.getAmount() <= amount) {
            inventory.setItem(slot, null);
        } else {
            stack.setAmount(stack.getAmount() - amount);
            inventory.setItem(slot, stack);
        }
        player.getInventory().addItem(units.toArray(ItemStack[]::new));
        return amount;
    }

    /**
     * Een resultaat pakken (craften, aambeeld, smeden, handelen) verbruikt de items in de andere
     * vakken. Beperkte items die daarna nergens meer zijn, zijn verwerkt.
     */
    private void watchInputs(Player player, InventoryView view) {
        Inventory top = view.getTopInventory();
        Set<String> serials = new HashSet<>();
        for (int raw = 0; raw < top.getSize(); raw++) {
            if (view.getSlotType(raw) == InventoryType.SlotType.RESULT) {
                continue;
            }
            LimitTags.forEach(top.getItem(raw), leaf -> {
                String serial = module.tags().serial(leaf);
                if (serial != null) {
                    serials.add(serial);
                }
            });
        }
        if (serials.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Set<String> present = new HashSet<>();
            collect(top.getContents(), present);
            collect(player.getInventory().getContents(), present);
            collect(new ItemStack[] {player.getItemOnCursor()}, present);
            for (String serial : serials) {
                if (!present.contains(serial)) {
                    module.consumed(serial, "verwerkt (craften, aambeeld of handel)");
                }
            }
        });
    }

    private void collect(ItemStack[] items, Set<String> into) {
        for (ItemStack item : items) {
            LimitTags.forEach(item, leaf -> {
                String serial = module.tags().serial(leaf);
                if (serial != null) {
                    into.add(serial);
                }
            });
        }
    }

    // ============================================================ binnenkomen: harnasstandaard, dispenser

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        ItemStack taking = event.getArmorStandItem();
        if (!module.involves(taking)) {
            return;
        }
        if (module.bypass(player)) {
            module.claimSoon(player);
            return;
        }
        ItemLimitModule.Check check = module.check(player, taking);
        if (check.allowed()) {
            module.reserve(player, check);
        } else {
            event.setCancelled(true);
            module.explain(player, check, 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStandPlaced(PlayerArmorStandManipulateEvent event) {
        module.located(event.getPlayerItem(), "stand:" + ItemLimitModule.at(event.getRightClicked().getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispenseArmor(BlockDispenseArmorEvent event) {
        if (!(event.getTargetEntity() instanceof Player player) || !module.involves(event.getItem())) {
            return;
        }
        if (module.bypass(player)) {
            module.claimSoon(player);
            return;
        }
        ItemLimitModule.Check check = module.check(player, event.getItem());
        if (check.allowed()) {
            module.reserve(player, check);
        } else {
            event.setCancelled(true);
            module.explain(player, check, 0);
        }
    }

    /** Een getrokken trident (of pijl) oppakken: gaat niet via het gewone oppakken. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getArrow().getItemStack();
        if (!module.involves(stack)) {
            return;
        }
        if (module.bypass(player)) {
            module.claimSoon(player);
            return;
        }
        ItemLimitModule.Check check = module.check(player, stack);
        if (check.allowed()) {
            module.reserve(player, check);
        } else {
            event.setCancelled(true);
            module.explain(player, check, 0);
        }
    }

    /** Een boek uit een boekenplank (chiseled bookshelf) halen gaat rechtstreeks de inventory in. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBookshelf(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || block.getType() != Material.CHISELED_BOOKSHELF
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Location point = event.getInteractionPoint();
        if (point == null || !(block.getState(false) instanceof ChiseledBookshelf shelf)) {
            return;
        }
        int slot = shelf.getSlot(point.toVector().subtract(block.getLocation().toVector()));
        ItemStack book = slot < 0 ? null : shelf.getInventory().getItem(slot);
        Player player = event.getPlayer();
        if (book == null || book.isEmpty() || !module.involves(book)) {
            return; // erin zetten, of geen beperkt boek
        }
        if (module.bypass(player)) {
            module.claimSoon(player);
            return;
        }
        ItemLimitModule.Check check = module.check(player, book);
        if (check.allowed()) {
            module.reserve(player, check);
        } else {
            event.setUseInteractedBlock(Event.Result.DENY);
            module.explain(player, check, 0);
        }
    }

    /** Uit een boekenkast of pot gehaald: gaat buiten de inventory-events om. Even nakijken. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null
                && (block.getType() == Material.CHISELED_BOOKSHELF || block.getType() == Material.DECORATED_POT)) {
            module.claimSoon(event.getPlayer());
        }
    }

    // ============================================================ verdwijnen

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        EquipmentSlot hand = event.getHand();
        EntityEquipment equipment = event.getEntity().getEquipment();
        if (hand != null && equipment != null) {
            module.used(equipment.getItem(hand), "opgebruikt", true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        module.used(event.getItem(), "opgegeten", true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent event) {
        module.used(event.getBrokenItem(), "kapot", true);
    }

    /** Een geplaatst blok telt niet meer mee (het is in gebruik); wat erin zit, ligt nu daar. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!module.mayInvolve(item)) {
            return;
        }
        Block block = event.getBlockPlaced();
        module.locatedInside(item, "container:" + block.getType().name().toLowerCase(Locale.ROOT) + ":"
                + ItemLimitModule.at(block.getLocation()));
        module.used(item, "geplaatst als blok", false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        ItemStack stack;
        if (entity instanceof Item item) {
            stack = item.getItemStack();
        } else if (entity instanceof AbstractArrow arrow) {
            stack = arrow.getItemStack(); // een trident
        } else {
            return;
        }
        if (!module.mayInvolve(stack)) {
            return;
        }
        switch (event.getCause()) {
            // Verbrand, ontploft, cactus: wat erin zat (shulkerdoos) valt eruit en telt apart.
            case DEATH -> module.used(stack, "verbrand of kapot", true);
            case DESPAWN -> module.destroyed(stack, "lag te lang op de grond");
            case OUT_OF_WORLD -> module.destroyed(stack, "in de void gevallen");
            case PLUGIN, DISCARD -> module.destroyed(stack, "opgeruimd");
            default -> {
                // opgepakt, chunk ontladen, samengevoegd, ...: bestaat nog
            }
        }
    }

    // ============================================================ waar ligt het nu

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (module.mayInvolve(stack)) {
            module.located(stack, "ground:" + ItemLimitModule.at(event.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        module.located(item.getItemStack(), "ground:" + ItemLimitModule.at(item.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        if (module.limitedType(item.getType())) {
            module.located(item, module.where(event.getDestination(), null));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (module.mayInvolve(item)) {
            module.located(item, module.where(event.getInventory(), null));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame) || !frame.getItem().isEmpty()) {
            return;
        }
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (module.mayInvolve(hand)) {
            module.located(hand, "frame:" + ItemLimitModule.at(frame.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        String where = module.where(top, player);
        if (where != null && !ItemLimitModule.IN_INVENTORY.equals(where)) {
            for (ItemStack item : top.getContents()) {
                module.located(item, where);
            }
        }
        module.claimSoon(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        module.joined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        module.quit(event.getPlayer().getUniqueId());
    }
}
