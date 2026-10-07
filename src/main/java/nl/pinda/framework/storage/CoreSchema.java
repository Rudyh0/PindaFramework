package nl.pinda.framework.storage;

import java.util.List;

/**
 * Databasetabellen van de kern. Voeg wijzigingen toe als nieuwe versie onderaan de lijst;
 * pas bestaande versies nooit aan.
 */
public final class CoreSchema {

    private CoreSchema() {
    }

    public static final List<List<String>> MIGRATIONS = List.of(
            // Versie 1: spelers en hun instellingen
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_players (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        language TEXT,
                        setup_completed INTEGER NOT NULL DEFAULT 0,
                        first_join INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_players_name ON pinda_players (name COLLATE NOCASE)",
                    """
                    CREATE TABLE IF NOT EXISTS pinda_player_settings (
                        uuid TEXT NOT NULL,
                        setting TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (uuid, setting)
                    )"""
            )
    );
}
