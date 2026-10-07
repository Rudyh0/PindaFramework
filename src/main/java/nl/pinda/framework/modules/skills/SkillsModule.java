package nl.pinda.framework.modules.skills;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.player.PlayerSetting;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Skills zoals in RuneScape: level 0-99 in mijnbouw, houthakken, vissen, vechten, koken,
 * landbouw, boogschieten en alchemie. Contant geld bij elke level-up.
 */
public final class SkillsModule extends PindaModule implements Listener {

    public static final String USE = "pinda.skills.use";
    public static final String OTHERS = "pinda.skills.others";
    public static final String ADMIN = "pinda.skills.admin";

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_skills (
                        uuid TEXT NOT NULL,
                        skill TEXT NOT NULL,
                        xp REAL NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid, skill)
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_skills_skill ON pinda_skills (skill, xp)"
            )
    );

    private SkillService service;
    private PlacedBlocks placed;
    private SkillListener listener;

    public SkillsModule(PindaFramework plugin) {
        super(plugin, "skills");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("skills", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de skills-tabel niet aanmaken", e);
        }
        SkillRules rules = new SkillRules(config(), plugin.getLogger());
        service = new SkillService(plugin, rules);
        placed = new PlacedBlocks(plugin, service::rules);
        listener = new SkillListener(plugin, service, placed);
        listen(this);
        listen(placed);
        listen(listener);
        registerSetting(rules);
        command(new SkillsCommand(plugin, this));

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            service.profile(player);
        }
        repeat(() -> service.flush(), 20L * 30, 20L * 30);
        repeat(() -> {
            service.cleanup();
            service.checkBoost();
        }, 20L * 60, 20L * 60);
    }

    @Override
    protected void onDisable() {
        if (service != null) {
            service.flush();
            service.clear();
        }
        if (placed != null) {
            placed.saveAll(plugin.getServer().getWorlds());
        }
        if (listener != null) {
            listener.clear();
        }
        plugin.settings().unregister(SkillService.SETTING);
    }

    @Override
    protected void onReload() {
        SkillRules rules = new SkillRules(config(), plugin.getLogger());
        service.rules(rules);
        registerSetting(rules);
    }

    private void registerSetting(SkillRules rules) {
        plugin.settings().register(new PlayerSetting(SkillService.SETTING, rules.notifyDefault,
                Material.EXPERIENCE_BOTTLE, rules.notifyInSetup));
    }

    public SkillService service() {
        return service;
    }

    // ============================================================ laden en opslaan per speler

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            service.preload(event.getUniqueId());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.WARNING, "Kon de skills van " + event.getName() + " niet vooraf laden", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.unload(event.getPlayer().getUniqueId());
    }
}
