package nl.pinda.framework.modules.homes;

import java.util.Map;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /sethome [naam]: zet een home op je huidige plek (of verplaatst een bestaande). */
public final class SetHomeCommand extends PindaCommand {

    private final HomesModule module;

    public SetHomeCommand(PindaFramework plugin, HomesModule module) {
        super(plugin, "sethome", "Zet een home op je huidige plek", HomesModule.USE, "createhome");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        String input = args.length == 0 || args[0].isBlank() ? "home" : args[0];
        String name = HomesModule.normalizeName(input);
        if (name == null) {
            plugin.lang().send(player, "homes.invalid-name");
            plugin.theme().play(player, "error");
            return;
        }
        if (module.isWorldBlocked(player.getWorld())) {
            plugin.lang().send(player, "homes.world-blocked");
            plugin.theme().play(player, "error");
            return;
        }

        Map<String, Home> homes = module.homes(player);
        boolean exists = homes.containsKey(name);
        int limit = module.limit(player);
        if (!exists && homes.size() >= limit) {
            plugin.lang().send(player, "homes.limit-reached", Text.p("limit", HomesModule.formatLimit(limit)));
            plugin.theme().play(player, "error");
            return;
        }

        int count = exists ? homes.size() : homes.size() + 1;
        module.saveHome(Home.of(player.getUniqueId(), name, player.getLocation()));
        plugin.lang().send(player, exists ? "homes.updated" : "homes.set",
                Text.p("home", name),
                Text.p("count", count),
                Text.p("limit", HomesModule.formatLimit(limit)));
        plugin.theme().play(player, "success");
    }
}
