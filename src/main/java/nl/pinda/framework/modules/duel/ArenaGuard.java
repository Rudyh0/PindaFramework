package nl.pinda.framework.modules.duel;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Beschermt de arena als WorldGuard er niet is: niemand bouwt of sloopt er (behalve met
 * {@value DuelModule#BUILD}), geen explosies, brand, monsters, of water en lava die naar binnen
 * stromen. Met WorldGuard doet de regio {@value WorldGuardHook#REGION} dit, en doet deze klasse niets.
 */
final class ArenaGuard implements Listener {

    private static final long MESSAGE_GAP_MS = 2000;
    private static final Set<CreatureSpawnEvent.SpawnReason> MONSTER_REASONS = EnumSet.of(CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS, CreatureSpawnEvent.SpawnReason.PATROL, CreatureSpawnEvent.SpawnReason.RAID,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION, CreatureSpawnEvent.SpawnReason.TRAP, CreatureSpawnEvent.SpawnReason.JOCKEY);

    private final PindaFramework plugin;
    private final DuelModule module;
    private final Map<UUID, Long> told = new HashMap<>();

    ArenaGuard(PindaFramework plugin, DuelModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    /** Het gebied dat wij zelf beschermen (null = niets, of WorldGuard doet het). */
    private Area area() {
        return module.guardedByFramework();
    }

    private boolean inside(Block block) {
        Area area = area();
        return area != null && area.contains(block);
    }

    private boolean inside(Entity entity) {
        Area area = area();
        return area != null && area.contains(entity.getLocation());
    }

    /** Mag deze speler hier niet bouwen? Dan meteen de melding (niet te vaak). */
    private boolean denied(Player player, Block block) {
        if (!inside(block) || player.hasPermission(DuelModule.BUILD)) {
            return false;
        }
        tell(player);
        return true;
    }

    private boolean denied(Player player, Entity entity) {
        if (!inside(entity) || player.hasPermission(DuelModule.BUILD)) {
            return false;
        }
        tell(player);
        return true;
    }

    private void tell(Player player) {
        long now = System.currentTimeMillis();
        Long last = told.get(player.getUniqueId());
        if (last == null || now - last > MESSAGE_GAP_MS) {
            told.put(player.getUniqueId(), now);
            plugin.lang().send(player, "duel.protected");
        }
    }

    // ============================================================ bouwen en slopen

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (denied(event.getPlayer(), event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Deuren, kisten, knoppen, ... niet gebruiken; en geen akkers kapot springen. Items gebruiken mag gewoon. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !inside(block) || event.getPlayer().hasPermission(DuelModule.BUILD)) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            if (block.getType() == Material.FARMLAND || block.getType() == Material.TURTLE_EGG || block.getType() == Material.SNIFFER_EGG) {
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block.getType().isInteractable()) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    // ============================================================ schilderijen, lijsten, harnasstandaarden

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!inside(event.getEntity())) {
            return;
        }
        if (event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player player) {
            if (denied(player, event.getEntity())) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getCause() != HangingBreakEvent.RemoveCause.PHYSICS) {
            event.setCancelled(true); // explosies, pijlen, ...
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && denied(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if ((entity instanceof ItemFrame || entity instanceof ArmorStand) && denied(event.getPlayer(), entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (denied(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDecorationDamage(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Hanging || entity instanceof ArmorStand) || !inside(entity)) {
            return;
        }
        Entity damager = event.getDamager();
        Player player = damager instanceof Player direct ? direct
                : damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (player == null) {
            event.setCancelled(true); // explosies en mobs
        } else if (denied(player, entity)) {
            event.setCancelled(true);
        }
    }

    // ============================================================ explosies, brand, monsters, water

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Area area = area();
        if (area != null) {
            event.blockList().removeIf(area::contains);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Area area = area();
        if (area != null) {
            event.blockList().removeIf(area::contains);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!inside(event.getBlock())) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            event.setCancelled(true); // brand die overslaat, lava, bliksem
        } else if (denied(player, event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (inside(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Material type = event.getNewState().getType();
        if ((type == Material.FIRE || type == Material.SOUL_FIRE) && inside(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Enemy && MONSTER_REASONS.contains(event.getSpawnReason()) && inside(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Endermen, ravagers, withers, ... veranderen hier geen blokken (vallend zand wel gewoon). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock || !inside(event.getBlock())) {
            return;
        }
        if (entity instanceof Player player) {
            if (denied(player, event.getBlock())) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
    }

    /** Water en lava van buiten stromen de arena niet in. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Area area = area();
        if (area != null && area.contains(event.getToBlock()) && !area.contains(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crosses(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crosses(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    /** Duwt een zuiger van buiten blokken de arena in (of eruit)? */
    private boolean crosses(Block piston, List<Block> moved, BlockFace direction) {
        Area area = area();
        if (area == null || area.contains(piston)) {
            return false;
        }
        if (area.contains(piston.getRelative(direction))) {
            return true;
        }
        for (Block block : moved) {
            if (area.contains(block) || area.contains(block.getRelative(direction))) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        told.remove(event.getPlayer().getUniqueId());
    }
}
