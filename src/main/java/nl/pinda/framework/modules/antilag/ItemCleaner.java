package nl.pinda.framework.modules.antilag;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.economy.EconomyModule;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;

/**
 * Ruimt losse items op de grond op, met een aftelling vooraf. Waardevolle items, items met een
 * eigen naam, geld en net gevallen spullen (bijv. na doodgaan) blijven liggen.
 */
final class ItemCleaner {

    private final PindaFramework plugin;
    private final AntilagModule module;
    private final Set<Integer> warned = new HashSet<>();
    private long nextClear;
    private long countdownEnd;
    private long lastClear;
    private int lastCount = -1;

    ItemCleaner(PindaFramework plugin, AntilagModule module) {
        this.plugin = plugin;
        this.module = module;
        reschedule();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection section = module.settings().getConfigurationSection("clear-items");
        return section != null ? section : module.settings().createSection("clear-items");
    }

    boolean autoEnabled() {
        return cfg().getBoolean("enabled", true);
    }

    long intervalMillis() {
        return Math.max(1, cfg().getLong("interval-minutes", 15)) * 60_000L;
    }

    /** Na een herlaadactie of opruimbeurt: de volgende automatische beurt opnieuw plannen. */
    void reschedule() {
        nextClear = System.currentTimeMillis() + intervalMillis();
    }

    /** Wanneer de volgende opruimbeurt is (0 = geen). */
    long nextClear() {
        if (countdownEnd > 0) {
            return countdownEnd;
        }
        return autoEnabled() ? nextClear : 0;
    }

    boolean counting() {
        return countdownEnd > 0;
    }

    long lastClear() {
        return lastClear;
    }

    int lastCount() {
        return lastCount;
    }

    private List<Integer> warnings() {
        List<Integer> list = cfg().getIntegerList("warnings");
        return list.isEmpty() ? List.of(60, 30, 10, 5, 4, 3, 2, 1) : list;
    }

    /** Elke seconde: aftellen, waarschuwen en opruimen. */
    void tick() {
        long now = System.currentTimeMillis();
        if (countdownEnd == 0) {
            if (!autoEnabled()) {
                return;
            }
            int first = 0;
            for (int seconds : warnings()) {
                first = Math.max(first, seconds);
            }
            if (now >= nextClear - first * 1000L) {
                countdownEnd = Math.max(nextClear, now + 1000L);
                warned.clear();
            } else {
                return;
            }
        }
        int left = (int) Math.ceil((countdownEnd - now) / 1000.0);
        if (left <= 0) {
            countdownEnd = 0;
            int removed = clear();
            announce(removed);
            reschedule();
            return;
        }
        if (warned.add(left) && (warnings().contains(left) || warned.size() == 1)) {
            warn(left);
        }
    }

    /** Start een aftelling (vanaf /lag clear, het paneel of bij lag). False als er al een loopt. */
    boolean start(int seconds) {
        if (countdownEnd > 0) {
            return false;
        }
        countdownEnd = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        warned.clear();
        tick();
        return true;
    }

    /** Meteen opruimen, zonder aftelling (een lopende aftelling stopt). */
    int clearNow() {
        countdownEnd = 0;
        int removed = clear();
        announce(removed);
        reschedule();
        return removed;
    }

    private void warn(int seconds) {
        boolean chat = seconds > 5;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.lang().send(player, chat ? "antilag.clear-warning" : "antilag.clear-countdown", Text.p("seconds", seconds));
            if (!chat) {
                plugin.theme().play(player, "countdown");
            }
        }
    }

    private void announce(int removed) {
        if (removed <= 0 || !cfg().getBoolean("announce-result", true)) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.lang().send(player, "antilag.clear-done", Text.p("count", removed));
        }
        plugin.getLogger().info(removed + " losse items opgeruimd.");
    }

    /** Ruimt nu meteen op en geeft terug hoeveel er weg is. */
    int clear() {
        ConfigurationSection cfg = cfg();
        long minAge = Math.max(0, cfg.getLong("min-age-seconds", 60)) * 20L;
        boolean keepNamed = cfg.getBoolean("keep-named", true);
        boolean arrows = cfg.getBoolean("arrows", true);
        List<String> keep = cfg.getStringList("keep");
        Set<String> disabled = new HashSet<>();
        for (String world : cfg.getStringList("disabled-worlds")) {
            disabled.add(world.toLowerCase(Locale.ROOT));
        }
        EconomyModule economy = plugin.modules().get(EconomyModule.class);
        boolean money = economy != null && economy.isEnabled();

        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            if (disabled.contains(world.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (item.getTicksLived() < minAge || item.isUnlimitedLifetime() || item.getPickupDelay() > 6000) {
                    continue; // net gevallen, of een etalage-item van een andere plugin
                }
                if (keepNamed && (item.customName() != null || item.getItemStack().hasItemMeta()
                        && item.getItemStack().getItemMeta().hasDisplayName())) {
                    continue;
                }
                if (money && economy.service().moneyValue(item.getItemStack()) != null) {
                    continue; // geld laten we nooit verdwijnen
                }
                if (matches(keep, item.getItemStack().getType().name())) {
                    continue;
                }
                item.remove();
                removed++;
            }
            if (arrows) {
                for (AbstractArrow arrow : world.getEntitiesByClass(AbstractArrow.class)) {
                    if (!(arrow instanceof Trident) && arrow.isInBlock() && arrow.getTicksLived() >= minAge) {
                        arrow.remove();
                        removed++;
                    }
                }
            }
        }
        lastClear = System.currentTimeMillis();
        lastCount = removed;
        return removed;
    }

    /** Past een naam bij een van de patronen? Een * mag overal staan (DIAMOND*, *_SHULKER_BOX). */
    static boolean matches(List<String> patterns, String name) {
        for (String pattern : patterns) {
            String regex = "\\Q" + pattern.trim().toUpperCase(Locale.ROOT).replace("*", "\\E.*\\Q") + "\\E";
            if (name.matches(regex)) {
                return true;
            }
        }
        return false;
    }
}
