package nl.pinda.framework.teleport;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.config.ConfigFile;
import nl.pinda.framework.economy.Economy;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.player.PindaPlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Voert alle teleports van het framework uit, met wachttijd, cooldown, veilige plek en kosten.
 * Homes, TPA, spawn en back gebruiken allemaal deze service.
 */
public final class TeleportService implements Listener {

    public static final String BYPASS_WARMUP = "pinda.teleport.bypass.warmup";
    public static final String BYPASS_COOLDOWN = "pinda.teleport.bypass.cooldown";
    public static final String FREE = "pinda.teleport.free";

    private final PindaFramework plugin;
    private final ConfigFile config;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();

    public TeleportService(PindaFramework plugin) {
        this.plugin = plugin;
        this.config = new ConfigFile(plugin, "teleport.yml");
    }

    public void reload() {
        config.reload();
    }

    private YamlConfiguration cfg() {
        return config.get();
    }

    // ------------------------------------------------------------- controles

    /**
     * Controleert of een teleport kan beginnen: geen lopende teleport, geen cooldown en
     * genoeg geld. Stuurt zelf een melding als het niet kan.
     */
    public boolean canStart(Player traveler, Player payer, TeleportType type) {
        if (pending.containsKey(traveler.getUniqueId())) {
            fail(traveler, "teleport.already-pending");
            return false;
        }
        long remaining = cooldownRemaining(traveler);
        if (remaining > 0) {
            fail(traveler, "teleport.cooldown", Text.p("seconds", (remaining + 999) / 1000));
            return false;
        }
        return canAfford(payer, type);
    }

    /** Controleert of de betaler genoeg geld heeft. Stuurt zelf een melding als dat niet zo is. */
    public boolean canAfford(Player payer, TeleportType type) {
        double cost = cost(payer, type);
        if (cost > 0 && !plugin.economy().has(payer.getUniqueId(), cost)) {
            fail(payer, "teleport.no-money", Text.p("cost", plugin.economy().format(cost)));
            return false;
        }
        return true;
    }

    /** Resterende cooldown in milliseconden. */
    public long cooldownRemaining(Player player) {
        if (player.hasPermission(BYPASS_COOLDOWN)) {
            return 0;
        }
        Long last = lastTeleport.get(player.getUniqueId());
        if (last == null) {
            return 0;
        }
        long end = last + Math.max(0, cfg().getLong("cooldown-seconds", 5)) * 1000L;
        return Math.max(0, end - System.currentTimeMillis());
    }

    /**
     * De kosten van een teleport voor deze speler. Gratis als er geen economy is, de speler
     * nog in zijn gratis periode zit of de permissie {@value #FREE} heeft.
     */
    public double cost(Player payer, TeleportType type) {
        if (payer == null || !plugin.economy().isEnabled() || !cfg().getBoolean("costs.enabled", true)) {
            return 0;
        }
        if (payer.hasPermission(FREE)) {
            return 0;
        }
        double freeHours = cfg().getDouble("costs.free-hours", 24);
        PindaPlayer data = plugin.players().get(payer);
        if (System.currentTimeMillis() - data.firstJoin() < (long) (freeHours * 3_600_000L)) {
            return 0;
        }
        return Math.max(0, cfg().getDouble("costs." + type.key(), 0));
    }

    /**
     * Een regel voor in menu's met de kosten ("Kosten: 5,00" of "Gratis"), in de taal van de speler.
     * Geeft null als er geen economy is, dan tonen we niets over kosten.
     */
    public Component costLine(Player payer, TeleportType type) {
        if (!plugin.economy().isEnabled()) {
            return null;
        }
        double cost = cost(payer, type);
        if (cost <= 0) {
            return plugin.lang().component(payer, "teleport.cost-free");
        }
        return plugin.lang().component(payer, "teleport.cost-line", Text.p("cost", plugin.economy().format(cost)));
    }

    // ------------------------------------------------------------ teleporteren

    /**
     * Start een teleport. Met wachttijd telt hij eerst af; bewegen of schade breekt hem af.
     *
     * @return false als de teleport niet kon beginnen (de speler kreeg dan al een melding)
     */
    public boolean teleport(TeleportRequest request) {
        Player traveler = request.traveler();
        if (!canStart(traveler, request.payer(), request.type())) {
            return false;
        }
        int warmup = traveler.hasPermission(BYPASS_WARMUP) ? 0 : Math.max(0, cfg().getInt("warmup-seconds", 3));
        if (warmup == 0) {
            execute(request);
            return true;
        }
        Pending entry = new Pending(request, warmup);
        pending.put(traveler.getUniqueId(), entry);
        plugin.lang().send(traveler, "teleport.warmup", Text.p("seconds", warmup));
        entry.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(entry), 0L, 20L);
        return true;
    }

    /** Is deze speler aan het aftellen voor een teleport? */
    public boolean isPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    private void tick(Pending entry) {
        Player traveler = entry.request.traveler();
        UUID uuid = traveler.getUniqueId();
        if (!traveler.isOnline() || pending.get(uuid) != entry) {
            entry.cancel();
            pending.remove(uuid, entry);
            return;
        }
        if (entry.remaining <= 0) {
            entry.cancel();
            pending.remove(uuid);
            execute(entry.request);
            return;
        }
        plugin.lang().send(traveler, "teleport.countdown", Text.p("seconds", entry.remaining));
        plugin.theme().play(traveler, "countdown");
        entry.remaining--;
    }

    private void execute(TeleportRequest request) {
        Player traveler = request.traveler();
        if (!traveler.isOnline()) {
            return;
        }
        Location destination = request.destination().get();
        World world = destination == null ? null : destination.getWorld();
        if (world == null) {
            fail(traveler, "teleport.destination-gone");
            return;
        }
        Player payer = request.payer();
        Economy economy = plugin.economy();
        double cost = cost(payer, request.type());
        if (cost > 0 && (payer == null || !payer.isOnline() || !economy.has(payer.getUniqueId(), cost))) {
            fail(traveler, "teleport.no-money", Text.p("cost", economy.format(cost)));
            return;
        }

        world.getChunkAtAsync(destination).thenAccept(chunk -> {
            if (!traveler.isOnline()) {
                return;
            }
            Location target = request.safe() ? SafeLocation.find(destination) : destination;
            if (target == null) {
                fail(traveler, "teleport.no-safe-location");
                return;
            }
            traveler.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
                if (!Boolean.TRUE.equals(success)) {
                    fail(traveler, "teleport.failed");
                    return;
                }
                if (cost > 0 && payer != null && economy.withdraw(payer.getUniqueId(), cost)) {
                    plugin.lang().send(payer, "teleport.paid", Text.p("cost", economy.format(cost)));
                }
                lastTeleport.put(traveler.getUniqueId(), System.currentTimeMillis());
                plugin.theme().play(traveler, "teleport");
                if (request.onSuccess() != null) {
                    request.onSuccess().run();
                }
            }).exceptionally(error -> {
                plugin.getLogger().log(Level.SEVERE, "Teleport van " + traveler.getName() + " mislukt", error);
                return null;
            });
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon de bestemming van " + traveler.getName() + " niet laden", error);
            return null;
        });
    }

    private void cancel(Player player, String messageKey) {
        Pending entry = pending.remove(player.getUniqueId());
        if (entry != null) {
            entry.cancel();
            fail(player, messageKey);
        }
    }

    private void fail(Player player, String key, TagResolver... resolvers) {
        if (player == null) {
            return;
        }
        plugin.lang().send(player, key, resolvers);
        plugin.theme().play(player, "error");
    }

    // ----------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (pending.isEmpty() || !event.hasChangedBlock()) {
            return;
        }
        if (pending.containsKey(event.getPlayer().getUniqueId()) && cfg().getBoolean("cancel-on-move", true)) {
            cancel(event.getPlayer(), "teleport.cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (pending.isEmpty() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (pending.containsKey(player.getUniqueId()) && cfg().getBoolean("cancel-on-damage", true)) {
            cancel(player, "teleport.cancelled-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Pending entry = pending.remove(event.getPlayer().getUniqueId());
        if (entry != null) {
            entry.cancel();
        }
    }

    /** Een teleport die aan het aftellen is. */
    private static final class Pending {
        private final TeleportRequest request;
        private int remaining;
        private BukkitTask task;

        private Pending(TeleportRequest request, int seconds) {
            this.request = request;
            this.remaining = seconds;
        }

        private void cancel() {
            if (task != null) {
                task.cancel();
            }
        }
    }
}
