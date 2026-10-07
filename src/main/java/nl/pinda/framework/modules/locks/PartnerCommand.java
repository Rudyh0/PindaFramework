package nl.pinda.framework.modules.locks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /partner: het partnermenu. Of direct:
 * /partner &lt;speler&gt; (verzoek sturen), /partner accept|deny|remove &lt;speler&gt;, /partner list.
 */
public final class PartnerCommand extends PindaCommand {

    private final LockService service;

    public PartnerCommand(PindaFramework plugin, LockService service) {
        super(plugin, "partner", "Partners met toegang tot elkaars kisten en deuren", LockModule.PARTNER, "partners");
        this.service = service;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0 || args[0].isBlank()) {
            new PartnerMenu(plugin, player, service, null).open();
            plugin.theme().play(player, "menu-open");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "accept", "accepteer", "accepteren" -> withRequester(player, args, from -> Partners.accept(plugin, service, player, from));
            case "deny", "weiger", "weigeren" -> withRequester(player, args, from -> Partners.deny(plugin, service, player, from));
            case "remove", "verwijder", "verwijderen" -> {
                if (args.length < 2) {
                    plugin.lang().send(player, "partner.usage");
                    return;
                }
                UUID partner = findPartner(player, args[1]);
                if (partner == null) {
                    plugin.lang().send(player, "partner.not-partners", Text.p("player", args[1]));
                    plugin.theme().play(player, "error");
                    return;
                }
                Partners.remove(plugin, service, player, partner);
            }
            case "list", "lijst" -> {
                List<String> names = new ArrayList<>();
                for (UUID partner : service.partners(player.getUniqueId())) {
                    names.add(Partners.name(plugin, partner));
                }
                if (names.isEmpty()) {
                    plugin.lang().send(player, "partner.list-empty");
                } else {
                    plugin.lang().send(player, "partner.list", Text.p("players", String.join(", ", names)));
                }
            }
            default -> {
                Player target = findPlayerOrFail(player, args[0]);
                if (target != null) {
                    Partners.request(plugin, service, player, target);
                }
            }
        }
    }

    private void withRequester(Player player, String[] args, java.util.function.Consumer<UUID> action) {
        List<UUID> incoming = service.incomingRequests(player.getUniqueId());
        if (incoming.isEmpty()) {
            plugin.lang().send(player, "partner.no-requests");
            plugin.theme().play(player, "error");
            return;
        }
        if (args.length < 2) {
            action.accept(incoming.get(incoming.size() - 1));
            return;
        }
        for (UUID uuid : incoming) {
            if (Partners.name(plugin, uuid).equalsIgnoreCase(args[1])) {
                action.accept(uuid);
                return;
            }
        }
        plugin.lang().send(player, "partner.no-request", Text.p("player", args[1]));
        plugin.theme().play(player, "error");
    }

    private UUID findPartner(Player player, String name) {
        for (UUID partner : service.partners(player.getUniqueId())) {
            if (Partners.name(plugin, partner).equalsIgnoreCase(name)) {
                return partner;
            }
        }
        return null;
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        int index = argIndex(args);
        if (index == 0) {
            List<String> options = new ArrayList<>(List.of("accept", "deny", "remove", "list"));
            options.addAll(visiblePlayers(sender).stream().filter(name -> !name.equals(sender.getName())).toList());
            return options;
        }
        if (index == 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            if (sub.startsWith("acc") || sub.startsWith("den") || sub.startsWith("wei")) {
                for (UUID uuid : service.incomingRequests(player.getUniqueId())) {
                    names.add(Partners.name(plugin, uuid));
                }
            } else if (sub.startsWith("rem") || sub.startsWith("ver")) {
                for (UUID uuid : service.partners(player.getUniqueId())) {
                    names.add(Partners.name(plugin, uuid));
                }
            }
            return names;
        }
        return List.of();
    }
}
