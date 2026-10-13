package nl.pinda.framework.modules.duel;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.World;

/**
 * De arena als WorldGuard-regio ({@value #REGION}). Alleen gebruiken als WorldGuard aan staat:
 * deze klasse laadt de klassen van WorldGuard.
 *
 * <p>Wat er in de regio geldt: niemand bouwt of sloopt (geen leden; beheerders met de
 * bypass-permissie van WorldGuard wel), geen explosies, brand of monsters. PvP staat in de regio
 * aan, zodat het gevecht ook werkt als PvP elders via WorldGuard uit staat; PindaFramework laat
 * alleen het 1v1 zelf toe.
 */
final class WorldGuardHook {

    static final String REGION = "pinda_arena";

    private WorldGuardHook() {
    }

    private static RegionManager regions(World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    /**
     * Zet de regio op dit gebied (nieuw, of de bestaande verplaatst). Vlaggen of leden die een
     * beheerder zelf aan de regio gaf, blijven staan. False als WorldGuard deze wereld niet beschermt.
     */
    static boolean sync(World world, Area area) {
        RegionManager regions = regions(world);
        if (regions == null) {
            return false;
        }
        ProtectedRegion old = regions.getRegion(REGION);
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(REGION,
                BlockVector3.at(area.minX(), area.minY(), area.minZ()), BlockVector3.at(area.maxX(), area.maxY(), area.maxZ()));
        if (old != null) {
            region.copyFrom(old);
        } else {
            region.setPriority(10);
            deny(region, Flags.TNT, Flags.CREEPER_EXPLOSION, Flags.OTHER_EXPLOSION, Flags.GHAST_FIREBALL,
                    Flags.ENDERDRAGON_BLOCK_DAMAGE, Flags.WITHER_DAMAGE, Flags.BREEZE_WIND_CHARGE, Flags.WIND_CHARGE_BURST,
                    Flags.ENDER_BUILD, Flags.RAVAGER_RAVAGE, Flags.FIRE_SPREAD, Flags.LAVA_FIRE, Flags.LIGHTNING,
                    Flags.MOB_SPAWNING, Flags.ENTITY_PAINTING_DESTROY, Flags.ENTITY_ITEM_FRAME_DESTROY);
        }
        // Het gevecht moet kunnen, ook als PvP elders via WorldGuard uit staat.
        region.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        regions.addRegion(region);
        return true;
    }

    private static void deny(ProtectedRegion region, StateFlag... flags) {
        for (StateFlag flag : flags) {
            region.setFlag(flag, StateFlag.State.DENY);
        }
    }

    /** Haalt onze regio weg (uit deze wereld). */
    static void remove(World world) {
        RegionManager regions = regions(world);
        if (regions != null && regions.hasRegion(REGION)) {
            regions.removeRegion(REGION);
        }
    }

    /** Staat onze regio in deze wereld? */
    static boolean exists(World world) {
        RegionManager regions = regions(world);
        return regions != null && regions.hasRegion(REGION);
    }

    /** Wegschrijven (op de achtergrond aanroepen; WorldGuard doet dit anders zelf ook af en toe). */
    static void save(World world) throws Exception {
        RegionManager regions = regions(world);
        if (regions != null) {
            regions.saveChanges();
        }
    }
}
