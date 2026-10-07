package nl.pinda.framework.modules.afk;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.staff.StaffModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * AFK: /afk [reden], automatisch AFK na een tijdje niets doen, [AFK] in de tablist
 * en eventueel kicken na lange tijd AFK.
 */
public final class AfkModule extends PindaModule implements Listener {

    public static final String USE = "pinda.afk.use";
    public static final String KICK_EXEMPT = "pinda.afk.kick-exempt";
    private static final Set<String> AFK_COMMANDS = Set.of("/afk", "/pindaframework:afk");

    private final Map<UUID, Long> lastActivity = new HashMap<>();
    private final Map<UUID, Long> afkSince = new HashMap<>();

    public AfkModule(PindaFramework plugin) {
        super(plugin, "afk");
    }

    @Override
    protected void onEnable() {
        listen(this);
        command(new AfkCommand(plugin, this));
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            lastActivity.put(player.getUniqueId(), now);
        }
        repeat(this::check, 100L, 100L);
    }

    @Override
    protected void onDisable() {
        for (UUID uuid : Set.copyOf(afkSince.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.playerListName(null);
            }
        }
        afkSince.clear();
        lastActivity.clear();
    }

    public boolean isAfk(Player player) {
        return afkSince.containsKey(player.getUniqueId());
    }

    /**
     * Zet iemand AFK of haalt hem uit AFK, met melding aan iedereen (of alleen aan hemzelf
     * als broadcast uit staat of de speler onzichtbaar is).
     */
    public void setAfk(Player player, boolean afk, String reason) {
        if (afk == isAfk(player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (afk) {
            afkSince.put(uuid, System.currentTimeMillis());
            if (config().getBoolean("tab-suffix", true)) {
                Component suffix = plugin.lang().component(plugin.lang().defaultLanguage(), "afk.tab-suffix");
                player.playerListName(player.displayName().append(suffix));
            }
        } else {
            afkSince.remove(uuid);
            lastActivity.put(uuid, System.currentTimeMillis());
            player.playerListName(null);
        }

        String key = afk ? (reason == null || reason.isBlank() ? "afk.now-afk" : "afk.now-afk-reason") : "afk.no-longer-afk";
        TagResolver[] resolvers = {Text.p("player", player.getName()), Text.p("reason", reason == null ? "" : reason)};
        boolean vanished = plugin.players().get(player).getBoolean(StaffModule.VANISHED_SETTING, false);
        if (config().getBoolean("broadcast", true) && !vanished) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                plugin.lang().send(online, key, resolvers);
            }
        } else {
            plugin.lang().send(player, key, resolvers);
        }
    }

    /** De speler deed iets: haal hem uit AFK en reset de timer. */
    public void activity(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        if (isAfk(player)) {
            setAfk(player, false, null);
        }
    }

    private void check() {
        long now = System.currentTimeMillis();
        long autoAfter = Math.max(0, config().getLong("auto-afk-minutes", 5)) * 60_000L;
        long kickAfter = Math.max(0, config().getLong("kick-after-minutes", 0)) * 60_000L;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Long since = afkSince.get(uuid);
            if (since == null) {
                long last = lastActivity.computeIfAbsent(uuid, ignored -> now);
                if (autoAfter > 0 && now - last >= autoAfter) {
                    setAfk(player, true, null);
                }
            } else if (kickAfter > 0 && now - since >= kickAfter && !player.hasPermission(KICK_EXEMPT)) {
                player.kick(plugin.lang().component(player, "afk.kick"));
            }
        }
    }

    // ----------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.hasChangedOrientation() || (event.hasChangedBlock() && !player.isInsideVehicle())) {
            activity(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                activity(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!AFK_COMMANDS.contains(label)) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
        afkSince.remove(event.getPlayer().getUniqueId());
    }
}
