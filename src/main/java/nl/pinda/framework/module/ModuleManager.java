package nl.pinda.framework.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;

/** Start, stopt en herlaadt alle modules. */
public final class ModuleManager {

    private final PindaFramework plugin;
    private final Map<String, PindaModule> modules = new LinkedHashMap<>();

    public ModuleManager(PindaFramework plugin) {
        this.plugin = plugin;
    }

    public void register(PindaModule module) {
        modules.put(module.id(), module);
    }

    public void enableAll() {
        for (PindaModule module : modules.values()) {
            boolean wanted = module.isCore() || plugin.mainConfig().getBoolean("modules." + module.id(), true);
            if (!wanted) {
                plugin.getLogger().info("Module '" + module.id() + "' staat uit in config.yml.");
                continue;
            }
            try {
                module.enableModule();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Module '" + module.id() + "' kon niet starten", e);
            }
        }
    }

    public void disableAll() {
        List<PindaModule> reversed = new ArrayList<>(modules.values());
        Collections.reverse(reversed);
        for (PindaModule module : reversed) {
            try {
                module.disableModule();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Module '" + module.id() + "' kon niet netjes stoppen", e);
            }
        }
    }

    public void reloadAll() {
        for (PindaModule module : modules.values()) {
            try {
                module.reloadModule();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Module '" + module.id() + "' kon niet herladen", e);
            }
        }
    }

    public Collection<PindaModule> all() {
        return List.copyOf(modules.values());
    }

    public int enabledCount() {
        int count = 0;
        for (PindaModule module : modules.values()) {
            if (module.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    public <T extends PindaModule> T get(Class<T> type) {
        for (PindaModule module : modules.values()) {
            if (type.isInstance(module)) {
                return type.cast(module);
            }
        }
        return null;
    }
}
