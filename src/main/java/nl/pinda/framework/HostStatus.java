package nl.pinda.framework;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.scheduler.BukkitTask;

/**
 * Een klein statusbestand ({@code plugins/PindaFramework/.status.json}) voor het dev-paneel
 * (PindaHost): hoeveel geheugen de game zelf gebruikt, de TPS en het aantal spelers. Het
 * dev-paneel kan dat zelf niet zien: de JVM houdt al het toegewezen geheugen vast, ook als
 * de game er maar een deel van gebruikt. Elke 5 seconden bijgewerkt; weg als de server stopt.
 */
final class HostStatus {

    static final String FILE = ".status.json";

    private final PindaFramework plugin;
    private final Path file;
    private final AtomicBoolean writing = new AtomicBoolean();
    private BukkitTask task;

    HostStatus(PindaFramework plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve(FILE);
    }

    void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::collect, 40L, 100L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // niet erg: het dev-paneel ziet aan de tijd dat hij oud is
        }
    }

    /** Op de hoofdthread verzamelen, op de achtergrond wegschrijven. */
    private void collect() {
        if (!writing.compareAndSet(false, true)) {
            return; // de vorige keer is nog niet klaar (trage schijf)
        }
        Runtime runtime = Runtime.getRuntime();
        long committed = runtime.totalMemory();
        String json = String.format(Locale.ROOT,
                "{\"time\":%d,\"pid\":%d,\"heapUsed\":%d,\"heapCommitted\":%d,\"heapMax\":%d,\"tps\":%.2f,\"mspt\":%.2f,\"online\":%d}",
                System.currentTimeMillis(), ProcessHandle.current().pid(), committed - runtime.freeMemory(), committed,
                runtime.maxMemory(), plugin.getServer().getTPS()[0], plugin.getServer().getAverageTickTime(),
                plugin.getServer().getOnlinePlayers().size());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                write(json);
            } catch (IOException e) {
                plugin.getLogger().log(Level.FINE, "Kon " + FILE + " niet schrijven", e);
            } finally {
                writing.set(false);
            }
        });
    }

    private void write(String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE + ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
