package nl.pinda.framework.modules.sleep;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.afk.AfkModule;
import nl.pinda.framework.modules.staff.StaffModule;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Slapen zoals BetterSleeping (zonder buffs): een instelbaar percentage spelers moet slapen,
 * met meldingen, en de nacht spoelt mooi door.
 */
public final class SleepModule extends PindaModule implements Listener {

    public static final String EXEMPT = "pinda.sleep.exempt";
    private static final String RULE_KEY = "sleep-rule.";

    /** Hoeveel spelers er slapen en hoeveel er nodig zijn. */
    public record Count(int sleeping, int needed, int counted) {
    }

    private final Map<UUID, BukkitTask> skipping = new HashMap<>();
    private GameRule<Integer> sleepRule;

    public SleepModule(PindaFramework plugin) {
        super(plugin, "sleep");
    }

    @Override
    protected void onEnable() {
        sleepRule = findSleepRule();
        listen(this);
        for (World world : plugin.getServer().getWorlds()) {
            applyRule(world);
        }
        repeat(this::tick, 20L, 10L);
    }

    @Override
    protected void onDisable() {
        for (BukkitTask task : skipping.values()) {
            task.cancel();
        }
        skipping.clear();
        for (World world : plugin.getServer().getWorlds()) {
            restoreRule(world);
        }
    }

    @Override
    protected void onReload() {
        for (World world : plugin.getServer().getWorlds()) {
            if (managed(world)) {
                applyRule(world);
            } else {
                restoreRule(world);
            }
        }
    }

    // ============================================================ instellingen

    /** Het percentage spelers dat moet slapen (0-100). */
    public int percentage() {
        return Math.max(0, Math.min(100, config().getInt("percentage", 50)));
    }

    /** Doet deze wereld mee? Alleen de overworld-achtige werelden, en niet uitgezet. */
    public boolean managed(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return false;
        }
        for (String name : config().getStringList("disabled-worlds")) {
            if (name.equalsIgnoreCase(world.getName())) {
                return false;
            }
        }
        return true;
    }

    // ============================================================ de vanilla-regel

    @SuppressWarnings("unchecked")
    private static GameRule<Integer> findSleepRule() {
        for (GameRule<?> rule : GameRule.values()) {
            String name = rule.getName().toLowerCase(Locale.ROOT).replace("_", "");
            if (name.equals("playerssleepingpercentage") && rule.getType() == Integer.class) {
                return (GameRule<Integer>) rule;
            }
        }
        return null;
    }

    /** Zet de vanilla-regel uit (101%), zodat alleen deze module de nacht overslaat. */
    private void applyRule(World world) {
        if (sleepRule == null || !managed(world)) {
            return;
        }
        Integer current = world.getGameRuleValue(sleepRule);
        if (current != null && current <= 100) {
            plugin.serverData().set(RULE_KEY + world.getName(), current);
            world.setGameRule(sleepRule, 101);
        }
    }

    /** Zet de vanilla-regel terug zoals hij was. */
    private void restoreRule(World world) {
        if (sleepRule == null) {
            return;
        }
        String saved = plugin.serverData().get(RULE_KEY + world.getName());
        if (saved == null) {
            return;
        }
        try {
            world.setGameRule(sleepRule, Integer.parseInt(saved));
        } catch (NumberFormatException ignored) {
            world.setGameRule(sleepRule, 100);
        }
        plugin.serverData().remove(RULE_KEY + world.getName());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        applyRule(event.getWorld());
    }

    // ============================================================ tellen

    /** Hoeveel spelers in deze wereld slapen en hoeveel er nodig zijn. */
    public Count count(World world) {
        AfkModule afk = enabled(AfkModule.class);
        StaffModule staff = enabled(StaffModule.class);
        boolean ignoreAfk = config().getBoolean("ignore.afk", true);
        boolean ignoreVanished = config().getBoolean("ignore.vanished", true);
        boolean ignoreCreative = config().getBoolean("ignore.creative", true);
        int sleeping = 0;
        int counted = 0;
        for (Player player : world.getPlayers()) {
            if (player.isSleeping()) {
                sleeping++;
                counted++;
                continue;
            }
            if (player.isSleepingIgnored() || player.hasPermission(EXEMPT)
                    || (ignoreAfk && afk != null && afk.isAfk(player))
                    || (ignoreVanished && staff != null && staff.isVanished(player))
                    || (ignoreCreative && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR))) {
                continue;
            }
            counted++;
        }
        int percentage = percentage();
        int needed = percentage <= 0 ? 1 : Math.max(1, (int) Math.ceil(counted * percentage / 100.0));
        return new Count(sleeping, needed, counted);
    }

    private <T extends PindaModule> T enabled(Class<T> type) {
        T module = plugin.modules().get(type);
        return module != null && module.isEnabled() ? module : null;
    }

    /** Wordt de nacht in deze wereld nu overgeslagen? */
    public boolean isSkipping(World world) {
        return skipping.containsKey(world.getUID());
    }

    // ============================================================ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (!managed(world)) {
            return;
        }
        // Een tick later, dan ligt de speler echt in bed en klopt de telling.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isSleeping() || isSkipping(world)) {
                return;
            }
            Count count = count(world);
            if (config().getBoolean("messages.enter-bed", true) && count.sleeping() < count.needed()) {
                broadcast(world, "sleep.enter", Text.p("player", player.getName()),
                        Text.p("sleeping", count.sleeping()), Text.p("needed", count.needed()));
            }
            check(world);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (!managed(world) || isSkipping(world) || !config().getBoolean("messages.leave-bed", true)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (isSkipping(world) || isDay(world)) {
                return;
            }
            Count count = count(world);
            broadcast(world, "sleep.leave", Text.p("player", player.getName()),
                    Text.p("sleeping", count.sleeping()), Text.p("needed", count.needed()));
        });
    }

    private static boolean isDay(World world) {
        long time = world.getTime();
        return time < 12542 && !world.isThundering();
    }

    // ============================================================ overslaan

    private void tick() {
        for (World world : plugin.getServer().getWorlds()) {
            if (managed(world) && !isSkipping(world)) {
                check(world);
            }
        }
    }

    private void check(World world) {
        Count count = count(world);
        if (count.sleeping() == 0) {
            return;
        }
        if (count.sleeping() >= count.needed()) {
            skip(world);
            return;
        }
        if (config().getBoolean("messages.progress", true)) {
            broadcast(world, "sleep.progress", Text.p("sleeping", count.sleeping()), Text.p("needed", count.needed()));
        }
    }

    /** Slaat de nacht (of het onweer) over in deze wereld. */
    public void skip(World world) {
        if (isSkipping(world)) {
            return;
        }
        boolean storm = world.isThundering() && world.getTime() < 12542;
        if (config().getBoolean("messages.skip", true)) {
            broadcast(world, storm ? "sleep.skipping-storm" : "sleep.skipping");
        }
        long target = (world.getFullTime() / 24000L + 1) * 24000L;
        if (!config().getBoolean("animation.enabled", true)) {
            world.setFullTime(target);
            finish(world);
            return;
        }
        long remaining = target - world.getFullTime();
        // Nooit langer dan ongeveer 6 seconden doorspoelen
        long speed = Math.max(Math.max(1, config().getInt("animation.speed", 100)), remaining / 120);
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long next = Math.min(target, world.getFullTime() + speed);
            world.setFullTime(next);
            if (next >= target) {
                holder[0].cancel();
                finish(world);
            }
        }, 1L, 1L);
        skipping.put(world.getUID(), holder[0]);
    }

    private void finish(World world) {
        skipping.remove(world.getUID());
        if (config().getBoolean("clear-weather", true)) {
            world.setStorm(false);
            world.setThundering(false);
            world.setWeatherDuration(0);
            world.setThunderDuration(0);
        }
        boolean everyone = config().getBoolean("reset-phantoms-for-everyone", false);
        for (Player player : world.getPlayers()) {
            if (player.isSleeping()) {
                player.wakeup(false);
            }
            if (everyone) {
                player.setStatistic(Statistic.TIME_SINCE_REST, 0);
            }
        }
        if (config().getBoolean("messages.skip", true)) {
            broadcast(world, "sleep.morning", Text.p("day", world.getFullTime() / 24000L + 1));
        }
    }

    private void broadcast(World world, String key, TagResolver... resolvers) {
        for (Player player : world.getPlayers()) {
            plugin.lang().send(player, key, resolvers);
        }
    }
}
