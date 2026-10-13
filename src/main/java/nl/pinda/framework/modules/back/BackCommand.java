package nl.pinda.framework.modules.back;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.backpack.BackpackModule;
import nl.pinda.framework.teleport.TeleportRequest;
import nl.pinda.framework.teleport.TeleportType;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /back: terug naar je vorige plek.
 *
 * <p>Ga je terug naar waar je doodging terwijl je rugtas daar nog ligt, dan kost dat je rugtas
 * (instelbaar in back.yml). Eerst krijg je een waarschuwing; met /back bevestig (of binnen 20
 * seconden nog een keer /back) ga je toch, en is de rugtas meteen weg.
 */
public final class BackCommand extends PindaCommand {

    private static final List<String> CONFIRM = List.of("bevestig", "confirm", "ja", "yes");

    private final BackModule module;

    public BackCommand(PindaFramework plugin, BackModule module) {
        super(plugin, "back", "Ga terug naar je vorige plek", BackModule.USE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Location last = module.last(player);
        if (last == null || last.getWorld() == null) {
            plugin.lang().send(player, "back.none");
            plugin.theme().play(player, "error");
            return;
        }
        BackpackModule.DroppedInfo bag = module.droppedBackpack(player);
        if (bag != null) {
            boolean explicit = args.length > 0 && CONFIRM.contains(args[0].toLowerCase(Locale.ROOT));
            if (!module.confirmWarning(player, explicit)) {
                warn(player, bag);
                return;
            }
        }
        UUID uuid = player.getUniqueId();
        boolean costsBag = bag != null;
        plugin.teleports().teleport(new TeleportRequest(player, player, TeleportType.BACK, () -> last, true, () -> {
            if (costsBag) {
                // Pas als je er echt bent: lukt het teleporteren niet, dan houd je je rugtas.
                BackpackModule backpacks = plugin.modules().get(BackpackModule.class);
                if (backpacks != null && backpacks.isEnabled() && backpacks.destroyDropped(uuid) > 0) {
                    plugin.lang().send(player, "back.backpack-lost");
                }
            }
            plugin.lang().send(player, "back.teleported");
        }));
    }

    private void warn(Player player, BackpackModule.DroppedInfo bag) {
        BackpackModule backpacks = plugin.modules().get(BackpackModule.class);
        String cash = bag.cash() > 0 && backpacks != null ? backpacks.cashText(bag.cash()) : "";
        plugin.lang().send(player, cash.isEmpty() ? "back.backpack-warning" : "back.backpack-warning-cash",
                Text.p("items", bag.items()), Text.p("amount", cash), Text.p("seconds", BackModule.CONFIRM_MILLIS / 1000));
        plugin.theme().play(player, "error");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender instanceof Player player && module.droppedBackpack(player) != null) {
            return List.of("bevestig");
        }
        return List.of();
    }
}
