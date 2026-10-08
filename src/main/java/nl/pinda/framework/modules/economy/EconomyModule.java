package nl.pinda.framework.modules.economy;

import java.sql.SQLException;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Economy met PindaCredits: contant geld (verlies je bij doodgaan) en de bank (veilig,
 * maar storten kost een klein percentage). Plus een bonus per uur actief spelen.
 */
public final class EconomyModule extends PindaModule {

    public static final String BALANCE = "pinda.eco.balance";
    public static final String BALANCE_OTHERS = "pinda.eco.balance.others";
    public static final String PAY = "pinda.eco.pay";
    public static final String BANK = "pinda.eco.bank";
    public static final String BALTOP = "pinda.eco.baltop";
    public static final String ADMIN = "pinda.eco.admin";

    /** De databasetabellen van deze module (ook gebruikt bij het omzetten naar MySQL). */
    public static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_economy (
                        uuid TEXT PRIMARY KEY,
                        cash INTEGER NOT NULL DEFAULT 0,
                        bank INTEGER NOT NULL DEFAULT 0,
                        playtime INTEGER NOT NULL DEFAULT 0
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS pinda_economy_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        uuid TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        cash_after INTEGER,
                        bank_after INTEGER,
                        note TEXT
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_economy_log_uuid ON pinda_economy_log (uuid, time)"
            )
    );

    private EconomyService service;

    public EconomyModule(PindaFramework plugin) {
        super(plugin, "economy");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("economy", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de economy-tabellen niet aanmaken", e);
        }
        service = new EconomyService(plugin, this);
        listen(service);
        service.loadOnline();
        plugin.setEconomy(service);

        command(new BalanceCommand(plugin, service));
        command(new PayCommand(plugin, service));
        command(new BankCommand(plugin, service));
        command(new BaltopCommand(plugin, service));
        command(new EcoCommand(plugin, service));

        repeat(service::playtimeTick, 20L * 60, 20L * 60);
        repeat(service::cleanup, 20L * 60, 20L * 60);
    }

    @Override
    protected void onDisable() {
        plugin.setEconomy(null);
        if (service != null) {
            service.clear();
        }
    }

    /** De config van deze module (modules/economy.yml). */
    YamlConfiguration cfg() {
        return config();
    }

    public EconomyService service() {
        return service;
    }
}
