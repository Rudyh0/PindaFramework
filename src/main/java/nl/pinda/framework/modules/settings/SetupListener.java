package nl.pinda.framework.modules.settings;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.player.PindaPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Kiest bij de eerste join een passende taal en opent het setupmenu. */
public final class SetupListener implements Listener {

    private final PindaFramework plugin;
    private final SettingsModule module;

    public SetupListener(PindaFramework plugin, SettingsModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PindaPlayer data = plugin.players().get(player);

        if (data.language() == null && module.detectClientLanguage()) {
            data.language(plugin.lang().detect(player, module.unknownClientLanguage()));
            plugin.players().save(data);
        }

        if (data.setupCompleted() || !module.setupEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || plugin.players().get(player).setupCompleted()) {
                return;
            }
            new SettingsMenu(plugin, player, SettingsMenu.Mode.SETUP).open();
            plugin.theme().play(player, "menu-open");
        }, module.setupDelayTicks());
    }
}
