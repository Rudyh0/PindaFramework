package nl.pinda.framework.modules.homes;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ConfirmMenu;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import nl.pinda.framework.menu.MenuClick;
import nl.pinda.framework.player.KnownPlayer;
import nl.pinda.framework.teleport.TeleportType;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Het /homes-menu. Klik = teleporteren, shift-klik = verwijderen (met bevestiging).
 * Bij meer dan 45 homes zijn er pagina's.
 */
public final class HomesMenu extends Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final HomesModule module;
    private final KnownPlayer owner;
    private final List<Home> homes;
    private final int page;
    private final boolean own;

    public HomesMenu(PindaFramework plugin, Player viewer, HomesModule module, KnownPlayer owner, List<Home> homes, int page) {
        super(plugin, viewer, 6, title(plugin, viewer, module, owner, homes.size()));
        this.module = module;
        this.owner = owner;
        this.homes = List.copyOf(homes);
        this.page = page;
        this.own = owner.uuid().equals(viewer.getUniqueId());
    }

    private static Component title(PindaFramework plugin, Player viewer, HomesModule module, KnownPlayer owner, int count) {
        if (owner.uuid().equals(viewer.getUniqueId())) {
            return plugin.lang().component(viewer, "homes.menu.title",
                    Text.p("count", count), Text.p("limit", HomesModule.formatLimit(module.limit(viewer))));
        }
        return plugin.lang().component(viewer, "homes.menu.title-others",
                Text.p("player", owner.name()), Text.p("count", count));
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        int pages = Math.max(1, (homes.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));

        int start = current * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < homes.size(); i++) {
            Home home = homes.get(start + i);
            set(i, homeItem(lang, code, home), click -> onHomeClick(home, click));
        }

        if (homes.isEmpty()) {
            set(22, ItemBuilder.of(Material.OAK_SIGN)
                    .name(lang.component(code, "homes.menu.empty.name"))
                    .lore(lang.components(code, "homes.menu.empty.lore"))
                    .build());
        }

        // Onderste rij
        ItemStack filler = ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).hideTooltip().build();
        for (int slot = 45; slot < 54; slot++) {
            set(slot, filler);
        }
        TagResolver count = Text.p("count", homes.size());
        TagResolver limit = Text.p("limit", own ? HomesModule.formatLimit(module.limit(viewer)) : "-");
        TagResolver player = Text.p("player", owner.name());
        set(INFO_SLOT, ItemBuilder.of(Material.PLAYER_HEAD)
                .head(plugin.getServer().getOfflinePlayer(owner.uuid()))
                .name(lang.component(code, own ? "homes.menu.info.name" : "homes.menu.info.name-others", player))
                .lore(lang.components(code, own ? "homes.menu.info.lore" : "homes.menu.info.lore-others", count, limit, player))
                .build());
        if (current > 0) {
            set(PREVIOUS_SLOT, ItemBuilder.of(Material.ARROW)
                    .name(lang.component(code, "menu.previous"))
                    .build(), click -> reopen(homes, current - 1));
        }
        if (current < pages - 1) {
            set(NEXT_SLOT, ItemBuilder.of(Material.ARROW)
                    .name(lang.component(code, "menu.next"))
                    .build(), click -> reopen(homes, current + 1));
        }
    }

    private ItemStack homeItem(LanguageManager lang, String code, Home home) {
        World world = home.bukkitWorld();
        Material icon;
        if (world == null) {
            icon = Material.BARRIER;
        } else {
            icon = switch (world.getEnvironment()) {
                case NETHER -> Material.NETHERRACK;
                case THE_END -> Material.END_STONE;
                default -> Material.RED_BED;
            };
        }
        TagResolver[] placeholders = {
                Text.p("home", home.name()),
                Text.p("world", home.world()),
                Text.p("x", home.blockX()),
                Text.p("y", home.blockY()),
                Text.p("z", home.blockZ())
        };
        List<Component> lore = new ArrayList<>(lang.components(code, "homes.menu.item.info", placeholders));
        lore.add(Component.empty());
        Component cost = plugin.teleports().costLine(viewer, TeleportType.HOME);
        if (cost != null) {
            lore.add(cost);
        }
        lore.addAll(lang.components(code, canDelete() ? "homes.menu.item.actions" : "homes.menu.item.actions-no-delete"));
        return ItemBuilder.of(icon)
                .name(lang.component(code, "homes.menu.item.name", placeholders))
                .lore(lore)
                .build();
    }

    private boolean canDelete() {
        return own || viewer.hasPermission(HomesModule.OTHERS_DELETE);
    }

    private void onHomeClick(Home home, MenuClick click) {
        if (click.click().isShiftClick()) {
            if (!canDelete()) {
                return;
            }
            plugin.theme().play(viewer, "click");
            ItemStack subject = ItemBuilder.of(Material.RED_BED)
                    .name(plugin.lang().component(viewer, "homes.menu.delete-subject", Text.p("home", home.name())))
                    .build();
            plugin.getServer().getScheduler().runTask(plugin, () -> new ConfirmMenu(plugin, viewer, subject,
                    () -> {
                        module.deleteHome(owner.uuid(), home.name());
                        if (own) {
                            plugin.lang().send(viewer, "homes.deleted", Text.p("home", home.name()));
                        } else {
                            plugin.lang().send(viewer, "homes.others-deleted",
                                    Text.p("player", owner.name()), Text.p("home", home.name()));
                        }
                        plugin.theme().play(viewer, "success");
                        List<Home> remaining = new ArrayList<>(homes);
                        remaining.remove(home);
                        reopen(remaining, page);
                    },
                    () -> reopen(homes, page)).open());
            return;
        }
        closeLater();
        module.teleport(viewer, home, own ? null : owner);
    }

    private void reopen(List<Home> list, int newPage) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                new HomesMenu(plugin, viewer, module, owner, list, newPage).open();
            }
        });
    }
}
