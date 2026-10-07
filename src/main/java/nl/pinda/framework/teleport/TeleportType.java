package nl.pinda.framework.teleport;

import java.util.Locale;

/** Soort teleport. Bepaalt onder andere de kosten in teleport.yml. */
public enum TeleportType {
    HOME,
    TPA,
    SPAWN,
    BACK;

    /** De naam in configbestanden, bijv. "home". */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
