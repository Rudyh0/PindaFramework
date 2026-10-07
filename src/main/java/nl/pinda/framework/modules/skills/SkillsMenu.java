package nl.pinda.framework.modules.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import nl.pinda.framework.modules.economy.EconomyModule;
import nl.pinda.framework.modules.economy.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** /skills: alle skills van een speler met level, voortgang en plek op de ranglijst. */
final class SkillsMenu extends Menu {

    private static final int[] SLOTS = {10, 12, 14, 16, 28, 30, 32, 34};

    private final SkillsModule module;
    private final UUID target;
    private final String targetName;
    private final Map<Skill, Double> xp;
    private final Map<Skill, Integer> positions;

    private SkillsMenu(PindaFramework plugin, Player viewer, SkillsModule module, UUID target, String targetName,
                       Map<Skill, Double> xp, Map<Skill, Integer> positions) {
        super(plugin, viewer, 5, plugin.lang().component(viewer, viewer.getUniqueId().equals(target)
                ? "skills.menu.title" : "skills.menu.title-others", Text.p("player", targetName)));
        this.module = module;
        this.target = target;
        this.targetName = targetName;
        this.xp = xp;
        this.positions = positions;
    }

    /** Laadt de gegevens (ook van offline spelers) en opent dan het menu. */
    static void open(PindaFramework plugin, SkillsModule module, Player viewer, UUID target, String targetName) {
        SkillService service = module.service();
        service.xpOf(target).thenCombine(service.positions(target), (xp, positions) -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) {
                    new SkillsMenu(plugin, viewer, module, target, targetName, xp, positions).open();
                    plugin.theme().play(viewer, "menu-open");
                }
            });
            return null;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon skills van " + targetName + " niet laden", error);
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.lang().send(viewer, "general.command-error"));
            return null;
        });
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        SkillService service = module.service();
        SkillCurve curve = service.curve();

        double totalXp = 0;
        for (double value : xp.values()) {
            totalXp += value;
        }
        List<Component> headLore = new ArrayList<>(lang.components(code, "skills.menu.head.lore",
                Text.p("total", service.totalLevel(xp)),
                Text.p("max", curve.maxLevel() * Skill.values().length),
                Text.p("xp", service.formatXp(code, totalXp))));
        long boostUntil = service.boostUntil();
        if (boostUntil > 0) {
            headLore.add(Component.empty());
            headLore.add(lang.component(code, "skills.menu.head.boost",
                    Text.p("multiplier", service.formatMultiplier(code, service.boostMultiplier())),
                    Text.p("time", service.durationText(code, boostUntil - System.currentTimeMillis()))));
        }
        set(4, ItemBuilder.of(Material.PLAYER_HEAD)
                .head(plugin.getServer().getOfflinePlayer(target))
                .name(lang.component(code, "skills.menu.head.name", Text.p("player", targetName)))
                .lore(headLore)
                .build());

        Skill[] skills = Skill.values();
        for (int index = 0; index < skills.length && index < SLOTS.length; index++) {
            Skill skill = skills[index];
            set(SLOTS[index], skillItem(code, skill, curve), click -> SkillsTopMenu.open(plugin, module, viewer, skill));
        }

        set(39, ItemBuilder.of(Material.GOLD_INGOT)
                .name(lang.component(code, "skills.menu.top.name"))
                .lore(lang.components(code, "skills.menu.top.lore"))
                .build(), click -> SkillsTopMenu.open(plugin, module, viewer, null));
        set(41, ItemBuilder.of(Material.BARRIER)
                .name(lang.component(code, "skills.menu.close"))
                .build(), click -> closeLater());
        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }

    private org.bukkit.inventory.ItemStack skillItem(String code, Skill skill, SkillCurve curve) {
        LanguageManager lang = plugin.lang();
        SkillService service = module.service();
        double value = xp.getOrDefault(skill, 0.0);
        int level = curve.levelOf(value);
        boolean max = level >= curve.maxLevel();
        TagResolver name = Text.p("skill", service.name(skill, code));

        List<Component> lore = new ArrayList<>(lang.components(code, "skills.info." + skill.id()));
        lore.add(Component.empty());
        if (!service.isEnabled(skill)) {
            lore.add(lang.component(code, "skills.menu.skill.disabled"));
        } else if (max) {
            lore.add(lang.component(code, "skills.menu.skill.xp-max", Text.p("xp", service.formatXp(code, value))));
            lore.add(lang.component(code, "skills.menu.skill.max"));
        } else {
            long next = curve.xpFor(level + 1);
            double progress = curve.progress(value);
            lore.add(lang.component(code, "skills.menu.skill.xp", Text.p("xp", service.formatXp(code, value)),
                    Text.p("next", service.formatXp(code, next))));
            lore.add(lang.component(code, "skills.menu.skill.bar", Text.c("bar", service.bar(progress, 20)),
                    Text.p("percent", (int) Math.floor(progress * 100))));
            lore.add(lang.component(code, "skills.menu.skill.left", Text.p("left", service.formatXp(code, Math.ceil(next - value))),
                    Text.p("next_level", level + 1)));
            String reward = reward(level + 1);
            if (reward != null) {
                lore.add(lang.component(code, "skills.menu.skill.reward", Text.p("reward", reward)));
            }
        }
        Integer position = positions.get(skill);
        if (position != null) {
            lore.add(lang.component(code, "skills.menu.skill.position", Text.p("position", position)));
        }
        lore.add(Component.empty());
        lore.add(lang.component(code, "skills.menu.skill.click"));
        return ItemBuilder.of(skill.icon())
                .name(lang.component(code, "skills.menu.skill.name", name, Text.p("level", level)))
                .lore(lore)
                .glint(max)
                .hideAttributes()
                .build();
    }

    /** Het geld voor een level als tekst, of null zonder economy. */
    private String reward(int level) {
        EconomyModule economy = plugin.modules().get(EconomyModule.class);
        double amount = module.service().rules().reward(level);
        if (economy == null || !economy.isEnabled() || amount <= 0) {
            return null;
        }
        return economy.service().format(Money.toCents(amount));
    }
}
