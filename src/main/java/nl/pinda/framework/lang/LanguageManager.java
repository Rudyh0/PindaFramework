package nl.pinda.framework.lang;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.config.ConfigFile;
import nl.pinda.framework.player.PindaPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Laadt de taalbestanden en verstuurt berichten in de taal van elke speler.
 *
 * <p>Ontbreekt een bericht in de taal van de speler, dan wordt de standaardtaal gebruikt,
 * en daarna Nederlands.
 */
public final class LanguageManager {

    private static final List<String> BUNDLED = List.of("nl", "en");
    private static final String FALLBACK = "nl";
    private static final String ACTIONBAR = "[actionbar]";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final PindaFramework plugin;
    private final Map<String, Language> languages = new LinkedHashMap<>();
    private String defaultLanguage = FALLBACK;

    public LanguageManager(PindaFramework plugin) {
        this.plugin = plugin;
    }

    public void load() {
        Map<String, Language> loaded = new LinkedHashMap<>();
        for (String code : BUNDLED) {
            ConfigFile file = new ConfigFile(plugin, "lang/" + code + ".yml");
            loaded.put(code, new Language(code, file.get()));
        }

        // Extra talen die de beheerder zelf heeft toegevoegd (bijv. lang/de.yml)
        File folder = new File(plugin.getDataFolder(), "lang");
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String name = file.getName();
                String code = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                if (!loaded.containsKey(code)) {
                    loaded.put(code, new Language(code, YamlConfiguration.loadConfiguration(file)));
                }
            }
        }

        languages.clear();
        languages.putAll(loaded);

        String configured = plugin.mainConfig().getString("default-language", FALLBACK).toLowerCase(Locale.ROOT);
        if (!languages.containsKey(configured)) {
            plugin.getLogger().warning("default-language '" + configured + "' bestaat niet in lang/, "
                    + FALLBACK + " wordt gebruikt.");
            configured = FALLBACK;
        }
        defaultLanguage = configured;
    }

    // ----------------------------------------------------------------- talen

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public Collection<Language> languages() {
        return List.copyOf(languages.values());
    }

    /** Zoekt een taal op code (nl) of naam (Nederlands). Geeft null als hij niet bestaat. */
    public Language find(String input) {
        if (input == null) {
            return null;
        }
        Language byCode = languages.get(input.toLowerCase(Locale.ROOT));
        if (byCode != null) {
            return byCode;
        }
        for (Language language : languages.values()) {
            if (language.name().equalsIgnoreCase(input)) {
                return language;
            }
        }
        return null;
    }

    /** De taal waarin iemand berichten krijgt. De console krijgt de standaardtaal. */
    public String languageOf(CommandSender sender) {
        if (sender instanceof Player player) {
            PindaPlayer data = plugin.players().get(player.getUniqueId());
            if (data != null && data.language() != null && languages.containsKey(data.language())) {
                return data.language();
            }
        }
        return defaultLanguage;
    }

    /**
     * Bepaalt een passende taal op basis van de taal van de Minecraft-client.
     *
     * @param fallback taal voor clients waarvan de taal niet op de server bestaat
     */
    public String detect(Player player, String fallback) {
        Locale locale = player.locale();
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        String full = locale.toString().toLowerCase(Locale.ROOT);
        for (Language candidate : languages.values()) {
            for (String clientLocale : candidate.clientLocales()) {
                if (clientLocale.equals(language) || clientLocale.equals(full)) {
                    return candidate.code();
                }
            }
        }
        if (fallback != null && languages.containsKey(fallback.toLowerCase(Locale.ROOT))) {
            return fallback.toLowerCase(Locale.ROOT);
        }
        return defaultLanguage;
    }

    // ------------------------------------------------------------- berichten

    /** De ruwe tekst van een bericht, of null als het nergens bestaat. Lijsten worden regels. */
    public String raw(String code, String key) {
        String value = rawFrom(languages.get(code), key);
        if (value == null && !code.equals(defaultLanguage)) {
            value = rawFrom(languages.get(defaultLanguage), key);
        }
        if (value == null && !FALLBACK.equals(defaultLanguage) && !FALLBACK.equals(code)) {
            value = rawFrom(languages.get(FALLBACK), key);
        }
        return value;
    }

    /** Een bericht als lijst van regels, bijvoorbeeld voor tips of de lore van een item. */
    public List<String> rawList(String code, String key) {
        List<String> value = listFrom(languages.get(code), key);
        if (value == null) {
            value = listFrom(languages.get(defaultLanguage), key);
        }
        if (value == null) {
            value = listFrom(languages.get(FALLBACK), key);
        }
        return value == null ? List.of() : value;
    }

    /** Zet MiniMessage-tekst om naar een component, met de thema-tags erbij. */
    public Component parse(String raw, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(raw, TagResolver.resolver(plugin.theme().resolver(), TagResolver.resolver(resolvers)));
    }

    public Component component(String code, String key, TagResolver... resolvers) {
        String raw = raw(code, key);
        if (raw == null) {
            return missing(key);
        }
        return parse(raw, resolvers);
    }

    public Component component(CommandSender sender, String key, TagResolver... resolvers) {
        return component(languageOf(sender), key, resolvers);
    }

    /** Elke regel van een lijst als los component, handig voor de lore van items. */
    public List<Component> components(String code, String key, TagResolver... resolvers) {
        String raw = raw(code, key);
        if (raw == null) {
            return List.of(missing(key));
        }
        List<Component> lines = new ArrayList<>();
        for (String line : rawList(code, key)) {
            lines.add(parse(line, resolvers));
        }
        return lines;
    }

    /** Stuurt een bericht in de taal van de ontvanger. Lege berichten worden niet verstuurd. */
    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        String raw = raw(languageOf(sender), key);
        if (raw == null) {
            sender.sendMessage(missing(key));
            return;
        }
        if (raw.isEmpty()) {
            return;
        }
        if (raw.startsWith(ACTIONBAR)) {
            Component message = parse(raw.substring(ACTIONBAR.length()).stripLeading(), resolvers);
            if (sender instanceof Player player) {
                player.sendActionBar(message);
            } else {
                sender.sendMessage(message);
            }
            return;
        }
        sender.sendMessage(parse(raw, resolvers));
    }

    /** Toont een titel en ondertitel in de taal van de speler. */
    public void sendTitle(Player player, String titleKey, String subtitleKey, TagResolver... resolvers) {
        String code = languageOf(player);
        Title title = Title.title(
                component(code, titleKey, resolvers),
                component(code, subtitleKey, resolvers),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(700)));
        player.showTitle(title);
    }

    // --------------------------------------------------------------- intern

    private static Component missing(String key) {
        return Component.text("[Ontbrekend bericht: " + key + "]", NamedTextColor.RED);
    }

    private static String rawFrom(Language language, String key) {
        if (language == null) {
            return null;
        }
        YamlConfiguration yaml = language.yaml();
        if (yaml.isList(key)) {
            return String.join("\n", yaml.getStringList(key));
        }
        if (yaml.isString(key)) {
            return yaml.getString(key);
        }
        if (yaml.isSet(key) && !yaml.isConfigurationSection(key)) {
            return String.valueOf(yaml.get(key));
        }
        return null;
    }

    private static List<String> listFrom(Language language, String key) {
        if (language == null) {
            return null;
        }
        YamlConfiguration yaml = language.yaml();
        if (yaml.isList(key)) {
            return yaml.getStringList(key);
        }
        if (yaml.isString(key)) {
            return List.of(yaml.getString(key));
        }
        return null;
    }
}
