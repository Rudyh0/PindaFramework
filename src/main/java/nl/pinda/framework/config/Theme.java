package nl.pinda.framework.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * De huisstijl: kleuren als MiniMessage-tags ({@code <primary>}, {@code <error>}, ...),
 * de {@code <prefix>} en de geluiden die bij acties horen.
 */
public final class Theme {

    private final JavaPlugin plugin;
    private final Map<String, Sound> sounds = new HashMap<>();
    private TagResolver resolver = TagResolver.empty();

    public Theme(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(ConfigurationSection root) {
        TagResolver.Builder colors = TagResolver.builder();
        ConfigurationSection colorSection = root.getConfigurationSection("theme.colors");
        if (colorSection != null) {
            for (String key : colorSection.getKeys(false)) {
                String value = colorSection.getString(key, "");
                TextColor color = parseColor(value);
                if (color == null) {
                    plugin.getLogger().warning("Ongeldige kleur voor theme.colors." + key + ": '" + value + "'");
                    continue;
                }
                try {
                    colors.resolver(Placeholder.styling(key.toLowerCase(Locale.ROOT), color));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Ongeldige kleurnaam in theme.colors: '" + key
                            + "' (alleen kleine letters, cijfers, - en _)");
                }
            }
        }
        TagResolver colorResolver = colors.build();

        Component prefix = MiniMessage.miniMessage().deserialize(root.getString("theme.prefix", ""), colorResolver);
        resolver = TagResolver.resolver(colorResolver, Placeholder.component("prefix", prefix));

        sounds.clear();
        ConfigurationSection soundSection = root.getConfigurationSection("theme.sounds");
        if (soundSection != null) {
            for (String key : soundSection.getKeys(false)) {
                ConfigurationSection entry = soundSection.getConfigurationSection(key);
                String name;
                float volume = 1f;
                float pitch = 1f;
                if (entry != null) {
                    name = entry.getString("sound", "");
                    volume = (float) entry.getDouble("volume", 1.0);
                    pitch = (float) entry.getDouble("pitch", 1.0);
                } else {
                    name = soundSection.getString(key, "");
                }
                if (name == null || name.isBlank()) {
                    continue;
                }
                try {
                    Key soundKey = Key.key(name.trim().toLowerCase(Locale.ROOT));
                    sounds.put(key, Sound.sound(soundKey, Sound.Source.MASTER, volume, pitch));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Ongeldig geluid voor theme.sounds." + key + ": '" + name + "'");
                }
            }
        }
    }

    /** De tags voor kleuren en {@code <prefix>}, voor gebruik in MiniMessage. */
    public TagResolver resolver() {
        return resolver;
    }

    /** Speelt een themageluid af (success, error, click, ...). Doet niets voor de console. */
    public void play(CommandSender sender, String sound) {
        if (sender instanceof Player player) {
            Sound value = sounds.get(sound);
            if (value != null) {
                player.playSound(value);
            }
        }
    }

    private static TextColor parseColor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            return TextColor.fromHexString(trimmed);
        }
        return NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
    }
}
