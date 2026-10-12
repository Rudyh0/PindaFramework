package nl.pinda.framework.modules.bossbar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Eén bossbar zoals hij in modules/bossbar.yml staat (onder 'bars'): de tekst (of een reeks
 * frames), kleur, stijl, hoe de balk beweegt, een effect over de tekst en wie hem ziet.
 *
 * @param frames         de teksten (MiniMessage); bij meer dan één wisselen ze elkaar af
 * @param frameTicks     hoe lang elk frame blijft staan
 * @param value          bij {@link ProgressMode#STATIC}: hoe vol (0 tot 1)
 * @param progressValue  bij {@link ProgressMode#VALUE}: de waarde (bijv. &lt;online&gt; of %placeholder%)
 * @param progressMax    bij {@link ProgressMode#VALUE}: het maximum
 * @param cycleTicks     bij {@link ProgressMode#LOOP} en {@link ProgressMode#BOUNCE}: hoe lang één ronde duurt
 * @param durationTicks  hoe lang hij blijft staan in een wisseling (0 = altijd)
 * @param permission     alleen voor spelers met deze permissie (leeg = iedereen)
 * @param worlds         alleen in deze werelden (leeg = overal)
 * @param disabledWorlds niet in deze werelden
 */
public record BarDefinition(
        String id,
        List<String> frames,
        int frameTicks,
        BossBar.Color color,
        BossBar.Overlay overlay,
        ProgressMode progress,
        float value,
        String progressValue,
        String progressMax,
        int cycleTicks,
        int durationTicks,
        TextEffect effect,
        List<TextColor> effectColors,
        double effectSpeed,
        String permission,
        List<String> worlds,
        List<String> disabledWorlds) {

    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    public static final int MAX_FRAMES = 30;
    public static final int MAX_TEXT = 600;
    public static final List<String> COLORS = List.of("pink", "blue", "red", "green", "yellow", "purple", "white");
    public static final List<String> STYLES = List.of("progress", "notched_6", "notched_10", "notched_12", "notched_20");

    /** Leest een bossbar uit de config. Vreemde waarden worden netjes begrensd. */
    public static BarDefinition parse(String id, ConfigurationSection section) {
        List<String> frames = new ArrayList<>();
        if (section.isList("text")) {
            for (String line : section.getStringList("text")) {
                if (frames.size() < MAX_FRAMES) {
                    frames.add(clip(line));
                }
            }
        } else {
            String text = section.getString("text", "");
            frames.add(clip(text == null ? "" : text));
        }
        if (frames.isEmpty()) {
            frames.add("");
        }
        List<TextColor> colors = new ArrayList<>();
        for (String value : section.getStringList("effect-colors")) {
            TextColor color = TextColor.fromHexString(value.trim());
            if (color != null && colors.size() < 8) {
                colors.add(color);
            }
        }
        return new BarDefinition(id, List.copyOf(frames),
                ticks(section.getDouble("frame-seconds", 1.0), 2, 20 * 600),
                color(section.getString("color", "yellow")),
                overlay(section.getString("style", "progress")),
                ProgressMode.find(section.getString("progress", "static")),
                (float) Math.max(0, Math.min(100, section.getDouble("value", 100))) / 100f,
                section.getString("progress-value", "<online>"),
                section.getString("progress-max", "<max>"),
                ticks(section.getDouble("cycle-seconds", 5), 10, 20 * 3600),
                ticks(section.getDouble("duration", 10), 0, 20 * 86400),
                TextEffect.find(section.getString("effect", "none")),
                List.copyOf(colors),
                Math.max(0.1, Math.min(5.0, section.getDouble("effect-speed", 1.0))),
                section.getString("permission", "").trim(),
                lowerList(section.getStringList("worlds")),
                lowerList(section.getStringList("disabled-worlds")));
    }

    /** Zet de bossbar in de config (voor het paneel). */
    public void write(ConfigurationSection section) {
        section.set("text", frames.size() == 1 ? frames.get(0) : frames);
        section.set("frame-seconds", yamlNumber(seconds(frameTicks)));
        section.set("color", color.name().toLowerCase(Locale.ROOT));
        section.set("style", overlay.name().toLowerCase(Locale.ROOT));
        section.set("progress", progress.id());
        section.set("value", yamlNumber(Math.round(value * 1000) / 10.0));
        section.set("progress-value", progressValue);
        section.set("progress-max", progressMax);
        section.set("cycle-seconds", yamlNumber(seconds(cycleTicks)));
        section.set("duration", yamlNumber(seconds(durationTicks)));
        section.set("effect", effect.id());
        section.set("effect-colors", effectColors.stream().map(color -> color.asHexString().toUpperCase(Locale.ROOT)).toList());
        section.set("effect-speed", yamlNumber(Math.round(effectSpeed * 10) / 10.0));
        section.set("permission", permission);
        section.set("worlds", worlds);
        section.set("disabled-worlds", disabledWorlds);
    }

    /** Voor het paneel. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("frames", frames);
        map.put("frameSeconds", seconds(frameTicks));
        map.put("color", color.name().toLowerCase(Locale.ROOT));
        map.put("style", overlay.name().toLowerCase(Locale.ROOT));
        map.put("progress", progress.id());
        map.put("value", Math.round(value * 1000) / 10.0);
        map.put("progressValue", progressValue);
        map.put("progressMax", progressMax);
        map.put("cycleSeconds", seconds(cycleTicks));
        map.put("duration", seconds(durationTicks));
        map.put("effect", effect.id());
        map.put("effectColors", effectColors.stream().map(TextColor::asHexString).toList());
        map.put("effectSpeed", effectSpeed);
        map.put("permission", permission);
        map.put("worlds", worlds);
        map.put("disabledWorlds", disabledWorlds);
        return map;
    }

    /** Hoe lang hij in een wisseling staat: 0 (altijd) wordt daar 10 seconden als er meer bars zijn. */
    int slotTicks(boolean alone) {
        if (durationTicks > 0) {
            return durationTicks;
        }
        return alone ? 0 : 200;
    }

    // ============================================================ hulpjes

    static BossBar.Color color(String name) {
        try {
            return BossBar.Color.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.YELLOW;
        }
    }

    static BossBar.Overlay overlay(String name) {
        try {
            return BossBar.Overlay.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    static int ticks(double seconds, int min, int max) {
        if (Double.isNaN(seconds)) {
            return min;
        }
        return (int) Math.max(min, Math.min(max, Math.round(seconds * 20)));
    }

    /**
     * Een heel getal als 6 en niet als 6.0: anders ziet de update van de standaardinstellingen het
     * als door de beheerder aangepast (6 is niet gelijk aan 6.0).
     */
    static Object yamlNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9 ? (Object) (int) value : (Object) value;
    }

    static double seconds(int ticks) {
        return Math.round(ticks / 2.0) / 10.0;
    }

    private static String clip(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    private static List<String> lowerList(List<String> values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String clean = value.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty() && !out.contains(clean)) {
                out.add(clean);
            }
        }
        return List.copyOf(out);
    }
}
