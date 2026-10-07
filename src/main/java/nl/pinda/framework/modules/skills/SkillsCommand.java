package nl.pinda.framework.modules.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.moderation.Durations;
import nl.pinda.framework.player.KnownPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /skills [speler] - het skillsmenu
 * /skills top [skill] - de ranglijst
 * /skills set|addxp|reset ... - beheer
 * /skills boost &lt;x&gt; &lt;duur&gt; | stop - XP-boost voor iedereen
 */
final class SkillsCommand extends PindaCommand {

    private final SkillsModule module;

    SkillsCommand(PindaFramework plugin, SkillsModule module) {
        super(plugin, "skills", "Je skills en de ranglijst", SkillsModule.USE, "skill", "vaardigheden", "levels");
        this.module = module;
    }

    private SkillService service() {
        return module.service();
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0) {
            Player player = asPlayer(sender);
            if (player != null) {
                SkillsMenu.open(plugin, module, player, player.getUniqueId(), player.getName());
            }
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "top", "ranglijst" -> top(sender, args);
            case "set", "zet" -> admin(sender, args, "set");
            case "addxp", "geefxp" -> admin(sender, args, "addxp");
            case "reset" -> reset(sender, args);
            case "boost" -> boost(sender, args);
            default -> other(sender, args[0]);
        }
    }

    private void top(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Skill skill = null;
        if (args.length >= 2) {
            skill = service().find(args[1]);
            if (skill == null) {
                unknownSkill(sender, args[1]);
                return;
            }
        }
        SkillsTopMenu.open(plugin, module, player, skill);
    }

    private void other(CommandSender sender, String name) {
        Player player = asPlayer(sender);
        if (player == null || !checkPermission(sender, SkillsModule.OTHERS)) {
            return;
        }
        withPlayer(sender, name, known -> SkillsMenu.open(plugin, module, player, known.uuid(), known.name()));
    }

    // ============================================================ beheer

    private void admin(CommandSender sender, String[] args, String action) {
        if (!checkPermission(sender, SkillsModule.ADMIN)) {
            return;
        }
        if (args.length < 4) {
            plugin.lang().send(sender, "skills.usage-admin");
            return;
        }
        Skill skill = service().find(args[2]);
        if (skill == null) {
            unknownSkill(sender, args[2]);
            return;
        }
        double number;
        try {
            number = Double.parseDouble(args[3].replace(',', '.'));
        } catch (NumberFormatException e) {
            number = -1;
        }
        if (number < 0 || (action.equals("set") && number > service().curve().maxLevel())) {
            plugin.lang().send(sender, "skills.invalid-number", Text.p("input", args[3]));
            plugin.theme().play(sender, "error");
            return;
        }
        double value = number;
        String code = plugin.lang().languageOf(sender);
        withPlayer(sender, args[1], known -> {
            CompletableFuture<Void> result;
            if (action.equals("set")) {
                result = service().setXp(known.uuid(), skill, service().curve().xpFor((int) value));
            } else {
                result = service().xpOf(known.uuid()).thenCompose(xp ->
                        service().setXp(known.uuid(), skill, xp.getOrDefault(skill, 0.0) + value));
            }
            done(sender, result, () -> {
                if (action.equals("set")) {
                    plugin.lang().send(sender, "skills.set-done", Text.p("player", known.name()),
                            Text.p("skill", service().name(skill, code)), Text.p("level", (int) value));
                } else {
                    plugin.lang().send(sender, "skills.addxp-done", Text.p("player", known.name()),
                            Text.p("skill", service().name(skill, code)), Text.p("amount", service().formatXp(code, value)));
                }
            });
        });
    }

    private void reset(CommandSender sender, String[] args) {
        if (!checkPermission(sender, SkillsModule.ADMIN)) {
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(sender, "skills.usage-admin");
            return;
        }
        Skill skill = null;
        if (args.length >= 3) {
            skill = service().find(args[2]);
            if (skill == null) {
                unknownSkill(sender, args[2]);
                return;
            }
        }
        Skill only = skill;
        String code = plugin.lang().languageOf(sender);
        withPlayer(sender, args[1], known -> {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Skill each : Skill.values()) {
                if (only == null || only == each) {
                    futures.add(service().setXp(known.uuid(), each, 0));
                }
            }
            done(sender, CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)), () -> {
                if (only == null) {
                    plugin.lang().send(sender, "skills.reset-all-done", Text.p("player", known.name()));
                } else {
                    plugin.lang().send(sender, "skills.reset-done", Text.p("player", known.name()),
                            Text.p("skill", service().name(only, code)));
                }
            });
        });
    }

    private void boost(CommandSender sender, String[] args) {
        if (!checkPermission(sender, SkillsModule.ADMIN)) {
            return;
        }
        String code = plugin.lang().languageOf(sender);
        if (args.length == 1) {
            long until = service().boostUntil();
            if (until == 0) {
                plugin.lang().send(sender, "skills.boost-none");
            } else {
                plugin.lang().send(sender, "skills.boost-status",
                        Text.p("multiplier", service().formatMultiplier(code, service().boostMultiplier())),
                        Text.p("time", service().durationText(code, until - System.currentTimeMillis())),
                        Text.p("by", service().boostBy()));
            }
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("stop") || action.equals("off") || action.equals("uit")) {
            boolean stopped = service().stopBoost(true);
            plugin.lang().send(sender, stopped ? "skills.boost-stopped" : "skills.boost-none");
            return;
        }
        if (args.length < 3) {
            plugin.lang().send(sender, "skills.boost-usage");
            return;
        }
        double multiplier;
        try {
            multiplier = Double.parseDouble(args[1].replace(',', '.').replace("x", ""));
        } catch (NumberFormatException e) {
            multiplier = -1;
        }
        long duration = Durations.parse(args[2]);
        if (multiplier <= 0 || multiplier > 10 || duration <= 0) {
            plugin.lang().send(sender, "skills.boost-usage");
            plugin.theme().play(sender, "error");
            return;
        }
        service().startBoost(multiplier, duration, sender.getName());
        if (!(sender instanceof Player)) {
            plugin.lang().send(sender, "skills.boost-started", Text.p("multiplier", service().formatMultiplier(code, multiplier)),
                    Text.p("time", service().durationText(code, duration)));
        }
    }

    // ============================================================ helpers

    private void unknownSkill(CommandSender sender, String input) {
        String code = plugin.lang().languageOf(sender);
        List<String> names = new ArrayList<>();
        for (Skill skill : Skill.values()) {
            names.add(service().name(skill, code));
        }
        plugin.lang().send(sender, "skills.unknown-skill", Text.p("input", input), Text.p("skills", String.join(", ", names)));
        plugin.theme().play(sender, "error");
    }

    private void withPlayer(CommandSender sender, String name, Consumer<KnownPlayer> action) {
        plugin.players().findKnown(name).thenAccept(known -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (known == null) {
                plugin.lang().send(sender, "general.player-unknown", Text.p("player", name));
                plugin.theme().play(sender, "error");
                return;
            }
            action.accept(known);
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler " + name + " niet opzoeken", error);
            return null;
        });
    }

    private void done(CommandSender sender, CompletableFuture<?> future, Runnable success) {
        future.whenComplete((ignored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Skills aanpassen mislukt", error);
                plugin.lang().send(sender, "general.command-error");
                return;
            }
            success.run();
            plugin.theme().play(sender, "success");
        }));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        boolean admin = sender.hasPermission(SkillsModule.ADMIN);
        if (index == 0) {
            List<String> options = new ArrayList<>(List.of("top"));
            if (admin) {
                options.addAll(List.of("set", "addxp", "reset", "boost"));
            }
            if (sender.hasPermission(SkillsModule.OTHERS)) {
                options.addAll(visiblePlayers(sender));
            }
            return options;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> skills = new ArrayList<>();
        for (Skill skill : Skill.values()) {
            skills.add(skill.id());
        }
        if (sub.equals("top") && index == 1) {
            return skills;
        }
        if (admin && (sub.equals("set") || sub.equals("addxp") || sub.equals("reset"))) {
            if (index == 1) {
                return visiblePlayers(sender);
            }
            if (index == 2) {
                return skills;
            }
            if (index == 3 && sub.equals("set")) {
                return List.of("10", "50", "99");
            }
        }
        if (admin && sub.equals("boost")) {
            if (index == 1) {
                return List.of("2", "1.5", "3", "stop");
            }
            if (index == 2) {
                return List.of("30m", "1u", "2u", "1d");
            }
        }
        return List.of();
    }
}
