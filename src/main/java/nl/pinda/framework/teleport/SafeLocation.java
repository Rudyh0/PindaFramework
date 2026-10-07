package nl.pinda.framework.teleport;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/** Zoekt een plek waar een speler veilig kan staan: niet in een muur, lava of de leegte. */
public final class SafeLocation {

    private static final Set<Material> DANGEROUS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.MAGMA_BLOCK, Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.WITHER_ROSE, Material.NETHER_PORTAL, Material.END_PORTAL);

    private SafeLocation() {
    }

    /**
     * Geeft de locatie zelf terug als die veilig is, anders de dichtstbijzijnde veilige plek
     * recht erboven of eronder. Geeft null als er geen veilige plek is.
     */
    public static Location find(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        int x = origin.getBlockX();
        int z = origin.getBlockZ();
        int startY = origin.getBlockY();
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight() - 2;
        if (world.getEnvironment() == World.Environment.NETHER) {
            max = Math.min(max, 125); // niet op het dak van de Nether
        }

        if (startY >= min && startY <= max && isSafe(world, x, startY, z)) {
            return origin;
        }
        int distance = Math.max(max - startY, startY - min);
        for (int d = 1; d <= distance; d++) {
            int up = startY + d;
            if (up >= min && up <= max && isSafe(world, x, up, z)) {
                return centered(origin, up);
            }
            int down = startY - d;
            if (down >= min && down <= max && isSafe(world, x, down, z)) {
                return centered(origin, down);
            }
        }
        return null;
    }

    private static boolean isSafe(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (DANGEROUS.contains(feet.getType()) || DANGEROUS.contains(head.getType())
                || DANGEROUS.contains(ground.getType())) {
            return false;
        }
        Material below = ground.getType();
        return below.isSolid() || below == Material.WATER;
    }

    private static Location centered(Location origin, int y) {
        return new Location(origin.getWorld(), origin.getBlockX() + 0.5, y, origin.getBlockZ() + 0.5,
                origin.getYaw(), origin.getPitch());
    }
}
