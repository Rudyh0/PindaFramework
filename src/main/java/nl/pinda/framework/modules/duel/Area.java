package nl.pinda.framework.modules.duel;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

/** Een blok-gebied (van hoek tot hoek, beide hoeken doen mee) in één wereld. */
public record Area(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** Van twee hoeken, in welke volgorde ook. */
    public static Area of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Area(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(Location location) {
        return location != null && location.getWorld() != null && location.getWorld().getName().equals(world)
                && contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean contains(Block block) {
        return block != null && block.getWorld().getName().equals(world) && contains(block.getX(), block.getY(), block.getZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Zelfde, maar alleen naar x en z gekeken (voor de grens tijdens een gevecht). */
    public boolean containsColumn(Location location) {
        return location != null && location.getWorld() != null && location.getWorld().getName().equals(world)
                && location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** Bijv. "world 80, -64, 180 tot 140, 319, 220 (61 x 61)". */
    public String describe() {
        return world + " " + minX + ", " + minY + ", " + minZ + " tot " + maxX + ", " + maxY + ", " + maxZ
                + " (" + sizeX() + " x " + sizeZ() + ")";
    }

    /** Een groter gebied: zoveel blokken extra naar alle kanten (niet in de hoogte). */
    public Area grow(int blocks) {
        return new Area(world, minX - blocks, minY, minZ - blocks, maxX + blocks, maxY, maxZ + blocks);
    }

    /** Hetzelfde gebied, met deze hoogte (bijv. de hele wereld). */
    public Area withHeight(int minHeight, int maxHeight) {
        return new Area(world, minX, minHeight, minZ, maxX, maxHeight, maxZ);
    }

    /** Uit een configsectie (world, min: [x, y, z], max: [x, y, z]), of null. */
    public static Area read(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        String world = section.getString("world", "");
        java.util.List<Integer> min = section.getIntegerList("min");
        java.util.List<Integer> max = section.getIntegerList("max");
        if (world.isBlank() || min.size() != 3 || max.size() != 3) {
            return null;
        }
        return of(world, min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2));
    }

    public void write(ConfigurationSection section) {
        section.set("world", world);
        section.set("min", java.util.List.of(minX, minY, minZ));
        section.set("max", java.util.List.of(maxX, maxY, maxZ));
    }
}
