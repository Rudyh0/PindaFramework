package nl.pinda.framework.modules.antilag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * /lag - hoe gaat het met de server (TPS, entities, items)
 * /lag chunks - de drukste chunks, klik om erheen te gaan
 * /lag clear [seconden|nu] - losse items opruimen
 */
final class LagCommand extends PindaCommand {

    private final AntilagModule module;

    LagCommand(PindaFramework plugin, AntilagModule module) {
        super(plugin, "lag", "Serverprestaties en antilag", AntilagModule.USE, "antilag");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0) {
            status(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "chunks" -> chunks(sender);
            case "clear", "opruimen" -> clear(sender, args);
            case "tp" -> teleport(sender, args);
            default -> plugin.lang().send(sender, "antilag.usage");
        }
    }

    private void status(CommandSender sender) {
        double[] tps = plugin.getServer().getTPS();
        Component tpsLine = module.tpsComponent(tps[0]).append(Component.text(", "))
                .append(module.tpsComponent(tps[1])).append(Component.text(", ")).append(module.tpsComponent(tps[2]));
        int chunks = 0;
        int entities = 0;
        int items = 0;
        for (AntilagModule.WorldLoad world : module.worlds()) {
            chunks += world.chunks();
            entities += world.entities();
            items += world.items();
        }
        String code = plugin.lang().languageOf(sender);
        String next;
        long when = module.nextClear();
        if (when == 0) {
            next = plugin.lang().raw(code, "antilag.next-off");
        } else {
            long seconds = Math.max(0, (when - System.currentTimeMillis()) / 1000);
            String time = seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
            String template = plugin.lang().raw(code, "antilag.next-in");
            next = template == null ? time : template.replace("<time>", time);
        }
        plugin.lang().send(sender, "antilag.status", Text.c("tps", tpsLine),
                Text.p("mspt", module.number(plugin.getServer().getAverageTickTime())),
                Text.p("chunks", chunks), Text.p("entities", entities), Text.p("items", items), Text.p("next", next));
    }

    private void chunks(CommandSender sender) {
        List<AntilagModule.ChunkLoad> busiest = module.busiest(5);
        if (busiest.isEmpty()) {
            plugin.lang().send(sender, "antilag.chunks-none");
            return;
        }
        plugin.lang().send(sender, "antilag.chunks-header");
        int position = 1;
        for (AntilagModule.ChunkLoad chunk : busiest) {
            plugin.lang().send(sender, "antilag.chunks-entry", Text.p("position", position++),
                    Text.c("location", module.location(sender, chunk)),
                    Text.p("count", chunk.entities()), Text.p("types", chunk.typesText()));
        }
    }

    private void clear(CommandSender sender, String[] args) {
        if (!checkPermission(sender, AntilagModule.ADMIN)) {
            return;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("nu") || args[1].equalsIgnoreCase("now"))) {
            int removed = module.clearNow();
            plugin.lang().send(sender, "antilag.cleared", Text.p("count", removed));
            return;
        }
        int seconds = 30;
        if (args.length >= 2) {
            try {
                seconds = Math.max(1, Math.min(600, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                plugin.lang().send(sender, "antilag.usage");
                return;
            }
        }
        if (module.startClear(seconds)) {
            plugin.lang().send(sender, "antilag.clear-started", Text.p("seconds", seconds));
        } else {
            plugin.lang().send(sender, "antilag.clear-busy");
        }
    }

    private void teleport(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null || !checkPermission(sender, AntilagModule.ADMIN)) {
            return;
        }
        if (args.length < 4) {
            plugin.lang().send(sender, "antilag.usage");
            return;
        }
        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            plugin.lang().send(sender, "antilag.world-unknown", Text.p("world", args[1]));
            return;
        }
        int cx;
        int cz;
        try {
            cx = Integer.parseInt(args[2]);
            cz = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            plugin.lang().send(sender, "antilag.usage");
            return;
        }
        Chunk chunk = world.getChunkAt(cx, cz);
        Location target = null;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Player)) {
                target = entity.getLocation().clone();
                break;
            }
        }
        if (target == null) {
            target = world.getHighestBlockAt(cx * 16 + 8, cz * 16 + 8).getLocation().add(0.5, 1, 0.5);
        }
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.COMMAND);
        plugin.lang().send(sender, "antilag.teleported", Text.p("world", world.getName()),
                Text.p("x", cx * 16 + 8), Text.p("z", cz * 16 + 8));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0) {
            List<String> options = new ArrayList<>(List.of("chunks"));
            if (sender.hasPermission(AntilagModule.ADMIN)) {
                options.add("clear");
            }
            return options;
        }
        if (argIndex(args) == 1 && args[0].equalsIgnoreCase("clear")) {
            return List.of("nu", "10", "30", "60");
        }
        return List.of();
    }
}
