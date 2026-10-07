package nl.pinda.framework.modules.locks;

import java.sql.SQLException;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Kisten, vaten, shulkerboxen, deuren, luiken en hekken zijn automatisch privé.
 * Shift+rechtsklik (met lege hand) opent het toegangsmenu. Partners hebben toegang tot
 * al elkaars sloten.
 */
public final class LockModule extends PindaModule {

    public static final String PARTNER = "pinda.partner.use";

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_locks (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        owner TEXT NOT NULL,
                        everyone INTEGER NOT NULL DEFAULT 0,
                        created INTEGER NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_locks_owner ON pinda_locks (owner)",
                    """
                    CREATE TABLE IF NOT EXISTS pinda_lock_trusted (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        player TEXT NOT NULL,
                        PRIMARY KEY (world, x, y, z, player)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS pinda_lock_hoppers (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        owner TEXT NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS pinda_partners (
                        a TEXT NOT NULL,
                        b TEXT NOT NULL,
                        since INTEGER NOT NULL,
                        PRIMARY KEY (a, b)
                    )"""
            )
    );

    private LockService service;

    public LockModule(PindaFramework plugin) {
        super(plugin, "locks");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("locks", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de slot-tabellen niet aanmaken", e);
        }
        service = new LockService(plugin, this);
        try {
            service.loadAll();
        } catch (Exception e) {
            throw new IllegalStateException("Kon de sloten niet laden", e);
        }
        listen(new LockListener(plugin, this, service));
        command(new PartnerCommand(plugin, service));
    }

    YamlConfiguration cfg() {
        return config();
    }

    public LockService service() {
        return service;
    }
}
