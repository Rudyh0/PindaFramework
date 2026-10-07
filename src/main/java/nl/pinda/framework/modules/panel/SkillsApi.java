package nl.pinda.framework.modules.panel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import nl.pinda.framework.modules.moderation.Durations;
import nl.pinda.framework.modules.skills.Skill;
import nl.pinda.framework.modules.skills.SkillCurve;
import nl.pinda.framework.modules.skills.SkillService;
import nl.pinda.framework.modules.skills.SkillsModule;
import nl.pinda.framework.player.KnownPlayer;

/** Skills in het paneel: ranglijsten, XP-boost en levels van spelers aanpassen. */
final class SkillsApi extends PanelApi {

    SkillsApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/skills", PanelUser.SKILLS, this::overview);
        server.post("/api/skills/boost", PanelUser.SKILLS_EDIT, this::boost);
        server.post("/api/players/{uuid}/skills", PanelUser.SKILLS_EDIT, this::edit);
    }

    private SkillService service() throws ApiException {
        return require(SkillsModule.class, "skills").service();
    }

    private Object overview(PanelRequest request) throws Exception {
        SkillService service = service();
        SkillCurve curve = service.curve();
        List<Map<String, Object>> skills = new ArrayList<>();
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("total", entries(await(service.top(null, 10))));
        for (Skill skill : Skill.values()) {
            skills.add(map("id", skill.id(), "name", service.name(skill), "enabled", service.isEnabled(skill)));
            top.put(skill.id(), entries(await(service.top(skill, 10))));
        }
        List<Map<String, Object>> milestones = new ArrayList<>();
        for (int level : new int[]{1, 10, 25, 50, 75, curve.maxLevel()}) {
            if (level <= curve.maxLevel()) {
                milestones.add(map("level", level, "xp", curve.xpFor(level)));
            }
        }
        return map("skills", skills, "maxLevel", curve.maxLevel(), "curve", milestones, "top", top, "boost", boost(service));
    }

    static Map<String, Object> boost(SkillService service) {
        long until = service.boostUntil();
        return until == 0 ? null : map("multiplier", service.boostMultiplier(), "until", until, "by", service.boostBy());
    }

    private static List<Map<String, Object>> entries(List<SkillService.TopEntry> list) {
        List<Map<String, Object>> result = new ArrayList<>();
        int position = 1;
        for (SkillService.TopEntry entry : list) {
            result.add(map("position", position++, "uuid", entry.uuid().toString(), "name", entry.name(),
                    "level", entry.level(), "xp", Math.floor(entry.xp())));
        }
        return result;
    }

    /** Het skills-blok op het spelersprofiel. */
    static Map<String, Object> profile(PanelApi api, UUID uuid, boolean canEdit) throws Exception {
        SkillsModule skillsModule = api.enabled(SkillsModule.class);
        if (skillsModule == null) {
            return null;
        }
        SkillService service = skillsModule.service();
        SkillCurve curve = service.curve();
        Map<Skill, Double> xp = await(service.xpOf(uuid));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Skill skill : Skill.values()) {
            double value = xp.getOrDefault(skill, 0.0);
            int level = curve.levelOf(value);
            boolean max = level >= curve.maxLevel();
            list.add(map("id", skill.id(), "name", service.name(skill), "level", level, "xp", Math.floor(value),
                    "next", max ? null : curve.xpFor(level + 1), "progress", curve.progress(value), "max", max));
        }
        return map("skills", list, "total", service.totalLevel(xp), "maxTotal", curve.maxLevel() * Skill.values().length,
                "canEdit", canEdit);
    }

    private Object boost(PanelRequest request) throws Exception {
        SkillService service = service();
        if (request.body().has("stop") && request.body().get("stop").getAsBoolean()) {
            boolean stopped = sync(() -> service.stopBoost(true));
            if (!stopped) {
                throw ApiException.badRequest("Er loopt geen XP-boost.");
            }
            module.log().add(request, "xp-boost gestopt", null, null);
            return map("boost", null);
        }
        String multiplierText = request.string("multiplier", "Vul een vermenigvuldiger in.").replace(',', '.');
        double multiplier;
        try {
            multiplier = Double.parseDouble(multiplierText);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Ongeldige vermenigvuldiger.");
        }
        if (multiplier <= 0 || multiplier > 10) {
            throw ApiException.badRequest("Kies een vermenigvuldiger tussen 0 en 10.");
        }
        String durationText = request.string("duration", "Vul een duur in.");
        long duration = Durations.parse(durationText);
        if (duration <= 0) {
            throw ApiException.badRequest("Ongeldige duur. Gebruik bijvoorbeeld 30m, 2u of 1d.");
        }
        String by = request.user().name();
        double chosen = multiplier;
        sync(() -> {
            service.startBoost(chosen, duration, by);
            return null;
        });
        module.log().add(request, "xp-boost", null, multiplierText + "x · " + durationText);
        return map("boost", boost(service));
    }

    private Object edit(PanelRequest request) throws Exception {
        SkillService service = service();
        UUID uuid = request.uuidParam("uuid");
        String action = request.string("action", "Kies een actie.").toLowerCase(Locale.ROOT);
        String skillText = request.optString("skill");
        Skill skill = skillText == null ? null : service.find(skillText);
        if (skill == null && !action.equals("reset")) {
            throw ApiException.badRequest("Kies een skill.");
        }
        KnownPlayer known = new PlayersApi(module).known(uuid);
        SkillCurve curve = service.curve();
        String details;
        switch (action) {
            case "level" -> {
                int level = number(request, curve.maxLevel());
                await(service.setXp(uuid, skill, curve.xpFor(level)));
                details = service.name(skill) + " level " + level;
            }
            case "xp" -> {
                int amount = number(request, 100_000_000);
                Map<Skill, Double> xp = await(service.xpOf(uuid));
                await(service.setXp(uuid, skill, xp.getOrDefault(skill, 0.0) + amount));
                details = service.name(skill) + " +" + amount + " XP";
            }
            case "reset" -> {
                for (Skill each : Skill.values()) {
                    if (skill == null || skill == each) {
                        await(service.setXp(uuid, each, 0));
                    }
                }
                details = skill == null ? "alle skills" : service.name(skill);
            }
            default -> throw ApiException.badRequest("Onbekende actie.");
        }
        module.log().add(request, "skills " + action, known.name(), details);
        return profile(this, uuid, true);
    }

    private static int number(PanelRequest request, int max) throws ApiException {
        String text = request.string("value", "Vul een getal in.");
        try {
            int value = Integer.parseInt(text.trim());
            if (value < 0 || value > max) {
                throw ApiException.badRequest("Kies een getal van 0 tot " + max + ".");
            }
            return value;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + text + "' is geen geldig getal.");
        }
    }
}
