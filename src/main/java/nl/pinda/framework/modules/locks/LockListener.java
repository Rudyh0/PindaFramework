package nl.pinda.framework.modules.locks;

import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Zet blokken op slot bij het plaatsen, controleert toegang en beschermt afgesloten blokken. */
public final class LockListener implements Listener {

    private final PindaFramework plugin;
    private final LockModule module;
    private final LockService service;

    public LockListener(PindaFramework plugin, LockModule module, LockService service) {
        this.plugin = plugin;
        this.module = module;
        this.service = service;
    }

    // ------------------------------------------------------------------ plaatsen

    /** Geen dubbele kist maken met de kist van iemand anders. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCheck(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!service.isLockable(block.getType())) {
            return;
        }
        Block other = LockService.otherChestHalf(block);
        if (other == null) {
            return;
        }
        Lock otherLock = service.find(other);
        if (otherLock != null && !service.canAccess(event.getPlayer(), otherLock)) {
            event.setCancelled(true);
            plugin.lang().send(event.getPlayer(), "lock.no-double-chest", Text.p("player", name(otherLock.owner())));
            plugin.theme().play(event.getPlayer(), "error");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();
        if (block.getType() == Material.HOPPER) {
            service.setHopperOwner(block, player.getUniqueId());
            return;
        }
        if (!service.isLockable(block.getType())) {
            return;
        }
        UUID owner = player.getUniqueId();
        Block other = LockService.otherChestHalf(block);
        if (other != null) {
            Lock otherLock = service.find(other);
            if (otherLock == null) {
                // Naast een openbare kist (bijv. van de wereld zelf): de dubbele kist blijft openbaar.
                return;
            }
            owner = otherLock.owner();
            Lock lock = service.create(block, owner);
            for (UUID trusted : otherLock.trusted()) {
                service.trust(block, trusted, true);
            }
            if (otherLock.everyone()) {
                service.setEveryone(block, true);
            }
            if (lock != null && module.cfg().getBoolean("notify-on-place", true)) {
                plugin.lang().send(player, "lock.locked");
            }
            return;
        }
        service.create(block, owner);
        if (module.cfg().getBoolean("notify-on-place", true)) {
            plugin.lang().send(player, "lock.locked");
        }
    }

    // ---------------------------------------------------------------- gebruiken

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Lock lock = service.find(block);
        if (lock == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean mainHand = event.getHand() == EquipmentSlot.HAND;
        ItemStack item = event.getItem();
        boolean emptyHand = item == null || item.isEmpty();

        // Shift + rechtsklik met lege hand: toegangsmenu (eigenaar of staff)
        if (player.isSneaking() && emptyHand
                && (lock.owner().equals(player.getUniqueId()) || player.hasPermission(LockService.BYPASS))) {
            event.setCancelled(true);
            if (mainHand) {
                new LockMenu(plugin, player, service, block).open();
                plugin.theme().play(player, "menu-open");
            }
            return;
        }

        if (service.canAccess(player, lock)) {
            if (mainHand && !service.canAccess(player.getUniqueId(), lock)) {
                plugin.lang().send(player, "lock.bypass", Text.p("player", name(lock.owner())));
            }
            return;
        }
        event.setCancelled(true);
        if (mainHand) {
            plugin.lang().send(player, "lock.denied", Text.p("player", name(lock.owner())));
            plugin.theme().play(player, "error");
        }
    }

    // ------------------------------------------------------------------ breken

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (block.getType() == Material.HOPPER) {
            service.removeHopper(block);
            return;
        }
        Lock lock = service.find(block);
        if (lock != null) {
            if (!service.canBreak(player, lock)) {
                event.setCancelled(true);
                plugin.lang().send(player, "lock.break-denied", Text.p("player", name(lock.owner())));
                plugin.theme().play(player, "error");
                return;
            }
            service.remove(block);
            return;
        }
        // Het blok onder een afgesloten deur
        Block above = block.getRelative(BlockFace.UP);
        if (LockService.kindOf(above.getType()) == LockService.Kind.DOOR) {
            Lock doorLock = service.find(above);
            if (doorLock != null) {
                if (!service.canBreak(player, doorLock)) {
                    event.setCancelled(true);
                    plugin.lang().send(player, "lock.break-denied", Text.p("player", name(doorLock.owner())));
                    plugin.theme().play(player, "error");
                } else {
                    service.remove(above);
                }
            }
        }
    }

    // --------------------------------------------------------------- bescherming

    private boolean protect() {
        return module.cfg().getBoolean("protect-blocks", true);
    }

    private boolean isProtected(Block block) {
        if (service.find(block) != null) {
            return true;
        }
        Block above = block.getRelative(BlockFace.UP);
        return LockService.kindOf(above.getType()) == LockService.Kind.DOOR && service.find(above) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (protect()) {
            event.blockList().removeIf(this::isProtected);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (protect()) {
            event.blockList().removeIf(this::isProtected);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (protect() && event.getBlocks().stream().anyMatch(this::isProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (protect() && event.getBlocks().stream().anyMatch(this::isProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protect() && isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDoorBreak(EntityBreakDoorEvent event) {
        if (protect() && service.find(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (protect() && isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Hoppers mogen een afgesloten kist alleen leegtrekken als ze van iemand met toegang zijn. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (event.getInitiator() != event.getDestination() || !module.cfg().getBoolean("block-hoppers", true)) {
            return;
        }
        org.bukkit.Location location = event.getSource().getLocation();
        if (location == null) {
            return;
        }
        Lock lock = service.find(location.getBlock());
        if (lock == null) {
            return;
        }
        InventoryHolder holder = event.getDestination().getHolder(false);
        if (holder instanceof org.bukkit.block.Hopper hopper) {
            UUID owner = service.hopperOwner(hopper.getBlock());
            if (owner != null && service.canAccess(owner, lock)) {
                return;
            }
        }
        event.setCancelled(true);
    }

    private String name(UUID uuid) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : "?";
    }
}
