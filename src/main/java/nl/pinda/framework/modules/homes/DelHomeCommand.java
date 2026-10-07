package nl.pinda.framework.modules.homes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /delhome &lt;naam&gt;: verwijdert een home. Beheerders: /delhome speler:naam */
public final class DelHomeCommand extends PindaCommand {

    private final HomesModule module;

    public DelHomeCommand(PindaFramework plugin, HomesModule module) {
        super(plugin, "delhome", "Verwijder een home", HomesModule.USE, "deletehome", "removehome");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            plugin.lang().send(sender, "homes.usage-delhome");
            return;
        }

        if (args[0].contains(":")) {
            if (!checkPermission(sender, HomesModule.OTHERS_DELETE)) {
                return;
            }
            String[] parts = args[0].split(":", 2);
            String name = parts[1].toLowerCase(Locale.ROOT);
            module.withOwnerHomes(sender, parts[0], (owner, homes) -> {
                if (!homes.containsKey(name)) {
                    plugin.lang().send(sender, "homes.others-not-found",
                            Text.p("player", owner.name()), Text.p("home", name));
                    plugin.theme().play(sender, "error");
                    return;
                }
                module.deleteHome(owner.uuid(), name);
                plugin.lang().send(sender, "homes.others-deleted",
                        Text.p("player", owner.name()), Text.p("home", name));
                plugin.theme().play(sender, "success");
            });
            return;
        }

        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        Map<String, Home> homes = module.homes(player);
        if (!homes.containsKey(name)) {
            plugin.lang().send(player, "homes.not-found", Text.p("home", name));
            plugin.theme().play(player, "error");
            return;
        }
        module.deleteHome(player.getUniqueId(), name);
        plugin.lang().send(player, "homes.deleted", Text.p("home", name));
        plugin.theme().play(player, "success");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) != 0 || !(sender instanceof Player player)) {
            return List.of();
        }
        return new ArrayList<>(module.homes(player).keySet());
    }
}
