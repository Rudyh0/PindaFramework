package nl.pinda.framework.modules.world;

import java.util.List;
import java.util.Locale;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;

/**
 * /dragon - hoe het met de ender dragon gaat
 * /dragon respawn - de draak nu terug laten komen
 */
final class DragonCommand extends PindaCommand {

    private final WorldControlModule module;

    DragonCommand(PindaFramework plugin, WorldControlModule module) {
        super(plugin, "dragon", "De ender dragon", WorldControlModule.DRAGON, "draak", "enderdragon");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0) {
            status(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "respawn", "terug", "spawn" -> respawn(sender);
            default -> plugin.lang().send(sender, "world.dragon.usage");
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? List.of("respawn") : List.of();
    }

    private void status(CommandSender sender) {
        DragonControl.Status status = module.dragonStatus();
        if (!status.end()) {
            plugin.lang().send(sender, "world.dragon.no-end");
            return;
        }
        String code = plugin.lang().languageOf(sender);
        long now = System.currentTimeMillis();
        if (status.alive()) {
            if (status.loaded()) {
                plugin.lang().send(sender, "world.dragon.status-alive",
                        Text.p("health", Math.round(status.health())), Text.p("max", Math.round(status.maxHealth())));
            } else {
                plugin.lang().send(sender, "world.dragon.status-alive-unloaded");
            }
        } else if (status.respawning()) {
            plugin.lang().send(sender, "world.dragon.status-respawning");
        } else {
            String ago = module.duration(now - status.killedAt(), code);
            if (status.killer() != null) {
                plugin.lang().send(sender, "world.dragon.status-dead", Text.p("time", ago), Text.p("player", status.killer()));
            } else {
                plugin.lang().send(sender, "world.dragon.status-dead-unknown", Text.p("time", ago));
            }
            if (status.respawnAt() == 0) {
                plugin.lang().send(sender, "world.dragon.status-respawn-off");
            } else if (status.respawnAt() > now) {
                plugin.lang().send(sender, "world.dragon.status-respawn-in", Text.p("time", module.duration(status.respawnAt() - now, code)));
            } else {
                plugin.lang().send(sender, "world.dragon.status-respawn-ready");
            }
        }
        if (status.kills() > 0) {
            plugin.lang().send(sender, "world.dragon.status-kills", Text.p("count", status.kills()));
        }
    }

    private void respawn(CommandSender sender) {
        String key = switch (module.respawnDragon()) {
            case STARTED -> "world.dragon.respawn-started";
            case WAITING -> "world.dragon.respawn-waiting";
            case ALIVE -> "world.dragon.respawn-alive";
            case BUSY -> "world.dragon.respawn-busy";
            case NO_END -> "world.dragon.no-end";
            case FAILED -> "world.dragon.respawn-failed";
        };
        plugin.lang().send(sender, key);
    }
}
