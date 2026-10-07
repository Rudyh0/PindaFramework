package nl.pinda.framework.modules.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Rekenen met bedragen in centen en bedragen uit de chat lezen. */
public final class Money {

    private Money() {
    }

    public static long toCents(double amount) {
        return Math.round(amount * 100.0);
    }

    public static double toDouble(long cents) {
        return cents / 100.0;
    }

    /** Een percentage van een bedrag, afgerond op hele centen. */
    public static long percentage(long cents, double percent) {
        if (percent <= 0 || cents <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(cents)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * Leest een bedrag zoals spelers het typen: "100", "1.000", "2,50", "2.5", "1k" of "1,5m".
     * Geeft het aantal centen terug, of -1 als het geen geldig bedrag is.
     */
    public static long parse(String input) {
        if (input == null) {
            return -1;
        }
        String text = input.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        long multiplier = 1;
        if (text.endsWith("k")) {
            multiplier = 1_000;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("m")) {
            multiplier = 1_000_000;
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return -1;
        }
        if (text.contains(",")) {
            // Nederlands: punt = duizendtal, komma = decimaal
            text = text.replace(".", "").replace(',', '.');
        } else if (text.matches("[1-9]\\d{0,2}(\\.\\d{3})+")) {
            // "1.000" of "1.000.000" zijn duizendtallen
            text = text.replace(".", "");
        }
        try {
            BigDecimal value = new BigDecimal(text).multiply(BigDecimal.valueOf(multiplier));
            if (value.signum() < 0 || value.scale() > 2 && value.stripTrailingZeros().scale() > 2) {
                return -1;
            }
            return value.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }
}
