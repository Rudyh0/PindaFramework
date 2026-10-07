package nl.pinda.framework.module;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.config.ConfigFile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

/**
 * Basis voor een module, zoals homes, economy of tips.
 *
 * <p>Een module kan een eigen configbestand hebben ({@code modules/<id>.yml} in de jar),
 * listeners, commando's en taken registreren. Listeners en taken worden automatisch
 * opgeruimd als de module stopt.
 */
public abstract class PindaModule {

    protected final PindaFramework plugin;
    private final String id;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private ConfigFile config;
    private boolean enabled;

    protected PindaModule(PindaFramework plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    /** De naam van de module, zoals in config.yml onder 'modules'. */
    public final String id() {
        return id;
    }

    /** Kernmodules staan altijd aan en kunnen niet worden uitgezet. */
    public boolean isCore() {
        return false;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    protected abstract void onEnable();

    protected void onDisable() {
    }

    /** Wordt aangeroepen na /pinda reload, als de config van de module opnieuw is ingelezen. */
    protected void onReload() {
    }

    // ------------------------------------------------------------ levenscyclus

    public final void enableModule() {
        if (enabled) {
            return;
        }
        if (hasBundledConfig()) {
            config = new ConfigFile(plugin, configPath());
        }
        try {
            onEnable();
        } catch (RuntimeException e) {
            cleanup();
            throw e;
        }
        enabled = true;
    }

    public final void disableModule() {
        if (!enabled) {
            return;
        }
        enabled = false;
        try {
            onDisable();
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
        cancelTasks();
    }

    public final void reloadModule() {
        if (!enabled) {
            return;
        }
        if (config != null) {
            config.reload();
        }
        onReload();
    }

    // ---------------------------------------------------------------- helpers

    /** De config van deze module ({@code modules/<id>.yml}). */
    protected YamlConfiguration config() {
        if (config == null) {
            config = new ConfigFile(plugin, configPath());
        }
        return config.get();
    }

    protected void listen(Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    protected void command(PindaCommand command) {
        plugin.commands().register(command);
    }

    /** Herhaalt een taak op de hoofdthread. Wordt automatisch gestopt als de module stopt. */
    protected BukkitTask repeat(Runnable task, long delayTicks, long periodTicks) {
        BukkitTask scheduled = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        tasks.add(scheduled);
        return scheduled;
    }

    protected void cancelTasks() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    private String configPath() {
        return "modules/" + id + ".yml";
    }

    private boolean hasBundledConfig() {
        try (InputStream in = plugin.getResource(configPath())) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }
}
