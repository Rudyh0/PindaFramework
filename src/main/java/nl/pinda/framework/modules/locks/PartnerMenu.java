package nl.pinda.framework.modules.locks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ConfirmMenu;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Je partners en openstaande verzoeken. Partners hebben toegang tot al jouw kisten en
 * deuren, en jij tot die van hen.
 */
public final class PartnerMenu extends Menu {

    private static final int FIRST_SLOT = 9;
    private static final int LAST_SLOT = 26;

    private final LockService service;
    private final Runnable back;

    /** @param back wat de terugknop doet (bijv. terug naar /instellingen), of null voor geen terugknop */
    public PartnerMenu(PindaFramework plugin, Player viewer, LockService service, Runnable back) {
        super(plugin, viewer, 4, plugin.lang().component(viewer, "partner.menu.title"));
        this.service = service;
        this.back = back;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        List<UUID> partners = new ArrayList<>(service.partners(viewer.getUniqueId()));
        List<UUID> requests = service.incomingRequests(viewer.getUniqueId());

        set(4, ItemBuilder.of(Material.BOOK)
                .name(lang.component(code, "partner.menu.info.name"))
                .lore(lang.components(code, "partner.menu.info.lore",
                        Text.p("count", partners.size()), Text.p("max", service.maxPartners())))
                .build());

        int slot = FIRST_SLOT;
        for (UUID partner : partners) {
            if (slot > LAST_SLOT) {
                break;
            }
            String name = Partners.name(plugin, partner);
            set(slot++, ItemBuilder.of(Material.PLAYER_HEAD)
                    .head(plugin.getServer().getOfflinePlayer(partner))
                    .name(lang.component(code, "partner.menu.partner.name", Text.p("player", name)))
                    .lore(lang.components(code, "partner.menu.partner.lore"))
                    .build(), click -> confirmRemove(partner, name));
        }
        for (UUID requester : requests) {
            if (slot > LAST_SLOT) {
                break;
            }
            String name = Partners.name(plugin, requester);
            set(slot++, ItemBuilder.of(Material.PLAYER_HEAD)
                    .head(plugin.getServer().getOfflinePlayer(requester))
                    .name(lang.component(code, "partner.menu.request.name", Text.p("player", name)))
                    .lore(lang.components(code, "partner.menu.request.lore"))
                    .glint(true)
                    .build(), click -> {
                if (click.click().isRightClick()) {
                    Partners.deny(plugin, service, viewer, requester);
                } else {
                    Partners.accept(plugin, service, viewer, requester);
                }
                refresh();
            });
        }

        if (back != null) {
            set(27, ItemBuilder.of(Material.ARROW)
                    .name(lang.component(code, "shop.menu.back"))
                    .build(), click -> plugin.getServer().getScheduler().runTask(plugin, back));
        }
        set(31, ItemBuilder.of(Material.NAME_TAG)
                .name(lang.component(code, "partner.menu.add.name"))
                .lore(lang.components(code, "partner.menu.add.lore"))
                .build(), click -> askPlayer());
        set(35, ItemBuilder.of(Material.BARRIER)
                .name(lang.component(code, "settings.menu.close.name"))
                .build(), click -> closeLater());

        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }

    private void confirmRemove(UUID partner, String name) {
        ItemStack subject = ItemBuilder.of(Material.PLAYER_HEAD)
                .head(plugin.getServer().getOfflinePlayer(partner))
                .name(plugin.lang().component(viewer, "partner.menu.remove-confirm", Text.p("player", name)))
                .build();
        plugin.getServer().getScheduler().runTask(plugin, () -> new ConfirmMenu(plugin, viewer, subject, () -> {
            Partners.remove(plugin, service, viewer, partner);
            reopen();
        }, this::reopen).open());
    }

    private void askPlayer() {
        plugin.lang().send(viewer, "partner.add-prompt");
        plugin.input().ask(viewer, text -> {
            Player target = plugin.getServer().getPlayerExact(text);
            if (target == null || !viewer.canSee(target)) {
                plugin.lang().send(viewer, "general.player-not-found", Text.p("player", text));
                plugin.theme().play(viewer, "error");
            } else {
                Partners.request(plugin, service, viewer, target);
            }
            reopen();
        }, this::reopen);
    }

    private void reopen() {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                new PartnerMenu(plugin, viewer, service, back).open();
            }
        });
    }
}
