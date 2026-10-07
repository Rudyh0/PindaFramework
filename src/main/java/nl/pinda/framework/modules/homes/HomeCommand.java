package nl.pinda.framework.modules.homes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.player.KnownPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /home [naam]: teleporteert naar een home.
 * Zonder naam: naar "home", of naar je enige home, of het menu als je er meer hebt.
 * Beheerders: /home speler:naam
 */
public final class HomeCommand extends PindaCommand {

    private final HomesModule module;

    public HomeCommand(PindaFramework plugin, HomesModule module) {
        super(plugin, "home", "Teleporteer naar een home", HomesModule.USE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }

        if (args.length > 0 && args[0].contains(":")) {
            if (!checkPermission(player, HomesModule.OTHERS)) {
                return;
            }
            String[] parts = args[0].split(":", 2);
            String name = parts[1].toLowerCase(Locale.ROOT);
            module.withOwnerHomes(player, parts[0], (owner, homes) -> {
                Home home = homes.get(name);
                if (home == null) {
                    plugin.lang().send(player, "homes.others-not-found",
                            Text.p("player", owner.name()), Text.p("home", name));
                    plugin.theme().play(player, "error");
                    return;
                }
                module.teleport(player, home, owner);
            });
            return;
        }

        Map<String, Home> homes = module.homes(player);
        if (homes.isEmpty()) {
            plugin.lang().send(player, "homes.none");
            return;
        }

        if (args.length == 0 || args[0].isBlank()) {
            Home home = homes.get("home");
            if (home == null && homes.size() == 1) {
                home = homes.values().iterator().next();
            }
            if (home == null) {
                new HomesMenu(plugin, player, module, new KnownPlayer(player.getUniqueId(), player.getName()),
                        module.sorted(homes), 0).open();
                plugin.theme().play(player, "menu-open");
                return;
            }
            module.teleport(player, home, null);
            return;
        }

        String name = args[0].toLowerCase(Locale.ROOT);
        Home home = homes.get(name);
        if (home == null) {
            plugin.lang().send(player, "homes.not-found", Text.p("home", name));
            plugin.theme().play(player, "error");
            return;
        }
        module.teleport(player, home, null);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) != 0 || !(sender instanceof Player player)) {
            return List.of();
        }
        return new ArrayList<>(module.homes(player).keySet());
    }
}
