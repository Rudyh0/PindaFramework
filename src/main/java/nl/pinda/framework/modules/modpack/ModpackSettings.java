package nl.pinda.framework.modules.modpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * De instellingen uit modules/modpack.yml.
 *
 * @param mode     uit, een melding (soft) of tegenhouden (hard)
 * @param url      de link naar de modpack (bijv. op Modrinth), voor in de meldingen
 * @param required de mods die je moet hebben (mod-ID → naam in meldingen)
 * @param exempt   spelers die altijd mogen spelen (UUID → naam)
 */
public record ModpackSettings(Mode mode, String url, List<Required> required, Map<UUID, String> exempt,
                              int timeoutSeconds, int remindMinutes, String softMessage, String kickMessage) {

    public enum Mode {
        OFF, SOFT, HARD;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode parse(String value) {
            if (value == null) {
                return OFF;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "soft", "softlock", "melding" -> SOFT;
                case "hard", "hardlock", "verplicht" -> HARD;
                default -> OFF;
            };
        }
    }

    /** Een verplichte mod. */
    public record Required(String id, String name) {
        public String label() {
            return name.isBlank() ? id : name;
        }
    }

    public static final int MAX_REQUIRED = 100;
    public static final int MAX_EXEMPT = 500;
    public static final int MAX_NAME = 48;
    public static final int MAX_URL = 300;
    public static final int MAX_MESSAGE = 2000;

    /** Leest de instellingen. Onzin wordt overgeslagen of vervangen door de standaard. */
    public static ModpackSettings read(YamlConfiguration config) {
        List<Required> required = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("required");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String id = ClientReport.id(key);
                if (id != null && required.size() < MAX_REQUIRED && required.stream().noneMatch(r -> r.id().equals(id))) {
                    String name = section.getString(key, "");
                    required.add(new Required(id, clip(name == null ? "" : name.strip(), MAX_NAME)));
                }
            }
        }
        Map<UUID, String> exempt = new LinkedHashMap<>();
        ConfigurationSection exempted = config.getConfigurationSection("exempt");
        if (exempted != null) {
            for (String key : exempted.getKeys(false)) {
                try {
                    exempt.put(UUID.fromString(key), exempted.getString(key, key));
                } catch (IllegalArgumentException ignored) {
                    // geen UUID: overslaan
                }
            }
        }
        String url = config.getString("modpack-url", "");
        return new ModpackSettings(Mode.parse(config.getString("mode", "off")),
                url == null ? "" : clip(url.strip(), MAX_URL), List.copyOf(required), Collections.unmodifiableMap(exempt),
                Math.max(3, Math.min(60, config.getInt("timeout-seconds", 8))),
                Math.max(0, Math.min(1440, config.getInt("remind-minutes", 0))),
                clip(config.getString("messages.soft", ""), MAX_MESSAGE), clip(config.getString("messages.kick", ""), MAX_MESSAGE));
    }

    /** Een geldige http(s)-link, of leeg. */
    public static boolean validUrl(String url) {
        return url.isEmpty() || url.length() <= MAX_URL && url.matches("https?://[^\\s<>\"']+");
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
