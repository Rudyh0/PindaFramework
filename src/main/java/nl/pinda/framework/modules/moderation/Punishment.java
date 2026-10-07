package nl.pinda.framework.modules.moderation;

import java.util.UUID;

/**
 * Een straf: ban, mute, kick of waarschuwing.
 *
 * @param expires null = permanent (bij ban en mute)
 * @param active  false als de straf is opgeheven of verlopen (kick en warn zijn nooit actief)
 */
public record Punishment(long id, UUID target, String targetName, Type type, String reason,
                         UUID actor, String actorName, long created, Long expires, boolean active) {

    public enum Type { BAN, MUTE, KICK, WARN }

    public boolean permanent() {
        return expires == null;
    }

    public boolean expired() {
        return expires != null && expires <= System.currentTimeMillis();
    }

    /** Actief en nog niet verlopen. */
    public boolean inEffect() {
        return active && !expired();
    }
}
