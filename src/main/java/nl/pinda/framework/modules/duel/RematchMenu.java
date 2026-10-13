package nl.pinda.framework.modules.duel;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;

/** Na een gevecht: nog een keer (met een nieuwe inzet), of stoppen en terug naar waar je was. */
final class RematchMenu extends Menu {

    private static final int YES = 11;
    private static final int INFO = 13;
    private static final int NO = 15;

    private final Session session;
    private final Session.Side side;

    RematchMenu(PindaFramework plugin, Player viewer, Session session, Session.Side side) {
        super(plugin, viewer, 3, plugin.lang().component(viewer, "duel.rematch.title", Text.p("player", session.other(side).name)));
        this.session = session;
        this.side = side;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        Session.Side other = session.other(side);
        TagResolver name = Text.p("player", other.name);
        TagResolver score = Text.p("score", session.score(side));
        String status = other.rematch == null ? "duel.rematch.status-waiting" : other.rematch ? "duel.rematch.status-yes" : "duel.rematch.status-no";

        set(YES, ItemBuilder.of(Material.LIME_CONCRETE)
                .name(lang.component(code, "duel.rematch.again.name"))
                .lore(lang.components(code, "duel.rematch.again.lore", name))
                .build(), click -> {
            plugin.theme().play(viewer, "click");
            // Een tick later: willen ze allebei, dan gaat het inzetmenu open (niet midden in een klik).
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                session.rematch(side, true);
                if (session.phase == Session.Phase.REMATCH) {
                    closeLater();
                }
            });
        });
        set(INFO, ItemBuilder.of(Material.PLAYER_HEAD).head(plugin.getServer().getOfflinePlayer(other.uuid))
                .name(lang.component(code, "duel.rematch.info.name", name, score))
                .lore(lang.components(code, "duel.rematch.info.lore", name, score, Text.p("round", session.round),
                        Text.c("status", lang.component(code, status, name)),
                        Text.p("seconds", session.secondsLeft(session.settings().rematchSeconds()))))
                .build());
        set(NO, ItemBuilder.of(Material.RED_CONCRETE)
                .name(lang.component(code, "duel.rematch.stop.name"))
                .lore(lang.components(code, "duel.rematch.stop.lore"))
                .build(), click -> {
            plugin.theme().play(viewer, "click");
            plugin.getServer().getScheduler().runTask(plugin, () -> session.rematch(side, false));
        });
        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }

    @Override
    protected void onClose(InventoryCloseEvent.Reason reason) {
        if (session.closingMenus || session.phase != Session.Phase.REMATCH || side.rematch != null || !plugin.isEnabled()
                || reason == InventoryCloseEvent.Reason.OPEN_NEW || reason == InventoryCloseEvent.Reason.DISCONNECT) {
            return;
        }
        // Dicht zonder te kiezen: de vraag staat ook in de chat.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = session.player(side);
            if (player != null && session.phase == Session.Phase.REMATCH && side.rematch == null) {
                session.askRematch(side, player);
            }
        });
    }
}
