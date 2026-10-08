package nl.pinda.framework.modules.broadcasts;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.integration.Placeholders;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.entity.Player;

/**
 * Automatische aankondigingen: om de zoveel minuten een bericht voor iedereen, uit een lijst
 * die je in het paneel of in modules/broadcasts.yml beheert.
 */
public final class BroadcastsModule extends PindaModule {

    private int counter;
    private long nextAt;

    public BroadcastsModule(PindaFramework plugin) {
        super(plugin, "broadcasts");
    }

    @Override
    protected void onEnable() {
        schedule();
    }

    @Override
    protected void onReload() {
        cancelTasks();
        schedule();
    }

    private void schedule() {
        long ticks = intervalMinutes() * 60L * 20L;
        nextAt = System.currentTimeMillis() + ticks * 50L;
        repeat(this::next, ticks, ticks);
    }

    public int intervalMinutes() {
        return Math.max(1, config().getInt("interval-minutes", 10));
    }

    /** Wanneer de volgende automatische aankondiging komt. */
    public long nextAt() {
        return nextAt;
    }

    public List<String> messages() {
        return config().getStringList("messages");
    }

    private void next() {
        nextAt = System.currentTimeMillis() + intervalMinutes() * 60_000L;
        List<String> messages = messages();
        int online = plugin.getServer().getOnlinePlayers().size();
        if (messages.isEmpty() || online == 0 || online < config().getInt("min-players", 1)) {
            return;
        }
        int index = config().getBoolean("random", false)
                ? ThreadLocalRandom.current().nextInt(messages.size())
                : Math.floorMod(counter++, messages.size());
        send(messages.get(index));
    }

    /** Stuurt een aankondiging naar iedereen (en de console). */
    public void send(String message) {
        LanguageManager lang = plugin.lang();
        int online = plugin.getServer().getOnlinePlayers().size();
        int max = plugin.getServer().getMaxPlayers();
        boolean sound = config().getBoolean("sound", true);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(render(player, player.getName(), lang.languageOf(player), message, online, max));
            if (sound) {
                plugin.theme().play(player, "tip");
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(render(null, "Console", lang.defaultLanguage(), message, online, max));
    }

    /** Hoe een aankondiging eruitziet voor een speler (of null voor de console en het paneel). */
    public Component render(Player player, String name, String code, String message, int online, int max) {
        LanguageManager lang = plugin.lang();
        String text = player == null ? message : Placeholders.apply(player, message);
        Component body = lang.parse(text, Text.p("player", name),
                Text.p("online", online), Text.p("max", max));
        return lang.component(code, "broadcasts.format", Text.c("message", body));
    }
}
