package nl.pinda.framework.storage;

import java.io.File;
import java.util.Locale;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.config.ConfigFile;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Waar de gegevens staan: {@code database.yml} in de pluginmap. Dit bestand hoort bij het
 * dev-paneel en staat bewust niet in het webpaneel voor staff (er staat een wachtwoord in).
 */
public final class DatabaseSettings {

    public enum Type { SQLITE, MYSQL }

    private static final String FILE = "database.yml";

    private final ConfigFile file;

    public DatabaseSettings(PindaFramework plugin) {
        boolean existed = new File(plugin.getDataFolder(), FILE).exists();
        this.file = new ConfigFile(plugin, FILE);
        if (!existed) {
            // Vroeger stond de naam van het SQLite-bestand in config.yml.
            String old = plugin.mainConfig().getString("database.file");
            if (old != null && !old.isBlank() && !old.equals("data.db")) {
                file.get().set("sqlite.file", old);
                file.save();
            }
        }
    }

    private YamlConfiguration yaml() {
        return file.get();
    }

    public Type type() {
        return "mysql".equals(yaml().getString("type", "sqlite").trim().toLowerCase(Locale.ROOT)) ? Type.MYSQL : Type.SQLITE;
    }

    public String sqliteFile() {
        String name = yaml().getString("sqlite.file", "data.db");
        return name == null || name.isBlank() ? "data.db" : name;
    }

    public String host() {
        return yaml().getString("mysql.host", "127.0.0.1");
    }

    public int port() {
        return yaml().getInt("mysql.port", 3306);
    }

    public String database() {
        return yaml().getString("mysql.database", "pindacraft");
    }

    public String user() {
        return yaml().getString("mysql.user", "pindacraft");
    }

    public String password() {
        return yaml().getString("mysql.password", "");
    }

    public boolean useSsl() {
        return yaml().getBoolean("mysql.ssl", false);
    }

    /** Moet bij deze start alles van SQLite naar MySQL worden overgezet? */
    public boolean convertFromSqlite() {
        return yaml().getBoolean("convert-from-sqlite", false);
    }

    /** Het omzetten is gelukt: niet nog eens doen. */
    public void conversionDone() {
        yaml().set("convert-from-sqlite", false);
        file.save();
    }

    /** "mysql://pindacraft@127.0.0.1:3306/pindacraft" of "sqlite:data.db", voor in het logboek. */
    public String describe() {
        return type() == Type.MYSQL
                ? "MySQL " + user() + "@" + host() + ":" + port() + "/" + database()
                : "SQLite " + sqliteFile();
    }
}
