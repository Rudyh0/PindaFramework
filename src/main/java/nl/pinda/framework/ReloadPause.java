package nl.pinda.framework;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Het hele framework herladen terwijl spelers even stilstaan: eerst krijgen ze een melding
 * ("de server herlaadt, over een paar tellen kun je verder"), dan wordt alles herladen, en
 * daarna mogen ze weer verder. Zo gebeurt er niets half terwijl instellingen wisselen.
 */
public final class ReloadPause implements Listener {

    /** Eerst even stilstaan, zodat iedereen de melding ziet. */
    private static final long BEFORE_TICKS = 20L;
    /** Na het herladen nog even, zodat alles weer loopt voordat spelers verder gaan. */
    private static final long AFTER_TICKS = 30L;

    private final PindaFramework plugin;
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private CompletableFuture<Long> running;

    ReloadPause(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /** Herlaadt alles (hoofdthread). Klaar met de duur van het herladen in ms, als spelers weer verder kunnen. */
    public CompletableFuture<Long> reload() {
        if (running != null && !running.isDone()) {
            return running; // er loopt al een herlaadronde: daar sluiten we bij aan
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        running = future;
        if (plugin.getServer().getOnlinePlayers().isEmpty()) {
            run(future, false);
            return future;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            freeze(player);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> run(future, true), BEFORE_TICKS);
        return future;
    }

    public boolean isPaused() {
        return running != null && !running.isDone();
    }

    private void run(CompletableFuture<Long> future, boolean wait) {
        long millis;
        try {
            millis = plugin.reload();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Herladen mislukt", e);
            resume(false);
            future.completeExceptionally(e);
            return;
        }
        if (!wait) {
            future.complete(millis);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            resume(true);
            future.complete(millis);
        }, AFTER_TICKS);
    }

    private void freeze(Player player) {
        frozen.add(player.getUniqueId());
        plugin.lang().sendTitle(player, "admin.reload-pause.title", "admin.reload-pause.subtitle");
        plugin.theme().play(player, "countdown");
    }

    private void resume(boolean ok) {
        for (UUID uuid : frozen) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.clearTitle();
                plugin.lang().send(player, ok ? "admin.reload-pause.done" : "admin.reload-pause.failed");
            }
        }
        frozen.clear();
    }

    private boolean stopped(Player player) {
        return frozen.contains(player.getUniqueId());
    }

    // ============================================================ stilstaan

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (isPaused()) {
            freeze(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        frozen.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (stopped(event.getPlayer()) && event.hasChangedPosition()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (stopped(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (stopped(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (stopped(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (stopped(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (stopped(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && stopped(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (stopped(event.getPlayer())) {
            event.setCancelled(true);
            plugin.lang().send(event.getPlayer(), "admin.reload-pause.wait");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (stopped(event.getPlayer())) {
            event.setCancelled(true);
            plugin.lang().send(event.getPlayer(), "admin.reload-pause.wait");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && stopped(player)) {
            event.setCancelled(true);
        }
    }
}
