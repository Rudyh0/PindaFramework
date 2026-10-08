package nl.pinda.framework.modules.world;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;

/**
 * Tegen gesloop: explosies zonder blokschade (creepers, TNT, ...), endermen die geen blokken
 * meer oppakken en akkers die niet kapot gelopen worden.
 */
final class GriefGuard implements Listener {

    /** De instellingen, in één keer ingelezen zodat de events niet steeds de config hoeven te lezen. */
    record Settings(Set<ExplosionSource> noDamage, boolean tntChain, boolean protectEntities, Set<String> damageWorlds,
                    boolean endermanPickup, boolean trampleFarmland) {
    }

    private final WorldControlModule module;
    private volatile Settings settings;

    GriefGuard(WorldControlModule module) {
        this.module = module;
        reload();
    }

    void reload() {
        YamlConfiguration cfg = module.settings();
        Set<ExplosionSource> noDamage = EnumSet.noneOf(ExplosionSource.class);
        ConfigurationSection damage = cfg.getConfigurationSection("explosions.block-damage");
        for (ExplosionSource source : ExplosionSource.values()) {
            boolean allowed = damage == null ? source.defaultDamage() : damage.getBoolean(source.id(), source.defaultDamage());
            if (!allowed) {
                noDamage.add(source);
            }
        }
        Set<String> worlds = new HashSet<>();
        for (String name : cfg.getStringList("explosions.worlds-with-damage")) {
            worlds.add(name.trim().toLowerCase(Locale.ROOT));
        }
        settings = new Settings(Set.copyOf(noDamage), cfg.getBoolean("explosions.tnt-chain", true),
                cfg.getBoolean("explosions.protect-entities", true), Set.copyOf(worlds),
                cfg.getBoolean("griefing.enderman-pickup", false), cfg.getBoolean("griefing.trample-farmland", true));
    }

    /** Mag deze explosie in deze wereld géén blokken kapotmaken? */
    private boolean blocked(ExplosionSource source, World world) {
        Settings current = settings;
        return source != null && current.noDamage().contains(source)
                && !current.damageWorlds().contains(world.getName().toLowerCase(Locale.ROOT));
    }

    // ============================================================ explosies

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (blocked(ExplosionSource.of(event.getEntity()), event.getEntity().getWorld())) {
            strip(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (blocked(ExplosionSource.of(event.getExplodedBlockState()), event.getBlock().getWorld())) {
            strip(event.blockList());
        }
    }

    /** Haalt alle blokken uit de explosie. TNT mag (als dat aan staat) nog wel afgaan. */
    private void strip(List<Block> blocks) {
        if (settings.tntChain()) {
            blocks.removeIf(block -> block.getType() != Material.TNT);
        } else {
            blocks.clear();
        }
    }

    /** Itemframes, schilderijen, harnasstandaarden en items op de grond. */
    private static boolean decoration(Entity entity) {
        return entity instanceof Hanging || entity instanceof ArmorStand || entity instanceof Item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return;
        }
        if (!settings.protectEntities() || !decoration(event.getEntity())) {
            return;
        }
        ExplosionSource source = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            source = ExplosionSource.of(byEntity.getDamager());
        } else if (event instanceof EntityDamageByBlockEvent byBlock) {
            source = ExplosionSource.of(byBlock.getDamagerBlockState());
        }
        if (blocked(source, event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event.getCause() != HangingBreakEvent.RemoveCause.EXPLOSION || !settings.protectEntities()) {
            return;
        }
        World world = event.getEntity().getWorld();
        if (event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() != null) {
            if (blocked(ExplosionSource.of(byEntity.getRemover()), world)) {
                event.setCancelled(true);
            }
            return;
        }
        // Zonder entity was het een bed of een respawn anchor; welke van de twee is hier niet te zien.
        if (blocked(ExplosionSource.BED, world) || blocked(ExplosionSource.RESPAWN_ANCHOR, world)) {
            event.setCancelled(true);
        }
    }

    // ============================================================ endermen en akkers

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        Settings current = settings;
        if (!current.endermanPickup() && event.getEntity() instanceof Enderman && event.getTo().isAir()) {
            event.setCancelled(true);
            return;
        }
        // Een gewonde wither slaat ook blokken om zich heen kapot (zonder explosie).
        if (event.getEntity() instanceof Wither && event.getTo().isAir() && blocked(ExplosionSource.WITHER, event.getBlock().getWorld())) {
            event.setCancelled(true);
            return;
        }
        if (!current.trampleFarmland() && event.getBlock().getType() == Material.FARMLAND && event.getTo() == Material.DIRT) {
            event.setCancelled(true);
        }
    }
}
