package nl.pinda.framework.integration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.pinda.framework.PindaFramework;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI in onze eigen teksten (aankondigingen, scoreboard): %...% wordt ingevuld als
 * PlaceholderAPI op de server staat. Zonder PlaceholderAPI verandert er niets.
 */
public final class Placeholders {

    private static final Pattern TOKEN = Pattern.compile("%[A-Za-z][^%\\s]*%");
    private static final Pattern LEGACY_AMPERSAND = Pattern.compile("&[0-9a-fk-orA-FK-OR#]");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private Placeholders() {
    }

    /** Staat PlaceholderAPI aan? */
    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /**
     * Vult de %placeholders% in voor deze speler. Kleurcodes uit de uitkomst (§a, &amp;a) worden
     * omgezet naar MiniMessage; gewone tekst wordt zo ingevoegd dat er geen tags in kunnen sluipen.
     */
    public static String apply(Player player, String text) {
        if (text == null || text.indexOf('%') < 0 || !available()) {
            return text;
        }
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String value;
            try {
                value = Papi.set(player, token);
            } catch (RuntimeException | LinkageError e) {
                value = token;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value.equals(token) ? token : toMini(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String toMini(String value) {
        if (value.indexOf('§') >= 0) {
            return MINI_MESSAGE.serialize(LegacyComponentSerializer.legacySection().deserialize(value));
        }
        if (LEGACY_AMPERSAND.matcher(value).find()) {
            return MINI_MESSAGE.serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(value));
        }
        return MINI_MESSAGE.escapeTags(value);
    }

    /** Alleen geladen als PlaceholderAPI er is. */
    private static final class Papi {
        static String set(Player player, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
    }

    // ============================================================ onze eigen placeholders aanmelden

    private static Object expansion;

    /** Meldt %pinda_...% aan bij PlaceholderAPI (als die er is). */
    public static void register(PindaFramework plugin) {
        if (!available() || expansion != null) {
            return;
        }
        try {
            expansion = Hook.register(plugin);
            if (expansion != null) {
                plugin.getLogger().info("PlaceholderAPI gevonden: de %pinda_...% placeholders staan klaar.");
            }
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Kon de placeholders niet aanmelden bij PlaceholderAPI: " + e.getMessage());
        }
    }

    public static void unregister() {
        if (expansion == null) {
            return;
        }
        try {
            Hook.unregister(expansion);
        } catch (RuntimeException | LinkageError ignored) {
            // PlaceholderAPI is al weg
        }
        expansion = null;
    }

    private static final class Hook {
        static Object register(PindaFramework plugin) {
            PindaExpansion created = new PindaExpansion(plugin);
            return created.register() ? created : null;
        }

        static void unregister(Object expansion) {
            ((PindaExpansion) expansion).unregister();
        }
    }
}
