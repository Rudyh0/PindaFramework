package nl.pinda.framework.modules.panel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Leest en schrijft de configbestanden in de pluginmap voor het paneel. Alleen bekende
 * bestanden (config.yml, teleport.yml, modules/*.yml en lang/*.yml) kunnen bewerkt worden.
 */
final class ConfigEditor {

    private static final Pattern ALLOWED = Pattern.compile("(config|teleport)\\.yml|modules/[a-z0-9-]+\\.yml|lang/[a-z0-9_-]+\\.yml");

    private final PindaFramework plugin;

    ConfigEditor(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /** De bestanden voor de instellingen-editor (zonder taalbestanden). */
    List<String> settingsFiles() {
        List<String> files = new ArrayList<>(List.of("config.yml", "teleport.yml"));
        File folder = new File(plugin.getDataFolder(), "modules");
        File[] found = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (found != null) {
            Arrays.sort(found);
            for (File file : found) {
                String path = "modules/" + file.getName();
                if (ALLOWED.matcher(path).matches()) {
                    files.add(path);
                }
            }
        }
        return files;
    }

    File file(String path) throws ApiException {
        if (path == null || !ALLOWED.matcher(path).matches()) {
            throw ApiException.badRequest("Dit bestand kan niet bewerkt worden.");
        }
        try {
            File root = plugin.getDataFolder().getCanonicalFile();
            File file = new File(root, path).getCanonicalFile();
            if (!file.toPath().startsWith(root.toPath())) {
                throw ApiException.badRequest("Ongeldig pad.");
            }
            if (!file.isFile()) {
                throw ApiException.notFound("Bestand " + path + " bestaat niet.");
            }
            return file;
        } catch (IOException e) {
            throw ApiException.badRequest("Ongeldig pad.");
        }
    }

    /** Leest een bestand vers van schijf, met de uitleg (commentaar) erbij. */
    YamlConfiguration read(String path) throws ApiException {
        File file = file(path);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw ApiException.badRequest("Het bestand " + path + " bevat een fout en kan niet gelezen worden: " + e.getMessage());
        }
        return yaml;
    }

    void write(String path, YamlConfiguration yaml) throws ApiException {
        try {
            yaml.save(file(path));
        } catch (IOException e) {
            throw new ApiException(500, "Kon " + path + " niet opslaan: " + e.getMessage());
        }
    }

    /** De module die bij een bestand hoort, of null. */
    PindaModule moduleFor(String path) {
        if (!path.startsWith("modules/")) {
            return null;
        }
        String id = path.substring("modules/".length(), path.length() - ".yml".length());
        for (PindaModule module : plugin.modules().all()) {
            if (module.id().equals(id)) {
                return module;
            }
        }
        return null;
    }

    /** Laat de plugin een bewerkt bestand opnieuw inlezen (hoofdthread). */
    void reload(String path) {
        if (path.startsWith("lang/")) {
            plugin.lang().load();
            return;
        }
        PindaModule module = moduleFor(path);
        if (module != null) {
            module.reloadModule();
            return;
        }
        plugin.reload();
    }
}
