package nl.pinda.framework.modules.homes;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Een home van een speler. De naam is altijd in kleine letters. */
public record Home(UUID owner, String name, String world, double x, double y, double z,
                   float yaw, float pitch, long created) {

    public static Home of(UUID owner, String name, Location location) {
        return new Home(owner, name, location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch(), System.currentTimeMillis());
    }

    /** De locatie, of null als de wereld niet (meer) bestaat. */
    public Location toLocation() {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return null;
        }
        return new Location(bukkitWorld, x, y, z, yaw, pitch);
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }
}
