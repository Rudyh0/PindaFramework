package nl.pinda.framework.player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** De gegevens die het framework van een speler bijhoudt. */
public final class PindaPlayer {

    private final UUID uuid;
    private final long firstJoin;
    private final boolean loadFailed;
    private final Map<String, String> settings = new ConcurrentHashMap<>();
    private volatile String name;
    private volatile String language;
    private volatile boolean setupCompleted;
    private volatile long lastSeen;

    public PindaPlayer(UUID uuid, String name, String language, boolean setupCompleted,
                       long firstJoin, long lastSeen, boolean loadFailed) {
        this.uuid = uuid;
        this.name = name;
        this.language = language;
        this.setupCompleted = setupCompleted;
        this.firstJoin = firstJoin;
        this.lastSeen = lastSeen;
        this.loadFailed = loadFailed;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    /** De gekozen taal, of null als de speler (nog) niets gekozen heeft. */
    public String language() {
        return language;
    }

    public void language(String language) {
        this.language = language;
    }

    public boolean setupCompleted() {
        return setupCompleted;
    }

    public void setupCompleted(boolean setupCompleted) {
        this.setupCompleted = setupCompleted;
    }

    public long firstJoin() {
        return firstJoin;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void lastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    /** True als laden mislukte; deze gegevens worden dan nooit opgeslagen. */
    public boolean loadFailed() {
        return loadFailed;
    }

    public boolean getBoolean(String setting, boolean defaultValue) {
        String value = settings.get(setting);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    public String getSetting(String setting) {
        return settings.get(setting);
    }

    public void setSetting(String setting, String value) {
        if (value == null) {
            settings.remove(setting);
        } else {
            settings.put(setting, value);
        }
    }

    Map<String, String> rawSettings() {
        return settings;
    }

    Map<String, String> settingsSnapshot() {
        return Map.copyOf(settings);
    }
}
