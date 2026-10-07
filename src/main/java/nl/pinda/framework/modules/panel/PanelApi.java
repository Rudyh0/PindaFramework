package nl.pinda.framework.modules.panel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.economy.EconomyModule;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.moderation.ModerationModule;
import nl.pinda.framework.modules.ranks.Rank;
import nl.pinda.framework.modules.ranks.RankModule;
import nl.pinda.framework.modules.shop.ShopModule;

/** Basis voor de onderdelen van de paneel-API, met gedeelde helpers. */
abstract class PanelApi {

    protected final PanelModule module;
    protected final PindaFramework plugin;

    PanelApi(PanelModule module) {
        this.module = module;
        this.plugin = module.plugin();
    }

    abstract void register(PanelServer server);

    // ============================================================ threads

    /** Iets op de hoofdthread uitvoeren en op het resultaat wachten. */
    protected <T> T sync(Callable<T> task) throws Exception {
        return PanelModule.await(module.sync(task));
    }

    /** Iets op de hoofdthread starten dat zelf een future teruggeeft, en daarop wachten. */
    protected <T> T syncAwait(Callable<CompletableFuture<T>> task) throws Exception {
        return PanelModule.await(module.sync(task).thenCompose(future -> future));
    }

    protected static <T> T await(CompletableFuture<T> future) throws Exception {
        return PanelModule.await(future);
    }

    // ============================================================ modules

    protected <T extends PindaModule> T enabled(Class<T> type) {
        T found = plugin.modules().get(type);
        return found != null && found.isEnabled() ? found : null;
    }

    protected RankModule ranks() {
        return enabled(RankModule.class);
    }

    protected EconomyService economy() {
        EconomyModule economy = enabled(EconomyModule.class);
        return economy == null ? null : economy.service();
    }

    protected ShopModule shops() {
        return enabled(ShopModule.class);
    }

    protected ModerationModule moderation() {
        return enabled(ModerationModule.class);
    }

    protected <T extends PindaModule> T require(Class<T> type, String name) throws ApiException {
        T found = enabled(type);
        if (found == null) {
            throw new ApiException(409, "De module '" + name + "' staat uit.");
        }
        return found;
    }

    // ============================================================ JSON

    /** Een JSON-object in een vaste volgorde: map("a", 1, "b", 2). */
    protected static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    protected static Map<String, Object> rank(Rank rank) {
        if (rank == null) {
            return null;
        }
        return map("id", rank.id(), "name", rank.displayName(), "color", rank.color().asHexString(),
                "weight", rank.weight(), "operator", rank.operator());
    }

    /** Een bedrag in centen, met de tekst zoals spelers hem in-game zien. */
    protected Map<String, Object> money(long cents) {
        EconomyService economy = economy();
        return map("cents", cents, "text", economy == null ? String.valueOf(cents / 100.0) : economy.format(cents));
    }
}
