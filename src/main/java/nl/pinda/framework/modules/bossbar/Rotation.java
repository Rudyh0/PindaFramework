package nl.pinda.framework.modules.bossbar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Een wisseling: één plek bovenin het scherm waar bossbars elkaar afwisselen, elk zo lang als
 * zijn 'duration'. Na de laatste begint hij weer vooraan (loop), of hij is klaar.
 *
 * @param onJoin  begint als de speler joint (bijv. een welkomstbalk); anders voor iedereen tegelijk
 * @param enabled staat de wisseling aan
 */
public record Rotation(String name, List<String> bars, boolean loop, boolean onJoin, boolean enabled) {

    public static final int MAX_BARS = 30;

    /**
     * Welke bossbar er nu staat, hoe lang al ({@code local}) en hoe lang in totaal ({@code length},
     * 0 = zolang de wisseling loopt).
     */
    public record Placement(BarDefinition bar, long local, long length, int index) {
    }

    public static Rotation parse(Map<?, ?> map) {
        Object name = map.get("name");
        List<String> bars = new ArrayList<>();
        if (map.get("bars") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && bars.size() < MAX_BARS) {
                    bars.add(String.valueOf(item).trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return new Rotation(name == null ? "" : String.valueOf(name), List.copyOf(bars),
                bool(map.get("loop"), true), "join".equals(String.valueOf(map.get("start"))), bool(map.get("enabled"), true));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("bars", bars);
        map.put("loop", loop);
        map.put("start", onJoin ? "join" : "always");
        map.put("enabled", enabled);
        return map;
    }

    /**
     * Wat er na {@code ticks} ticks staat (vanaf de start van de wisseling), of null als er
     * niets (meer) is.
     */
    public Placement at(long ticks, Map<String, BarDefinition> definitions) {
        return at(ticks, definitions, bar -> true);
    }

    /**
     * Zoals {@link #at(long, Map)}, maar alleen met de bossbars die deze speler kan zien (permissie,
     * wereld): dan valt er geen gat en blijft de volgorde van de bossbars op het scherm gelijk.
     */
    public Placement at(long ticks, Map<String, BarDefinition> definitions, Predicate<BarDefinition> visible) {
        List<BarDefinition> list = new ArrayList<>();
        for (String id : bars) {
            BarDefinition bar = definitions.get(id);
            if (bar != null && visible.test(bar)) {
                list.add(bar);
            }
        }
        if (list.isEmpty() || ticks < 0) {
            return null;
        }
        boolean alone = list.size() == 1;
        long total = 0;
        for (BarDefinition bar : list) {
            total += bar.slotTicks(alone);
        }
        if (total == 0) {
            // Eén bossbar die altijd blijft staan.
            return new Placement(list.get(0), ticks, 0, 0);
        }
        if (!loop && ticks >= total) {
            return null;
        }
        long at = loop ? ticks % total : ticks;
        for (int index = 0; index < list.size(); index++) {
            long length = list.get(index).slotTicks(alone);
            if (at < length) {
                return new Placement(list.get(index), at, length, index);
            }
            at -= length;
        }
        return null;
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
}
