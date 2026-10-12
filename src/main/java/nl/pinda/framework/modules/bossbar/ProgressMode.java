package nl.pinda.framework.modules.bossbar;

import java.util.Locale;

/** Hoe vol de balk van een bossbar is, en hoe dat beweegt. */
public enum ProgressMode {
    /** Altijd even vol (de waarde in procenten). */
    STATIC("static"),
    /** Loopt leeg zolang de bossbar te zien is: een aftelbalk. */
    DECREASE("decrease"),
    /** Loopt vol zolang de bossbar te zien is. */
    INCREASE("increase"),
    /** Loopt steeds opnieuw leeg (elke cyclus). */
    LOOP("loop"),
    /** Loopt leeg en weer vol, heen en weer (elke cyclus). */
    BOUNCE("bounce"),
    /** Zo vol als een waarde: bijvoorbeeld spelers online van het maximum. */
    VALUE("value");

    private final String id;

    ProgressMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ProgressMode find(String id) {
        if (id != null) {
            String value = id.trim().toLowerCase(Locale.ROOT);
            for (ProgressMode mode : values()) {
                if (mode.id.equals(value)) {
                    return mode;
                }
            }
        }
        return STATIC;
    }

    public static boolean exists(String id) {
        return id != null && find(id).id.equals(id.trim().toLowerCase(Locale.ROOT));
    }
}
