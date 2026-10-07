package nl.pinda.framework.modules.motd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

/** Een eigen MOTD (het bericht in de serverlijst), met opmaak en meerdere varianten. */
public final class MotdModule extends PindaModule implements Listener {

    public MotdModule(PindaFramework plugin) {
        super(plugin, "motd");
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    /** Alle MOTD's uit de config. */
    public List<String> motds() {
        return config().getStringList("motds");
    }

    /** Een MOTD als component, met placeholders ingevuld. */
    public Component render(String raw, int online, int max) {
        return plugin.lang().parse(raw.replace("\\n", "<newline>"), Text.p("online", online), Text.p("max", max));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(ServerListPingEvent event) {
        if (!config().getBoolean("enabled", true)) {
            return;
        }
        int shownMax = config().getInt("shown-max-players", -1);
        if (shownMax >= 0) {
            event.setMaxPlayers(shownMax);
        }
        List<String> motds = motds();
        if (!motds.isEmpty()) {
            String raw = motds.get(ThreadLocalRandom.current().nextInt(motds.size()));
            event.motd(render(raw, event.getNumPlayers(), event.getMaxPlayers()));
        }
        if (config().getBoolean("hide-players", false) && event instanceof PaperServerListPingEvent paper) {
            paper.setHidePlayers(true);
        }
    }
}
