package nl.pinda.framework.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Een YAML-bestand in de pluginmap dat automatisch wordt aangemaakt vanuit de jar.
 *
 * <p>Komen er in een nieuwe versie van de plugin instellingen of berichten bij, dan worden
 * die automatisch aan het bestaande bestand toegevoegd. Aanpassingen van de beheerder blijven staan.
 */
public final class ConfigFile {

    private final JavaPlugin plugin;
    private final String resourcePath;
    private final File file;
    private YamlConfiguration config = new YamlConfiguration();

    public ConfigFile(JavaPlugin plugin, String resourcePath) {
        this.plugin = plugin;
        this.resourcePath = resourcePath;
        this.file = new File(plugin.getDataFolder(), resourcePath);
        reload();
    }

    public void reload() {
        boolean bundled = hasBundledResource();
        if (!file.exists()) {
            if (bundled) {
                plugin.saveResource(resourcePath, false);
            } else {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    plugin.getLogger().warning("Kon map niet aanmaken: " + parent.getPath());
                }
            }
        }

        YamlConfiguration loaded = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();

        if (bundled) {
            YamlConfiguration defaults = loadBundledDefaults();
            if (defaults != null) {
                boolean changed = false;
                for (String key : defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(key) || loaded.isSet(key)) {
                        continue;
                    }
                    loaded.set(key, defaults.get(key));
                    changed = true;
                }
                loaded.setDefaults(defaults);
                config = loaded;
                if (changed) {
                    save();
                }
                return;
            }
        }
        config = loaded;
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

    private boolean hasBundledResource() {
        try (InputStream in = plugin.getResource(resourcePath)) {
            return in != null;
        } catch (IOException e) {
            return false;
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
}
