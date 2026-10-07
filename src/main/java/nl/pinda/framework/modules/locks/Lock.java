package nl.pinda.framework.modules.locks;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Een slot op een kist, deur, luik of hek. */
public final class Lock {

    private final BlockKey key;
    private final UUID owner;
    private final Set<UUID> trusted = ConcurrentHashMap.newKeySet();
    private boolean everyone;

    public Lock(BlockKey key, UUID owner, boolean everyone) {
        this.key = key;
        this.owner = owner;
        this.everyone = everyone;
    }

    public BlockKey key() {
        return key;
    }

    public UUID owner() {
        return owner;
    }

    /** Spelers die de eigenaar los toegang heeft gegeven (naast partners). */
    public Set<UUID> trusted() {
        return Set.copyOf(trusted);
    }

    Set<UUID> rawTrusted() {
        return trusted;
    }

    /** Heeft de eigenaar dit blok voor iedereen opengezet? */
    public boolean everyone() {
        return everyone;
    }

    void everyone(boolean everyone) {
        this.everyone = everyone;
    }
}
