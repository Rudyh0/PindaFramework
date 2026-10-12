package nl.pinda.framework.modules.bossbar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /bossbars toggle - je eigen bossbars aan of uit
 * /bossbars show &lt;bossbar&gt; [speler|*] [seconden] - een bossbar los tonen (bijv. voor een event)
 * /bossbars hide &lt;bossbar|*&gt; [speler|*] - los getoonde bossbars weghalen
 * /bossbars list - alle bossbars en wisselingen
 */
final class BossBarCommand extends PindaCommand {

    private final BossBarModule module;

    BossBarCommand(PindaFramework plugin, BossBarModule module) {
        super(plugin, "bossbars", "Bossbars bovenin het scherm", BossBarModule.TOGGLE, "bbar", "bb");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "toggle", "aan", "uit" -> toggle(sender);
            case "show", "toon" -> {
                if (checkPermission(sender, BossBarModule.ADMIN)) {
                    show(sender, args);
                }
            }
            case "hide", "verberg" -> {
                if (checkPermission(sender, BossBarModule.ADMIN)) {
                    hide(sender, args);
                }
            }
            case "list", "lijst" -> {
                if (checkPermission(sender, BossBarModule.ADMIN)) {
                    list(sender);
                }
            }
            default -> plugin.lang().send(sender, sender.hasPermission(BossBarModule.ADMIN) ? "bossbar.usage-admin" : "bossbar.usage");
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission(BossBarModule.ADMIN);
        if (args.length <= 1) {
            return admin ? List.of("toggle", "show", "hide", "list") : List.of("toggle");
        }
        if (!admin) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("show") || sub.equals("hide"))) {
            List<String> ids = new ArrayList<>(module.bars().keySet());
            if (sub.equals("hide")) {
                ids.add("*");
            }
            return ids;
        }
        if (args.length == 3 && (sub.equals("show") || sub.equals("hide"))) {
            List<String> names = new ArrayList<>();
            names.add("*");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                names.add(player.getName());
            }
            return names;
        }
        if (args.length == 4 && sub.equals("show")) {
            return List.of("10", "30", "60", "300");
        }
        return List.of();
    }

    private void toggle(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        boolean enabled = plugin.settings().toggle(player, BossBarModule.SETTING);
        plugin.lang().send(player, enabled ? "bossbar.toggled-on" : "bossbar.toggled-off");
    }

    /** Spelers bij "speler", "*" of niets (= iedereen). Null als de speler niet online is (met melding). */
    private Collection<? extends Player> targets(CommandSender sender, String name) {
        if (name == null || name.equals("*") || name.equalsIgnoreCase("alle") || name.equalsIgnoreCase("all")) {
            return plugin.getServer().getOnlinePlayers();
        }
        Player player = plugin.getServer().getPlayerExact(name);
        if (player == null) {
            plugin.lang().send(sender, "general.player-not-found", Text.p("player", name));
            return null;
        }
        return List.of(player);
    }

    private boolean known(CommandSender sender, String id) {
        if (module.bars().containsKey(id)) {
            return true;
        }
        plugin.lang().send(sender, "bossbar.unknown", Text.p("bar", id), Text.p("bars", String.join(", ", module.bars().keySet())));
        return false;
    }

    private void show(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.lang().send(sender, "bossbar.usage-admin");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!known(sender, id)) {
            return;
        }
        Collection<? extends Player> targets = targets(sender, args.length >= 3 ? args[2] : null);
        if (targets == null) {
            return;
        }
        int seconds = 0;
        if (args.length >= 4) {
            try {
                seconds = Math.max(1, Math.min(86_400, Integer.parseInt(args[3])));
            } catch (NumberFormatException e) {
                plugin.lang().send(sender, "bossbar.usage-admin");
                return;
            }
        }
        int count = module.show(id, targets, seconds);
        plugin.lang().send(sender, "bossbar.shown", Text.p("bar", id), Text.p("count", count));
    }

    private void hide(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.lang().send(sender, "bossbar.usage-admin");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!id.equals("*") && !known(sender, id)) {
            return;
        }
        Collection<? extends Player> targets = targets(sender, args.length >= 3 ? args[2] : null);
        if (targets == null) {
            return;
        }
        int count = module.hide(id.equals("*") ? null : id, targets);
        plugin.lang().send(sender, "bossbar.hidden", Text.p("bar", id), Text.p("count", count));
    }

    private void list(CommandSender sender) {
        plugin.lang().send(sender, "bossbar.list-header", Text.p("count", module.bars().size()));
        for (BarDefinition bar : module.bars().values()) {
            plugin.lang().send(sender, "bossbar.list-bar", Text.p("bar", bar.id()),
                    Text.p("color", bar.color().name().toLowerCase(Locale.ROOT)), Text.p("frames", bar.frames().size()),
                    Text.p("seconds", BarDefinition.seconds(bar.durationTicks())));
        }
        for (Rotation rotation : module.rotations()) {
            plugin.lang().send(sender, rotation.enabled() ? "bossbar.list-rotation" : "bossbar.list-rotation-off",
                    Text.p("name", rotation.name()), Text.p("bars", String.join(" → ", rotation.bars())));
        }
    }
}
