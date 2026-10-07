package nl.pinda.framework.player;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.pinda.framework.PindaFramework;
import org.bukkit.entity.Player;

/** Houdt bij welke instellingen er zijn en wat elke speler gekozen heeft. */
public final class SettingsService {

    private final PindaFramework plugin;
    private final Map<String, PlayerSetting> registry = new LinkedHashMap<>();

    public SettingsService(PindaFramework plugin) {
        this.plugin = plugin;
    }

    public void register(PlayerSetting setting) {
        registry.put(setting.id(), setting);
    }

    public void unregister(String id) {
        registry.remove(id);
    }

    /** Alle instellingen, in de volgorde waarin modules ze registreerden. */
    public Collection<PlayerSetting> all() {
        return List.copyOf(registry.values());
    }

    public PlayerSetting get(String id) {
        return registry.get(id);
    }

    public boolean isEnabled(Player player, String id) {
        PlayerSetting setting = registry.get(id);
        boolean defaultValue = setting == null || setting.defaultValue();
        return plugin.players().get(player).getBoolean(id, defaultValue);
    }

    public void set(Player player, String id, boolean value) {
        PindaPlayer data = plugin.players().get(player);
        data.setSetting(id, Boolean.toString(value));
        plugin.players().save(data);
    }

    /** Zet een instelling om en geeft de nieuwe waarde terug. */
    public boolean toggle(Player player, String id) {
        boolean value = !isEnabled(player, id);
        set(player, id, value);
        return value;
    }
}
