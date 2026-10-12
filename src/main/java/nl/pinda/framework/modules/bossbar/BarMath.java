package nl.pinda.framework.modules.bossbar;

/** De rekenregels van de bossbars, los van de server (zodat ze te testen zijn). */
final class BarMath {

    private BarMath() {
    }

    /** Hoe vol de balk is (0 tot 1). Het paneel rekent hetzelfde na (app.js, bbProgress). */
    static float progress(BarDefinition bar, long local, long length, float valueProgress) {
        double cycle = Math.max(1, bar.cycleTicks());
        double value = switch (bar.progress()) {
            case STATIC -> bar.value();
            case DECREASE -> length > 0 ? 1 - (double) local / length : 1 - (local % cycle) / cycle;
            case INCREASE -> length > 0 ? (double) local / length : (local % cycle) / cycle;
            case LOOP -> 1 - (local % cycle) / cycle;
            case BOUNCE -> Math.abs(1 - 2 * ((local % cycle) / cycle));
            case VALUE -> valueProgress;
        };
        return (float) Math.max(0, Math.min(1, value));
    }

    /** "1.234,5" en "1,234.5" en "€ 12,50" worden allemaal een getal. */
    static double parseNumber(String text) {
        StringBuilder digits = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c) || c == '.' || c == ',' || (c == '-' && digits.isEmpty())) {
                digits.append(c);
            } else if (!digits.isEmpty() && !Character.isWhitespace(c) && c != ' ') {
                break;
            }
        }
        String value = digits.toString();
        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            // Het laatste teken is het decimaalteken, het andere scheidt duizendtallen.
            value = comma > dot ? value.replace(".", "").replace(',', '.') : value.replace(",", "");
        } else if (comma >= 0 || dot >= 0) {
            char separator = comma >= 0 ? ',' : '.';
            int at = Math.max(comma, dot);
            String whole = value.substring(0, at).replace("-", "");
            boolean thousands = value.indexOf(separator) != at
                    || (value.length() - at - 1 == 3 && !whole.isEmpty() && !whole.equals("0"));
            // "1.234" of "1.234.567": duizendtallen; "19,8", "12.5" of "0.125": een decimaal.
            value = thousands ? value.replace(String.valueOf(separator), "") : value.replace(separator, '.');
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
