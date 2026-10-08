package nl.pinda.framework.modules.leaderboards;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import nl.pinda.framework.modules.skills.SkillsModule;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** /top: bovenin je eigen hoofd, daaronder de toplijsten om uit te kiezen, in het midden de top 10. */
final class TopMenu extends Menu {

    private static final int[] SLOTS = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};

    private final LeaderboardsModule module;
    private final Board board;

    private TopMenu(PindaFramework plugin, Player viewer, LeaderboardsModule module, Board board) {
        super(plugin, viewer, 6, plugin.lang().component(viewer, "top.menu.title",
                Text.p("board", module.service().name(board, plugin.lang().languageOf(viewer)))));
        this.module = module;
        this.board = board;
    }

    /** Opent het menu op een toplijst (of de eerste als board null is). */
    static void open(PindaFramework plugin, LeaderboardsModule module, Player viewer, Board board) {
        List<Board> boards = module.service().boards();
        if (boards.isEmpty()) {
            plugin.lang().send(viewer, "top.none");
            return;
        }
        Board shown = board != null && boards.contains(board) ? board : boards.get(0);
        new TopMenu(plugin, viewer, module, shown).open();
        plugin.theme().play(viewer, "menu-open");
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        LeaderboardService service = module.service();
        List<Board> boards = service.boards();

        // De toplijsten om uit te kiezen (tweede rij)
        int[] tabs = centered(1, boards.size());
        for (int index = 0; index < boards.size() && index < tabs.length; index++) {
            Board tab = boards.get(index);
            LeaderboardService.Ranking ranking = service.ranking(tab);
            LeaderboardService.Entry own = ranking.entry(viewer.getUniqueId());
            Component position = own == null
                    ? lang.component(code, "top.menu.board.position-none")
                    : lang.component(code, "top.menu.board.position", Text.p("position", ranking.position(viewer.getUniqueId())),
                    Text.p("value", service.format(tab, own.value(), code)));
            set(tabs[index], ItemBuilder.of(tab.icon())
                    .name(lang.component(code, "top.menu.board.name", Text.p("board", service.name(tab, code))))
                    .lore(lang.components(code, "top.menu.board.lore",
                            Text.c("description", lang.component(code, "top.boards." + tab.id() + ".description")),
                            Text.c("position", position)))
                    .glint(tab == board)
                    .hideAttributes()
                    .build(), click -> {
                        if (tab != board) {
                            new TopMenu(plugin, viewer, module, tab).open();
                            plugin.theme().play(viewer, "click");
                        }
                    });
        }

        List<Component> summary = new ArrayList<>();
        for (Board each : boards) {
            LeaderboardService.Ranking ranking = service.ranking(each);
            LeaderboardService.Entry own = ranking.entry(viewer.getUniqueId());
            summary.add(own == null
                    ? lang.component(code, "top.menu.head.line-none", Text.p("board", service.name(each, code)))
                    : lang.component(code, "top.menu.head.line", Text.p("board", service.name(each, code)),
                    Text.p("position", ranking.position(viewer.getUniqueId())), Text.p("value", service.format(each, own.value(), code))));
        }
        set(4, ItemBuilder.of(Material.PLAYER_HEAD)
                .head(viewer)
                .name(lang.component(code, "top.menu.head.name", Text.p("player", viewer.getName())))
                .lore(summary)
                .build());

        // De top 10
        List<LeaderboardService.Entry> entries = service.ranking(board).top(SLOTS.length);
        if (entries.isEmpty()) {
            set(22, ItemBuilder.of(Material.PAPER).name(lang.component(code, "top.menu.empty")).build());
        }
        for (int index = 0; index < entries.size(); index++) {
            LeaderboardService.Entry entry = entries.get(index);
            set(SLOTS[index], ItemBuilder.of(Material.PLAYER_HEAD)
                    .head(plugin.getServer().getOfflinePlayer(entry.uuid()))
                    .amount(index + 1)
                    .name(lang.component(code, entry.uuid().equals(viewer.getUniqueId()) ? "top.menu.entry-self" : "top.menu.entry",
                            Text.p("position", index + 1), Text.p("player", entry.name())))
                    .lore(lang.components(code, "top.menu.entry-lore", Text.p("value", service.format(board, entry.value(), code))))
                    .build());
        }

        // Onderin: per skill (bij skills) en sluiten
        SkillsModule skills = plugin.modules().get(SkillsModule.class);
        if (board == Board.SKILLS && skills != null && skills.isEnabled()) {
            set(45, ItemBuilder.of(Material.ENCHANTED_BOOK)
                    .name(lang.component(code, "top.menu.skills.name"))
                    .lore(lang.components(code, "top.menu.skills.lore"))
                    .build(), click -> skills.openTop(viewer));
        }
        set(49, ItemBuilder.of(Material.BARRIER)
                .name(lang.component(code, "top.menu.close"))
                .build(), click -> closeLater());
        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }
}
