package nl.pinda.framework.modules.tips;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.player.PlayerSetting;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Stuurt spelers af en toe een tip in hun eigen taal. Spelers kunnen tips uitzetten
 * in /instellingen. De tips staan in de taalbestanden onder 'tips.list'.
 */
public final class TipsModule extends PindaModule {

    public static final String SETTING = "tips";

    private int counter;

    public TipsModule(PindaFramework plugin) {
        super(plugin, "tips");
    }

    @Override
    protected void onEnable() {
        registerSetting();
        schedule();
    }

    @Override
    protected void onDisable() {
        plugin.settings().unregister(SETTING);
    }

    @Override
    protected void onReload() {
        cancelTasks();
        registerSetting();
        schedule();
    }

    private void registerSetting() {
        plugin.settings().register(new PlayerSetting(SETTING,
                config().getBoolean("default-enabled", true),
                Material.WRITABLE_BOOK,
                config().getBoolean("show-in-setup", true)));
    }

    private void schedule() {
        long ticks = Math.max(30L, config().getLong("interval-seconds", 300L)) * 20L;
        repeat(this::broadcast, ticks, ticks);
    }

    private void broadcast() {
        int index = counter++;
        boolean random = config().getBoolean("random", false);
        boolean sound = config().getBoolean("sound", true);
        LanguageManager lang = plugin.lang();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.settings().isEnabled(player, SETTING)) {
                continue;
            }
            String code = lang.languageOf(player);
            List<String> tips = lang.rawList(code, "tips.list");
            if (tips.isEmpty()) {
                continue;
            }
            String tip = random
                    ? tips.get(ThreadLocalRandom.current().nextInt(tips.size()))
                    : tips.get(Math.floorMod(index, tips.size()));
            Component tipComponent = lang.parse(tip);
            player.sendMessage(lang.component(code, "tips.format", Text.c("tip", tipComponent)));
            if (sound) {
                plugin.theme().play(player, "tip");
            }
        }
    }
}
