package nl.pinda.framework.modules.homes;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.player.KnownPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /homes [speler]: opent het homes-menu (van jezelf, of van een ander voor beheerders). */
public final class HomesCommand extends PindaCommand {

    private final HomesModule module;

    public HomesCommand(PindaFramework plugin, HomesModule module) {
        super(plugin, "homes", "Bekijk je homes", HomesModule.USE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length > 0 && !args[0].isBlank() && !args[0].equalsIgnoreCase(player.getName())) {
            if (!checkPermission(player, HomesModule.OTHERS)) {
                return;
            }
            module.withOwnerHomes(player, args[0], (owner, homes) -> {
                new HomesMenu(plugin, player, module, owner, module.sorted(homes), 0).open();
                plugin.theme().play(player, "menu-open");
            });
            return;
        }
        new HomesMenu(plugin, player, module, new KnownPlayer(player.getUniqueId(), player.getName()),
                module.sorted(module.homes(player)), 0).open();
        plugin.theme().play(player, "menu-open");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender.hasPermission(HomesModule.OTHERS)) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
