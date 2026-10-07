package nl.pinda.framework.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Een YAML-bestand in de pluginmap dat automatisch wordt aangemaakt vanuit de jar.
 *
 * <p>Bij een update van de plugin:
 * <ul>
 *   <li>nieuwe instellingen en berichten worden toegevoegd;</li>
 *   <li>teksten die de beheerder nooit heeft aangepast, krijgen de nieuwe standaardtekst;</li>
 *   <li>teksten die de beheerder wel heeft aangepast, blijven staan.</li>
 * </ul>
 * Om dat verschil te zien, bewaart het framework een kopie van de vorige standaardbestanden
 * in de verborgen map {@code .defaults/}.
 */
public final class ConfigFile {

    private static final String SNAPSHOT_FOLDER = ".defaults";

    private final JavaPlugin plugin;
    private final String resourcePath;
    private final File file;
    private final File snapshot;
    private YamlConfiguration config = new YamlConfiguration();

    public ConfigFile(JavaPlugin plugin, String resourcePath) {
        this.plugin = plugin;
        this.resourcePath = resourcePath;
        this.file = new File(plugin.getDataFolder(), resourcePath);
        this.snapshot = new File(new File(plugin.getDataFolder(), SNAPSHOT_FOLDER), resourcePath);
        reload();
    }

    public void reload() {
        YamlConfiguration defaults = loadBundledDefaults();
        if (!file.exists()) {
            if (defaults != null) {
                plugin.saveResource(resourcePath, false);
            } else {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    plugin.getLogger().warning("Kon map niet aanmaken: " + parent.getPath());
                }
            }
        }

        YamlConfiguration loaded = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        if (defaults == null) {
            config = loaded;
            return;
        }

        YamlConfiguration previous = snapshot.exists() ? YamlConfiguration.loadConfiguration(snapshot) : null;
        int added = 0;
        int updated = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            Object newDefault = defaults.get(key);
            if (!loaded.isSet(key)) {
                loaded.set(key, newDefault);
                added++;
                continue;
            }
            if (previous != null && previous.isSet(key)) {
                Object current = loaded.get(key);
                Object oldDefault = previous.get(key);
                if (Objects.equals(current, oldDefault) && !Objects.equals(current, newDefault)) {
                    loaded.set(key, newDefault);
                    updated++;
                }
            }
        }
        loaded.setDefaults(defaults);
        config = loaded;

        if (added > 0 || updated > 0) {
            save();
            plugin.getLogger().info(resourcePath + " bijgewerkt: " + added + " nieuw, " + updated + " vernieuwd.");
        }
        writeSnapshot();
    }

    public YamlConfiguration get() {
        return config;
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Kon " + resourcePath + " niet opslaan", e);
        }
    }

    private YamlConfiguration loadBundledDefaults() {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Kon standaardwaarden van " + resourcePath + " niet lezen", e);
            return null;
        }
    }

    /** Bewaart de huidige standaardversie, zodat we bij de volgende update het verschil zien. */
    private void writeSnapshot() {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return;
            }
            File parent = snapshot.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            Files.copy(in, snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Kon kopie van de standaard " + resourcePath + " niet opslaan", e);
        }
    }
}
