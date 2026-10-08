package nl.pinda.framework.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.pinda.framework.modules.backpack.BackpackModule;
import nl.pinda.framework.modules.economy.EconomyModule;
import nl.pinda.framework.modules.homes.HomesModule;
import nl.pinda.framework.modules.leaderboards.LeaderboardsModule;
import nl.pinda.framework.modules.locks.LockModule;
import nl.pinda.framework.modules.moderation.ModerationModule;
import nl.pinda.framework.modules.panel.PanelModule;
import nl.pinda.framework.modules.shop.ShopModule;
import nl.pinda.framework.modules.skills.SkillsModule;
import nl.pinda.framework.modules.timber.TimberModule;

/**
 * Alle databasetabellen van het framework bij elkaar, ook van modules die uit staan.
 * Nodig om in één keer alles van SQLite naar MySQL over te zetten.
 *
 * <p>Een nieuwe module met eigen tabellen? Zet hem hier ook bij, met dezelfde naam als in
 * {@code plugin.database().migrate(...)}.
 */
public final class Schemas {

    private Schemas() {
    }

    public static Map<String, List<List<String>>> all() {
        Map<String, List<List<String>>> all = new LinkedHashMap<>();
        all.put("core", CoreSchema.MIGRATIONS);
        all.put("economy", EconomyModule.MIGRATIONS);
        all.put("shop", ShopModule.MIGRATIONS);
        all.put("locks", LockModule.MIGRATIONS);
        all.put("backpack", BackpackModule.MIGRATIONS);
        all.put("homes", HomesModule.MIGRATIONS);
        all.put("moderation", ModerationModule.MIGRATIONS);
        all.put("skills", SkillsModule.MIGRATIONS);
        all.put("timber", TimberModule.MIGRATIONS);
        all.put("leaderboards", LeaderboardsModule.MIGRATIONS);
        all.put("panel", PanelModule.MIGRATIONS);
        return all;
    }
}
