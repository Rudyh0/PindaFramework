package nl.pinda.framework.modules.skills;

import java.util.List;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** De ranglijst: top 10 per skill of op totaal level. */
final class SkillsTopMenu extends Menu {

    private static final int[] SLOTS = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};

    private final SkillsModule module;
    private final Skill skill;
    private final List<SkillService.TopEntry> entries;

    private SkillsTopMenu(PindaFramework plugin, Player viewer, SkillsModule module, Skill skill,
                          List<SkillService.TopEntry> entries) {
        super(plugin, viewer, 6, skill == null
                ? plugin.lang().component(viewer, "skills.top.title-total")
                : plugin.lang().component(viewer, "skills.top.title",
                Text.p("skill", module.service().name(skill, plugin.lang().languageOf(viewer)))));
        this.module = module;
        this.skill = skill;
        this.entries = entries;
    }

    /** Laadt de ranglijst en opent het menu. skill null = totaal level. */
    static void open(PindaFramework plugin, SkillsModule module, Player viewer, Skill skill) {
        module.service().top(skill, 10).thenAccept(entries -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                new SkillsTopMenu(plugin, viewer, module, skill, entries).open();
                plugin.theme().play(viewer, "click");
            }
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon de ranglijst niet laden", error);
            return null;
        });
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        SkillService service = module.service();

        set(0, ItemBuilder.of(Material.NETHER_STAR)
                .name(lang.component(code, "skills.top.tab-total"))
                .glint(skill == null)
                .build(), click -> {
                    if (skill != null) {
                        open(plugin, module, viewer, null);
                    }
                });
        Skill[] skills = Skill.values();
        for (int index = 0; index < skills.length && index < 8; index++) {
            Skill tab = skills[index];
            set(index + 1, ItemBuilder.of(tab.icon())
                    .name(lang.component(code, "skills.top.tab", Text.p("skill", service.name(tab, code))))
                    .glint(tab == skill)
                    .hideAttributes()
                    .build(), click -> {
                        if (tab != skill) {
                            open(plugin, module, viewer, tab);
                        }
                    });
        }

        if (entries.isEmpty()) {
            set(22, ItemBuilder.of(Material.PAPER)
                    .name(lang.component(code, "skills.top.empty"))
                    .build());
        }
        for (int index = 0; index < entries.size() && index < SLOTS.length; index++) {
            SkillService.TopEntry entry = entries.get(index);
            set(SLOTS[index], ItemBuilder.of(Material.PLAYER_HEAD)
                    .head(plugin.getServer().getOfflinePlayer(entry.uuid()))
                    .name(lang.component(code, "skills.top.entry", Text.p("position", index + 1),
                            Text.p("player", entry.name())))
                    .lore(lang.components(code, skill == null ? "skills.top.entry-lore-total" : "skills.top.entry-lore",
                            Text.p("level", entry.level()), Text.p("xp", service.formatXp(code, entry.xp()))))
                    .build(), click -> {
                        if (entry.uuid().equals(viewer.getUniqueId()) || viewer.hasPermission(SkillsModule.OTHERS)) {
                            SkillsMenu.open(plugin, module, viewer, entry.uuid(), entry.name());
                        }
                    });
        }

        set(49, ItemBuilder.of(Material.ARROW)
                .name(lang.component(code, "skills.top.back"))
                .build(), click -> SkillsMenu.open(plugin, module, viewer, viewer.getUniqueId(), viewer.getName()));
        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }
}
