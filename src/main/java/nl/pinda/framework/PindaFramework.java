package nl.pinda.framework;

import java.util.logging.Level;
import nl.pinda.framework.command.AdminCommand;
import nl.pinda.framework.command.CommandManager;
import nl.pinda.framework.config.ConfigFile;
import nl.pinda.framework.config.Theme;
import nl.pinda.framework.economy.DisabledEconomy;
import nl.pinda.framework.economy.Economy;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.menu.Menu;
import nl.pinda.framework.menu.MenuListener;
import nl.pinda.framework.module.ModuleManager;
import nl.pinda.framework.modules.back.BackModule;
import nl.pinda.framework.modules.homes.HomesModule;
import nl.pinda.framework.modules.settings.SettingsModule;
import nl.pinda.framework.modules.spawn.SpawnModule;
import nl.pinda.framework.modules.tips.TipsModule;
import nl.pinda.framework.modules.tpa.TpaModule;
import nl.pinda.framework.player.PlayerManager;
import nl.pinda.framework.player.SettingsService;
import nl.pinda.framework.storage.CoreSchema;
import nl.pinda.framework.storage.Database;
import nl.pinda.framework.teleport.TeleportService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hoofdklasse van PindaFramework.
 *
 * <p>Het framework bestaat uit gedeelde services (config, thema, taal, database, spelers,
 * commando's en menu's) en losse modules die daarop bouwen. Modules staan in
 * {@code nl.pinda.framework.modules} en worden hier geregistreerd.
 */
public final class PindaFramework extends JavaPlugin {

    private ConfigFile mainConfig;
    private Theme theme;
    private LanguageManager lang;
    private Database database;
    private PlayerManager players;
    private SettingsService settings;
    private CommandManager commands;
    private ModuleManager modules;
    private TeleportService teleports;
    private Economy economy = new DisabledEconomy();

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        try {
            mainConfig = new ConfigFile(this, "config.yml");
            theme = new Theme(this);
            theme.load(mainConfig.get());
            lang = new LanguageManager(this);
            lang.load();

            database = new Database(this);
            database.connect(mainConfig.get().getString("database.file", "data.db"));
            database.migrate("core", CoreSchema.MIGRATIONS);

            players = new PlayerManager(this);
            settings = new SettingsService(this);
            commands = new CommandManager(this);
            teleports = new TeleportService(this);

            getServer().getPluginManager().registerEvents(players, this);
            getServer().getPluginManager().registerEvents(new MenuListener(this), this);
            getServer().getPluginManager().registerEvents(teleports, this);

            modules = new ModuleManager(this);
            modules.register(new SettingsModule(this));
            modules.register(new TipsModule(this));
            modules.register(new HomesModule(this));
            modules.register(new TpaModule(this));
            modules.register(new SpawnModule(this));
            modules.register(new BackModule(this));
            modules.enableAll();

            commands.register(new AdminCommand(this));
            commands.hook();

            players.loadOnlinePlayers();
            players.startCleanupTask();

            getLogger().info("PindaFramework v" + version() + " gestart in "
                    + (System.currentTimeMillis() - start) + "ms ("
                    + modules.enabledCount() + " modules actief).");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "PindaFramework kon niet starten en wordt uitgeschakeld.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) {
                player.closeInventory();
            }
        }
        if (players != null) {
            players.saveAll();
        }
        if (database != null) {
            database.close();
        }
    }

    /**
     * Herlaadt config, thema, taalbestanden en de configs van alle modules.
     *
     * @return de duur in milliseconden
     */
    public long reload() {
        long start = System.nanoTime();
        mainConfig.reload();
        theme.load(mainConfig.get());
        lang.load();
        teleports.reload();
        modules.reloadAll();
        return (System.nanoTime() - start) / 1_000_000L;
    }

    public String version() {
        return getPluginMeta().getVersion();
    }

    public YamlConfiguration mainConfig() {
        return mainConfig.get();
    }

    public Theme theme() {
        return theme;
    }

    public LanguageManager lang() {
        return lang;
    }

    public Database database() {
        return database;
    }

    public PlayerManager players() {
        return players;
    }

    public SettingsService settings() {
        return settings;
    }

    public CommandManager commands() {
        return commands;
    }

    public ModuleManager modules() {
        return modules;
    }

    public TeleportService teleports() {
        return teleports;
    }

    /** De actieve economy. Zonder economy-module is alles gratis. */
    public Economy economy() {
        return economy;
    }

    /** Wordt later door de economy-module aangeroepen om zich aan te melden. */
    public void setEconomy(Economy economy) {
        this.economy = economy == null ? new DisabledEconomy() : economy;
    }
}
