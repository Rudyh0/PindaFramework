package nl.pinda.framework.modules.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nl.pinda.framework.modules.discord.DiscordModule;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.homes.HomesModule;
import nl.pinda.framework.modules.moderation.ModerationModule;
import nl.pinda.framework.modules.motd.MotdModule;
import nl.pinda.framework.modules.ranks.Rank;
import nl.pinda.framework.modules.ranks.RankModule;
import nl.pinda.framework.modules.skills.SkillsModule;

/**
 * Inloggen (eenmalige link + 2FA-code), uitloggen en "wie ben ik".
 *
 * <p>Na de link is de sessie nog "wachtend": pas na de juiste code uit de authenticator-app
 * kan de API gebruikt worden. De eerste keer krijg je een QR-code om de app te koppelen.
 */
final class AuthApi extends PanelApi {

    private static final int MAX_ATTEMPTS = 5;

    AuthApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.open("GET", "/api/info", request -> map("server", module.serverName(), "version", plugin.version()));
        server.open("POST", "/api/login", this::login);
        server.open("POST", "/api/login/verify", this::verify);
        server.open("GET", "/api/login/state", this::state);
        server.post("/api/logout", null, this::logout);
        server.get("/api/me", null, this::me);
        server.post("/api/players/{uuid}/2fa/reset", PanelUser.SECURITY, this::resetOther);
    }

    // ============================================================ stap 1: de link

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
        request.exchange().getResponseHeaders().add("Set-Cookie", PanelServer.COOKIE + "=" + session.id
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + module.maxMillis() / 1000
                + (module.secureCookies() ? "; Secure" : ""));
        if (!module.twoFactorRequired()) {
            return finish(request, session, user);
        }
        session.pending = true;
        if (await(module.twoFactor().get(token.uuid())) == null) {
            session.setupSecret = TwoFactor.newSecret();
        }
        return challenge(session);
    }

    /** Wat de browser moet tonen: de QR-code (eerste keer) of het invulveld voor de code. */
    private Map<String, Object> challenge(PanelSessions.Session session) {
        if (session.setupSecret != null) {
            String issuer = module.serverName();
            return map("step", "setup",
                    "qr", TwoFactor.qrDataUrl(TwoFactor.uri(issuer, session.name, session.setupSecret)),
                    "secret", TwoFactor.pretty(session.setupSecret),
                    "issuer", issuer, "account", session.name,
                    "attemptsLeft", MAX_ATTEMPTS - session.attempts);
        }
        return map("step", "code", "account", session.name, "attemptsLeft", MAX_ATTEMPTS - session.attempts);
    }

    /** Na herladen van de pagina: waar was je gebleven? */
    private Object state(PanelRequest request) {
        PanelSessions.Session session = module.sessions().get(request.cookie(PanelServer.COOKIE), module.idleMillis(), module.maxMillis());
        if (session == null || !session.pending) {
            return map("step", "none");
        }
        return challenge(session);
    }

    // ============================================================ stap 2: de code

    private Object verify(PanelRequest request) throws Exception {
        PanelSessions sessions = module.sessions();
        PanelSessions.Session session = sessions.get(request.cookie(PanelServer.COOKIE), module.idleMillis(), module.maxMillis());
        if (session == null || !session.pending) {
            throw new ApiException(401, "Je inlogpoging is verlopen. Typ /panel in-game voor een nieuwe link.");
        }
        if (sessions.blocked(request.ip())) {
            throw new ApiException(429, "Te veel mislukte pogingen. Probeer het over 10 minuten opnieuw.");
        }
        String code = request.string("code", "Vul de code uit je authenticator-app in.");
        String setup = session.setupSecret;
        long step;
        if (setup != null) {
            step = TwoFactor.verify(setup, code, 0);
        } else {
            TwoFactorStore.Entry entry = await(module.twoFactor().get(session.uuid));
            if (entry == null) {
                // Net gereset door een admin: dan alsnog koppelen.
                session.setupSecret = TwoFactor.newSecret();
                return challenge(session);
            }
            step = TwoFactor.verify(entry.secret(), code, entry.lastStep());
        }
        if (step < 0) {
            session.attempts++;
            sessions.failed(request.ip());
            if (session.attempts >= MAX_ATTEMPTS) {
                sessions.remove(session.id);
                throw new ApiException(401, "Te vaak een verkeerde code. Typ /panel in-game voor een nieuwe link.");
            }
            int left = MAX_ATTEMPTS - session.attempts;
            throw ApiException.badRequest("Deze code klopt niet. Je kunt het nog " + left + " keer proberen.");
        }
        if (setup != null) {
            await(module.twoFactor().save(session.uuid, setup, step));
        } else {
            await(module.twoFactor().used(session.uuid, step));
        }
        PanelUser user = await(module.loadUser(session.uuid, session.name));
        if (!user.has(PanelUser.USE)) {
            sessions.remove(session.id);
            throw ApiException.forbidden("Je hebt geen toegang tot het paneel.");
        }
        session.pending = false;
        session.setupSecret = null;
        session.attempts = 0;
        if (setup != null) {
            request.session = session;
            request.user = user;
            module.log().add(request, "2fa gekoppeld", null, null);
        }
        return finish(request, session, user);
    }

    private Object finish(PanelRequest request, PanelSessions.Session session, PanelUser user) throws Exception {
        session.user = user;
        session.userLoaded = System.currentTimeMillis();
        request.session = session;
        request.user = user;
        module.log().add(request, "inloggen", null, null);
        TwoFactorStore.Entry entry = await(module.twoFactor().get(user.uuid()));
        return sync(() -> me(user, entry));
    }

    // ============================================================ uitloggen en "wie ben ik"

    private Object logout(PanelRequest request) {
        module.sessions().remove(request.session.id);
        request.exchange().getResponseHeaders().add("Set-Cookie", PanelServer.COOKIE
                + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (module.secureCookies() ? "; Secure" : ""));
        module.log().add(request, "uitloggen", null, null);
        return null;
    }

    private Object me(PanelRequest request) throws Exception {
        PanelUser user = request.user();
        TwoFactorStore.Entry entry = await(module.twoFactor().get(user.uuid()));
        return sync(() -> me(user, entry));
    }

    private Map<String, Object> me(PanelUser user, TwoFactorStore.Entry twoFactor) {
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
                        "skills", enabled(SkillsModule.class) != null,
                        "motd", enabled(MotdModule.class) != null,
                        "discord", enabled(DiscordModule.class) != null,
                        "homes", enabled(HomesModule.class) != null),
                "ranks", ranks,
                "currency", currency,
                "maxTempBan", moderation == null ? 0 : moderation.maxTempBan(),
                "idleMinutes", module.idleMillis() / 60_000L,
                "twoFactor", map("required", module.twoFactorRequired(), "enabled", twoFactor != null,
                        "since", twoFactor == null ? null : twoFactor.created()));
    }

    // ============================================================ 2FA van een ander resetten

    private Object resetOther(PanelRequest request) throws Exception {
        UUID uuid = request.uuidParam("uuid");
        String name = new PlayersApi(module).known(uuid).name();
        boolean removed = await(module.resetTwoFactor(uuid));
        if (!removed) {
            throw ApiException.badRequest(name + " heeft geen 2FA gekoppeld.");
        }
        module.log().add(request, "2fa gereset", name, null);
        return null;
    }
}
