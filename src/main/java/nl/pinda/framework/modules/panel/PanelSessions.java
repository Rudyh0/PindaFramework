package nl.pinda.framework.modules.panel;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inloglinks en sessies van het paneel. Alles staat alleen in het geheugen: na een herstart
 * moet iedereen opnieuw /panel typen.
 */
final class PanelSessions {

    private static final int MAX_FAILED_LOGINS = 10;
    private static final long FAILED_WINDOW_MS = 10 * 60 * 1000L;
    /** Hoe lang je hebt om de 2FA-code in te vullen. */
    private static final long PENDING_MS = 10 * 60 * 1000L;

    /** Een inloglink die nog niet gebruikt is. */
    record Token(UUID uuid, String name, long expires) {
    }

    /** Een ingelogde browser. */
    static final class Session {
        final String id;
        final UUID uuid;
        final String name;
        final String ip;
        final long created;
        volatile long lastSeen;
        volatile PanelUser user;
        volatile long userLoaded;
        /** Nog niet klaar met inloggen: de 2FA-code moet nog ingevuld worden. */
        volatile boolean pending;
        /** Bij de eerste keer: het nieuwe geheim dat nog bevestigd moet worden. */
        volatile String setupSecret;
        volatile int attempts;

        Session(String id, UUID uuid, String name, String ip) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.ip = ip;
            this.created = System.currentTimeMillis();
            this.lastSeen = created;
        }
    }

    private record Failures(int count, long since) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private String randomId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ============================================================ inloglinks

    /** Maakt een nieuwe inloglink. Oudere, ongebruikte links van dezelfde speler vervallen. */
    String createToken(UUID uuid, String name, long validMillis) {
        tokens.values().removeIf(token -> token.uuid().equals(uuid));
        String id = randomId();
        tokens.put(id, new Token(uuid, name, System.currentTimeMillis() + validMillis));
        return id;
    }

    /** Gebruikt een inloglink. Null als hij niet bestaat, al gebruikt is of verlopen is. */
    Token consume(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Token token = tokens.remove(id.trim());
        if (token == null || token.expires() < System.currentTimeMillis()) {
            return null;
        }
        return token;
    }

    // ============================================================ sessies

    Session create(UUID uuid, String name, String ip) {
        Session session = new Session(randomId(), uuid, name, ip);
        sessions.put(session.id, session);
        return session;
    }

    /** De sessie bij een cookie, of null als die niet (meer) geldig is. */
    Session get(String id, long idleMillis, long maxMillis) {
        if (id == null) {
            return null;
        }
        Session session = sessions.get(id);
        if (session == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - session.lastSeen > idleMillis || now - session.created > maxMillis
                || (session.pending && now - session.created > PENDING_MS)) {
            sessions.remove(id);
            return null;
        }
        session.lastSeen = now;
        return session;
    }

    void remove(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    /** Logt een speler overal uit. Geeft het aantal beëindigde sessies. */
    int removeAll(UUID uuid) {
        int before = sessions.size();
        sessions.values().removeIf(session -> session.uuid.equals(uuid));
        tokens.values().removeIf(token -> token.uuid().equals(uuid));
        return before - sessions.size();
    }

    void clear() {
        sessions.clear();
        tokens.clear();
        failures.clear();
    }

    /** De actieve sessies, nieuwste activiteit eerst. */
    List<Session> active(long idleMillis, long maxMillis) {
        cleanup(idleMillis, maxMillis);
        List<Session> list = new ArrayList<>(sessions.values());
        list.removeIf(session -> session.pending);
        list.sort(Comparator.comparingLong((Session session) -> session.lastSeen).reversed());
        return list;
    }

    void cleanup(long idleMillis, long maxMillis) {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(token -> token.expires() < now);
        sessions.values().removeIf(session -> now - session.lastSeen > idleMillis || now - session.created > maxMillis);
        failures.values().removeIf(entry -> now - entry.since() > FAILED_WINDOW_MS);
    }

    // ============================================================ te veel foute pogingen

    boolean blocked(String ip) {
        Failures entry = failures.get(ip);
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() - entry.since() > FAILED_WINDOW_MS) {
            failures.remove(ip);
            return false;
        }
        return entry.count() >= MAX_FAILED_LOGINS;
    }

    void failed(String ip) {
        long now = System.currentTimeMillis();
        failures.merge(ip, new Failures(1, now), (old, ignored) ->
                now - old.since() > FAILED_WINDOW_MS ? new Failures(1, now) : new Failures(old.count() + 1, old.since()));
    }
}
