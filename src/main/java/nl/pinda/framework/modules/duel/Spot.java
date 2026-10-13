package nl.pinda.framework.modules.duel;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** Een vaste plek: wereld, positie en kijkrichting. Los van of die wereld nu geladen is. */
public record Spot(String world, double x, double y, double z, float yaw, float pitch) {

    public static Spot of(Location location) {
        return new Spot(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    /** De plek in de wereld, of null als die wereld er (nu) niet is. */
    public Location location(Server server) {
        World loaded = server.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
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

    /** Bijv. "world 120, 64, -340". */
    public String describe() {
        return world + " " + blockX() + ", " + blockY() + ", " + blockZ();
    }

    // ============================================================ opslaan

    /** Uit een configsectie (world, x, y, z, yaw, pitch), of null als die er niet (goed) is. */
    public static Spot read(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        String world = section.getString("world", "");
        if (world.isBlank() || !section.isSet("x") || !section.isSet("y") || !section.isSet("z")) {
            return null;
        }
        return new Spot(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    public void write(ConfigurationSection section) {
        section.set("world", world);
        section.set("x", round(x));
        section.set("y", round(y));
        section.set("z", round(z));
        section.set("yaw", round(yaw));
        section.set("pitch", round(pitch));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Als één regel tekst voor de database: de wereldnaam mag zelf ook ';' bevatten. */
    public String serialize() {
        return String.format(Locale.ROOT, "%s;%.3f;%.3f;%.3f;%.2f;%.2f", world, x, y, z, yaw, pitch);
    }

    /** Terug uit {@link #serialize()}, of null als het geen geldige plek is. */
    public static Spot parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = new String[6];
        String rest = text;
        for (int index = 5; index >= 1; index--) {
            int split = rest.lastIndexOf(';');
            if (split < 0) {
                return null;
            }
            parts[index] = rest.substring(split + 1);
            rest = rest.substring(0, split);
        }
        parts[0] = rest;
        try {
            return new Spot(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
