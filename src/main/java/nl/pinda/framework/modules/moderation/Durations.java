package nl.pinda.framework.modules.moderation;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Een duur lezen en tonen: "30m", "2u", "1d12u", "1w". */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)(maanden|maand|mo|dagen|dag|weken|week|jaar|uur|min|sec|s|m|u|h|d|w|y|j)");
    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;
    private static final long MONTH = 30 * DAY;
    private static final long YEAR = 365 * DAY;

    private Durations() {
    }

    /**
     * Leest een duur zoals "30m", "2u", "1d12u" of "1w" (ook "2h" en "1y").
     * Geeft het aantal milliseconden, of -1 als het geen duur is.
     */
    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            return -1;
        }
        String text = input.toLowerCase(Locale.ROOT).trim();
        Matcher matcher = PART.matcher(text);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                return -1;
            }
            long amount = Long.parseLong(matcher.group(1));
            total += amount * unit(matcher.group(2));
            end = matcher.end();
        }
        return end == text.length() && total > 0 ? total : -1;
    }

    private static long unit(String unit) {
        return switch (unit) {
            case "s", "sec" -> SECOND;
            case "m", "min" -> MINUTE;
            case "u", "h", "uur" -> HOUR;
            case "d", "dag", "dagen" -> DAY;
            case "w", "week", "weken" -> WEEK;
            case "mo", "maand", "maanden" -> MONTH;
            default -> YEAR;
        };
    }

    /**
     * Een duur kort opgeschreven, bijv. "2d 4u 10m".
     *
     * @param units de afkortingen voor dag, uur, minuut en seconde (bijv. "d", "u", "m", "s")
     */
    public static String format(long millis, String[] units) {
        if (millis < MINUTE) {
            return Math.max(1, millis / SECOND) + units[3];
        }
        long days = millis / DAY;
        long hours = (millis % DAY) / HOUR;
        long minutes = (millis % HOUR) / MINUTE;
        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append(units[0]).append(' ');
        }
        if (hours > 0) {
            builder.append(hours).append(units[1]).append(' ');
        }
        if (minutes > 0 && days == 0) {
            builder.append(minutes).append(units[2]).append(' ');
        }
        return builder.toString().trim();
    }
}
