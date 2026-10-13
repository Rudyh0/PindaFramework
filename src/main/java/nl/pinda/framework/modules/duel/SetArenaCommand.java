package nl.pinda.framework.modules.duel;

import java.util.List;
import java.util.Locale;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /setarena: de 1v1-arena instellen, op waar je staat (en waar je heen kijkt).
 * <pre>
 * /setarena 1             plek van speler 1
 * /setarena 2             plek van speler 2
 * /setarena kijken        plek voor toeschouwers
 * /setarena gebied        het beschermde gebied: je selectie met de WorldEdit-bijl
 * /setarena hoek1|hoek2   het beschermde gebied zonder WorldEdit: twee hoeken (de hele hoogte)
 * /setarena               laat zien wat er is ingesteld
 * /setarena wis &lt;1|2|kijken|gebied&gt;
 * </pre>
 */
final class SetArenaCommand extends PindaCommand {

    /** Groter dan dit (blokken, in de breedte of lengte) is vast een vergissing. */
    private static final int MAX_SIZE = 1000;

    private final DuelModule module;

    SetArenaCommand(PindaFramework plugin, DuelModule module) {
        super(plugin, "setarena", "Stel de 1v1-arena in (plek 1, 2, toeschouwers en het beschermde gebied)", DuelModule.ADMIN);
        this.module = module;
    }

    /** "spawn1", "spawn2", "spectate" of "area" (de namen in duel.yml), of null. */
    static String spot(String input) {
        if (input == null) {
            return null;
        }
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "1", "een", "one", "spawn1" -> "spawn1";
            case "2", "twee", "two", "spawn2" -> "spawn2";
            case "kijken", "kijk", "toeschouwers", "spectate", "spectators", "spec", "tribune" -> "spectate";
            case "gebied", "area", "regio", "region" -> "area";
            default -> null;
        };
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0) {
            show(sender);
            return;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("wis") || first.equals("clear") || first.equals("verwijder")) {
            String which = spot(args.length > 1 ? args[1] : null);
            if (which == null) {
                plugin.lang().send(sender, "duel.arena.usage");
                return;
            }
            module.clearArena(which);
            plugin.lang().send(sender, which.equals("area") ? "duel.arena.area-cleared" : "duel.arena.cleared",
                    Text.p("spot", label(sender, which)));
            plugin.theme().play(sender, "success");
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        switch (first) {
            case "hoek1", "corner1", "pos1" -> {
                module.corner(player, player.getLocation());
                plugin.lang().send(player, "duel.arena.corner-first",
                        Text.p("location", Spot.of(player.getLocation()).describe()));
                plugin.theme().play(player, "click");
                return;
            }
            case "hoek2", "corner2", "pos2" -> {
                Location corner = module.corner(player);
                if (corner == null || !corner.getWorld().equals(player.getWorld())) {
                    plugin.lang().send(player, "duel.arena.corner-missing");
                    plugin.theme().play(player, "error");
                    return;
                }
                World world = player.getWorld();
                Location here = player.getLocation();
                Area area = Area.of(world.getName(), corner.getBlockX(), world.getMinHeight(), corner.getBlockZ(),
                        here.getBlockX(), world.getMaxHeight() - 1, here.getBlockZ());
                if (setArea(player, area)) {
                    module.clearCorner(player);
                }
                return;
            }
            default -> {
            }
        }
        String which = spot(first);
        if (which == null) {
            plugin.lang().send(sender, "duel.arena.usage");
            return;
        }
        if (which.equals("area")) {
            if (!module.worldEditPresent()) {
                plugin.lang().send(player, "duel.arena.no-worldedit");
                plugin.theme().play(player, "error");
                return;
            }
            Area selection = module.worldEditSelection(player);
            if (selection == null) {
                plugin.lang().send(player, "duel.arena.no-selection");
                plugin.theme().play(player, "error");
                return;
            }
            setArea(player, module.tallEnough(selection, player.getWorld()));
            return;
        }
        module.setArena(which, player.getLocation());
        plugin.lang().send(player, "duel.arena.set", Text.p("spot", label(sender, which)),
                Text.p("location", Spot.of(player.getLocation()).describe()));
        plugin.theme().play(player, "success");
        DuelSettings settings = module.settings();
        if (settings.arenaReady() && !settings.spawn1().world().equals(settings.spawn2().world())) {
            plugin.lang().send(player, "duel.arena.other-worlds");
        } else if (!settings.arenaReady()) {
            plugin.lang().send(player, "duel.arena.next", Text.p("spot", label(sender, settings.spawn1() == null ? "spawn1" : "spawn2")));
        } else if (settings.spectate() == null) {
            plugin.lang().send(player, "duel.arena.spectate-hint");
        } else {
            plugin.lang().send(player, "duel.arena.ready");
        }
        warnOutside(player);
    }

    /** Zet het gebied; false als het te groot is. */
    private boolean setArea(Player player, Area area) {
        if (area.sizeX() > MAX_SIZE || area.sizeZ() > MAX_SIZE) {
            plugin.lang().send(player, "duel.arena.area-too-big", Text.p("max", MAX_SIZE));
            plugin.theme().play(player, "error");
            return false;
        }
        module.setArea(area);
        plugin.lang().send(player, "duel.arena.area-set", Text.p("location", area.describe()));
        plugin.lang().send(player, protectionKey());
        plugin.theme().play(player, "success");
        warnOutside(player);
        return true;
    }

    /** Liggen de plekken buiten het ingestelde gebied? Dan een waarschuwing. */
    private void warnOutside(Player player) {
        DuelSettings settings = module.settings();
        Area area = settings.area();
        if (area == null) {
            return;
        }
        for (String which : List.of("spawn1", "spawn2", "spectate")) {
            Spot spot = spot(settings, which);
            if (spot != null && (!spot.world().equals(area.world()) || !area.contains(spot.blockX(), spot.blockY(), spot.blockZ()))) {
                plugin.lang().send(player, "duel.arena.outside-area", Text.p("spot", label(player, which)));
            }
        }
    }

    private String protectionKey() {
        if (!module.settings().protect()) {
            return "duel.arena.protection-off";
        }
        if (module.viaWorldGuard()) {
            return "duel.arena.protection-worldguard";
        }
        return module.worldGuardPresent() ? "duel.arena.protection-worldguard-failed" : "duel.arena.protection-framework";
    }

    private static Spot spot(DuelSettings settings, String which) {
        return switch (which) {
            case "spawn1" -> settings.spawn1();
            case "spawn2" -> settings.spawn2();
            default -> settings.spectate();
        };
    }

    private String label(CommandSender sender, String which) {
        String raw = plugin.lang().raw(plugin.lang().languageOf(sender), "duel.arena.spots." + which);
        return raw == null ? which : raw;
    }

    private void show(CommandSender sender) {
        DuelSettings settings = module.settings();
        plugin.lang().send(sender, "duel.arena.header");
        for (String which : List.of("spawn1", "spawn2", "spectate")) {
            Spot spot = spot(settings, which);
            if (spot == null) {
                plugin.lang().send(sender, "duel.arena.line-unset", Text.p("spot", label(sender, which)));
            } else {
                plugin.lang().send(sender, "duel.arena.line", Text.p("spot", label(sender, which)), Text.p("location", spot.describe()));
            }
        }
        Area guarded = module.guarded();
        if (guarded != null) {
            plugin.lang().send(sender, settings.area() != null ? "duel.arena.line-area" : "duel.arena.line-area-auto",
                    Text.p("spot", label(sender, "area")), Text.p("location", guarded.describe()));
        }
        plugin.lang().send(sender, protectionKey());
        plugin.lang().send(sender, "duel.arena.usage");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return List.of("1", "2", "kijken", "gebied", "hoek1", "hoek2", "wis");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("wis")) {
            return List.of("1", "2", "kijken", "gebied");
        }
        return List.of();
    }
}
