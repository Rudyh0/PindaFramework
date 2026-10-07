package nl.pinda.framework.modules.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.moderation.ModerationModule;
import nl.pinda.framework.modules.ranks.Rank;
import nl.pinda.framework.modules.ranks.RankModule;

/** Inloggen, uitloggen en "wie ben ik". */
final class AuthApi extends PanelApi {

    AuthApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.open("GET", "/api/info", request -> map("server", module.serverName(), "version", plugin.version()));
        server.open("POST", "/api/login", this::login);
        server.post("/api/logout", null, this::logout);
        server.get("/api/me", null, request -> sync(() -> me(request.user())));
    }

    private Object login(PanelRequest request) throws Exception {
        PanelSessions sessions = module.sessions();
        if (sessions.blocked(request.ip())) {
            throw new ApiException(429, "Te veel mislukte pogingen. Probeer het over 10 minuten opnieuw.");
        }
        PanelSessions.Token token = sessions.consume(request.optString("token"));
        if (token == null) {
            sessions.failed(request.ip());
            throw new ApiException(401, "Deze inloglink is ongeldig, al gebruikt of verlopen. Typ /panel in-game voor een nieuwe.");
        }
        PanelUser user = await(module.loadUser(token.uuid(), token.name()));
        if (!user.has(PanelUser.USE)) {
            throw ApiException.forbidden("Je hebt geen toegang tot het paneel.");
        }
        PanelSessions.Session session = sessions.create(token.uuid(), token.name(), request.ip());
        session.user = user;
        session.userLoaded = System.currentTimeMillis();
        request.session = session;
        request.user = user;
        request.exchange().getResponseHeaders().add("Set-Cookie", PanelServer.COOKIE + "=" + session.id
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + module.maxMillis() / 1000
                + (module.secureCookies() ? "; Secure" : ""));
        module.log().add(request, "inloggen", null, null);
        return sync(() -> me(user));
    }

    private Object logout(PanelRequest request) {
        module.sessions().remove(request.session.id);
        request.exchange().getResponseHeaders().add("Set-Cookie", PanelServer.COOKIE
                + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (module.secureCookies() ? "; Secure" : ""));
        module.log().add(request, "uitloggen", null, null);
        return null;
    }

    private Map<String, Object> me(PanelUser user) {
        List<Map<String, Object>> ranks = new ArrayList<>();
        RankModule rankModule = ranks();
        if (rankModule != null) {
            for (Rank rank : rankModule.service().ranks()) {
                ranks.add(rank(rank));
            }
        }
        ModerationModule moderation = moderation();
        EconomyService economy = economy();
        String currency = null;
        if (economy != null) {
            // De valutanaam zoals spelers hem zien, bijv. "PindaCredits"
            String sample = economy.format(200L);
            currency = sample.substring(sample.indexOf(' ') + 1);
        }
        return map(
                "uuid", user.uuid().toString(),
                "name", user.name(),
                "rank", user.rankId() == null ? null
                        : map("id", user.rankId(), "name", user.rankName(), "color", user.rankColor(),
                        "weight", user.weight(), "operator", user.operator()),
                "permissions", user.granted(),
                "server", module.serverName(),
                "version", plugin.version(),
                "features", map(
                        "ranks", rankModule != null,
                        "economy", economy != null,
                        "shop", shops() != null,
                        "moderation", moderation != null,
                        "skills", enabled(nl.pinda.framework.modules.skills.SkillsModule.class) != null),
                "ranks", ranks,
                "currency", currency,
                "maxTempBan", moderation == null ? 0 : moderation.maxTempBan(),
                "idleMinutes", module.idleMillis() / 60_000L);
    }
}
