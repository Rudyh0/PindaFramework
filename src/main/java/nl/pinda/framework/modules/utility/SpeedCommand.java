package nl.pinda.framework.modules.utility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /speed [lopen|vliegen] &lt;1-10&gt; [speler]. Zonder soort: vliegsnelheid als je vliegt,
 * anders loopsnelheid. 1 is normaal, 10 is het snelst.
 */
public final class SpeedCommand extends PindaCommand {

    private static final String OTHERS = "pinda.speed.others";

    public SpeedCommand(PindaFramework plugin) {
        super(plugin, "speed", "Verander je loop- of vliegsnelheid", "pinda.speed");
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        int index = 0;
        Boolean fly = null;
        if (args.length > 0) {
            fly = parseType(args[0]);
            if (fly != null) {
                index = 1;
            }
        }
        if (args.length <= index) {
            plugin.lang().send(sender, "utility.speed-usage");
            return;
        }
        float speed;
        try {
            speed = Float.parseFloat(args[index].replace(',', '.'));
        } catch (NumberFormatException e) {
            plugin.lang().send(sender, "utility.speed-usage");
            return;
        }
        speed = Math.max(0f, Math.min(10f, speed));

        Player target;
        if (args.length > index + 1 && !args[index + 1].isBlank()) {
            target = findPlayerOrFail(sender, args[index + 1]);
            if (target == null) {
                return;
            }
        } else {
            target = asPlayer(sender);
            if (target == null) {
                return;
            }
        }
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (!self && !checkPermission(sender, OTHERS)) {
            return;
        }

        boolean flying = fly != null ? fly : target.isFlying();
        float value = toBukkitSpeed(speed, flying);
        if (flying) {
            target.setFlySpeed(value);
        } else {
            target.setWalkSpeed(value);
        }

        String typeKey = flying ? "utility.speed-types.fly" : "utility.speed-types.walk";
        String shown = speed == Math.floor(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
        Component targetType = plugin.lang().component(target, typeKey);
        plugin.lang().send(target, "utility.speed-set", Text.c("type", targetType), Text.p("speed", shown));
        plugin.theme().play(target, "success");
        if (!self) {
            plugin.lang().send(sender, "utility.speed-set-other", Text.c("type", plugin.lang().component(sender, typeKey)),
                    Text.p("speed", shown), Text.p("player", target.getName()));
            plugin.theme().play(sender, "success");
        }
    }

    /** "lopen"/"walk" = false, "vliegen"/"fly" = true, iets anders = null. */
    private static Boolean parseType(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "walk", "lopen", "loop", "w" -> false;
            case "fly", "vliegen", "vlieg", "f" -> true;
            default -> null;
        };
    }

    /** Zelfde schaal als Essentials: 1 = normaal, 10 = maximaal. */
    private static float toBukkitSpeed(float speed, boolean fly) {
        float normal = fly ? 0.1f : 0.2f;
        float max = 1f;
        if (speed < 1f) {
            return normal * speed;
        }
        return normal + ((speed - 1f) / 9f) * (max - normal);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        if (index == 0) {
            List<String> options = new ArrayList<>(List.of("lopen", "vliegen"));
            for (int i = 1; i <= 10; i++) {
                options.add(String.valueOf(i));
            }
            return options;
        }
        boolean typed = args.length > 0 && parseType(args[0]) != null;
        if (typed && index == 1) {
            List<String> numbers = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                numbers.add(String.valueOf(i));
            }
            return numbers;
        }
        if (index == (typed ? 2 : 1) && sender.hasPermission(OTHERS)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }
}
