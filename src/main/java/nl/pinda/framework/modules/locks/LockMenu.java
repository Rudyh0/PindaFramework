package nl.pinda.framework.modules.locks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Toegang tot een kist, deur, luik of hek beheren (shift + rechtsklik met lege hand).
 *
 * <pre>
 * rij 0:   [info]
 * rij 1-3: spelers met toegang (klik = toegang afnemen)
 * rij 4:   [iedereen aan/uit]  [speler toevoegen]  [sluiten]
 * </pre>
 */
public final class LockMenu extends Menu {

    private static final int FIRST_SLOT = 9;
    private static final int LAST_SLOT = 35;

    private final LockService service;
    private final Block block;

    public LockMenu(PindaFramework plugin, Player viewer, LockService service, Block block) {
        super(plugin, viewer, 5, plugin.lang().component(viewer, "lock.menu.title"));
        this.service = service;
        this.block = block;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        Lock lock = service.find(block);
        if (lock == null) {
            closeLater();
            return;
        }

        Material icon = block.getType().isItem() ? block.getType() : Material.CHEST;
        set(4, ItemBuilder.of(icon)
                .name(lang.component(code, "lock.menu.info.name"))
                .lore(lang.components(code, "lock.menu.info.lore",
                        Text.p("player", name(lock.owner())),
                        Text.p("count", lock.trusted().size()),
                        Text.p("partners", service.partners(lock.owner()).size())))
                .build());

        List<UUID> trusted = new ArrayList<>(lock.trusted());
        int slot = FIRST_SLOT;
        for (UUID uuid : trusted) {
            if (slot > LAST_SLOT) {
                break;
            }
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(uuid);
            set(slot++, ItemBuilder.of(Material.PLAYER_HEAD)
                    .head(offline)
                    .name(lang.component(code, "lock.menu.trusted.name", Text.p("player", name(uuid))))
                    .lore(lang.components(code, "lock.menu.trusted.lore"))
                    .build(), click -> {
                service.trust(block, uuid, false);
                plugin.lang().send(viewer, "lock.untrusted", Text.p("player", name(uuid)));
                plugin.theme().play(viewer, "click");
                refresh();
            });
        }

        boolean everyone = lock.everyone();
        set(36, ItemBuilder.of(everyone ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(lang.component(code, everyone ? "lock.menu.everyone.enabled" : "lock.menu.everyone.disabled"))
                .lore(lang.components(code, "lock.menu.everyone.lore"))
                .build(), click -> {
            service.setEveryone(block, !everyone);
            plugin.theme().play(viewer, "click");
            refresh();
        });

        set(40, ItemBuilder.of(Material.NAME_TAG)
                .name(lang.component(code, "lock.menu.add.name"))
                .lore(lang.components(code, "lock.menu.add.lore"))
                .build(), click -> askPlayer());

        set(44, ItemBuilder.of(Material.BARRIER)
                .name(lang.component(code, "settings.menu.close.name"))
                .build(), click -> closeLater());

        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }

    private void askPlayer() {
        plugin.lang().send(viewer, "lock.add-prompt");
        plugin.input().ask(viewer, text -> plugin.players().findKnown(text).thenAccept(known ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) {
                        return;
                    }
                    Lock lock = service.find(block);
                    if (lock == null) {
                        return;
                    }
                    if (known == null) {
                        plugin.lang().send(viewer, "general.player-unknown", Text.p("player", text));
                        plugin.theme().play(viewer, "error");
                    } else if (known.uuid().equals(lock.owner())) {
                        plugin.lang().send(viewer, "lock.already-owner");
                    } else {
                        service.trust(block, known.uuid(), true);
                        plugin.lang().send(viewer, "lock.trusted", Text.p("player", known.name()));
                        plugin.theme().play(viewer, "success");
                    }
                    new LockMenu(plugin, viewer, service, block).open();
                })).exceptionally(error -> {
                    plugin.getLogger().log(Level.SEVERE, "Kon speler niet opzoeken", error);
                    return null;
                }),
                () -> plugin.getServer().getScheduler().runTask(plugin, () -> new LockMenu(plugin, viewer, service, block).open()));
    }

    private String name(UUID uuid) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : "?";
    }
}
