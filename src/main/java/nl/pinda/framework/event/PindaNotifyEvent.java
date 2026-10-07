package nl.pinda.framework.event;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Een melding voor staff, bijvoorbeeld een ban of een rangwijziging. De Discord-koppeling
 * stuurt deze door; andere onderdelen kunnen er later ook naar luisteren.
 *
 * <p>Soorten: punishment, revoke, rank, panel-login, panel-action.
 * Gegevens (afhankelijk van de soort): player, actor, kind, reason, expires, rank, action,
 * target, details, ip.
 */
public final class PindaNotifyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String type;
    private final Map<String, String> data;

    public PindaNotifyEvent(String type, Map<String, String> data) {
        this.type = type;
        this.data = Map.copyOf(data);
    }

    public String type() {
        return type;
    }

    public Map<String, String> data() {
        return data;
    }

    public String get(String key) {
        return data.getOrDefault(key, "");
    }

    /** Stuurt een melding rond (op de hoofdthread). Paren: "player", "Henk", "actor", "Rudyh0", ... */
    public static void fire(Plugin plugin, String type, String... pairs) {
        Map<String, String> data = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] != null) {
                data.put(pairs[index], pairs[index + 1]);
            }
        }
        PindaNotifyEvent event = new PindaNotifyEvent(type, data);
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
