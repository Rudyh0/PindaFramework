package nl.pinda.framework.modules.locks;

import org.bukkit.World;
import org.bukkit.block.Block;

/** Een blokpositie, bruikbaar als sleutel in een map. */
public record BlockKey(String world, int x, int y, int z) {

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public Block block(org.bukkit.Server server) {
        World bukkitWorld = server.getWorld(world);
        return bukkitWorld == null ? null : bukkitWorld.getBlockAt(x, y, z);
    }
}
