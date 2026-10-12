package nl.pinda.framework.modules.bossbar;

import java.util.Locale;

/**
 * Een bewegend effect over de tekst van een bossbar. Het paneel doet dezelfde berekening na
 * voor het voorbeeld (app.js, bbEffect), dus pas ze samen aan.
 */
public enum TextEffect {
    /** Gewoon de tekst zoals hij is. */
    NONE("none"),
    /** Een kleurverloop dat over de tekst schuift (met twee of meer kleuren; ook een regenboog). */
    WAVE("wave"),
    /** Een lichte glans die van links naar rechts over de tekst gaat. */
    SHINE("shine"),
    /** De hele tekst kleurt rustig heen en weer tussen twee kleuren. */
    PULSE("pulse"),
    /** De tekst verschijnt letter voor letter, bij elk frame opnieuw. */
    TYPEWRITER("typewriter");

    private final String id;

    TextEffect(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static TextEffect find(String id) {
        if (id != null) {
            String value = id.trim().toLowerCase(Locale.ROOT);
            for (TextEffect effect : values()) {
                if (effect.id.equals(value)) {
                    return effect;
                }
            }
        }
        return NONE;
    }

    public static boolean exists(String id) {
        return id != null && find(id).id.equals(id.trim().toLowerCase(Locale.ROOT));
    }
}
