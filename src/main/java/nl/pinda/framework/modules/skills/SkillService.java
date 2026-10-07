package nl.pinda.framework.modules.skills;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.economy.EconomyModule;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.economy.Money;
import nl.pinda.framework.storage.Database;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Het hart van de skills: XP bijhouden, levels uitrekenen, beloningen geven, meldingen tonen
 * en de ranglijsten. XP wordt in het geheugen bijgehouden en elke 30 seconden opgeslagen.
 */
public final class SkillService {

    /** De instelling (in /instellingen) voor de XP-melding in de actionbar. */
    public static final String SETTING = "skill-xp";

    private static final String BOOST_MULTIPLIER = "skill-boost.multiplier";
    private static final String BOOST_UNTIL = "skill-boost.until";
    private static final String BOOST_BY = "skill-boost.by";
    private static final long PRELOAD_TIMEOUT_MS = 60_000L;

    /** Een regel op de ranglijst. */
    public record TopEntry(UUID uuid, String name, int level, double xp) {
    }

    private final PindaFramework plugin;
    private final Map<UUID, SkillProfile> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loadedAt = new ConcurrentHashMap<>();
    private volatile SkillRules rules;

    SkillService(PindaFramework plugin, SkillRules rules) {
        this.plugin = plugin;
        this.rules = rules;
    }

    void rules(SkillRules rules) {
        this.rules = rules;
    }

    SkillRules rules() {
        return rules;
    }

    public SkillCurve curve() {
        return rules.curve;
    }

    public boolean isEnabled(Skill skill) {
        return rules.isEnabled(skill);
    }

    /** De naam van een skill in een taal, bijv. "Mijnbouw". */
    public String name(Skill skill, String code) {
        String name = plugin.lang().raw(code, "skills.names." + skill.id());
        return name == null ? skill.id() : name;
    }

    public String name(Skill skill) {
        return name(skill, plugin.lang().defaultLanguage());
    }

    public Skill find(String input) {
        return Skill.find(input, skill -> name(skill));
    }

    // ============================================================ profielen

    /** Het profiel van een online speler. Laadt het meteen als dat nog niet gebeurd is. */
    public SkillProfile profile(Player player) {
        SkillProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) {
            try {
                profile = load(player.getUniqueId()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Kon de skills van " + player.getName() + " niet laden", e);
            }
            profiles.putIfAbsent(player.getUniqueId(), profile);
            profile = profiles.get(player.getUniqueId());
            loadedAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
        return profile;
    }

    /** Laadt een profiel uit de database (niet op de hoofdthread afgerond). */
    CompletableFuture<SkillProfile> load(UUID uuid) {
        return plugin.database().query(connection -> {
            SkillProfile profile = new SkillProfile(uuid);
            try (PreparedStatement statement = connection.prepareStatement("SELECT skill, xp FROM pinda_skills WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Skill skill = Skill.find(result.getString("skill"), null);
                        if (skill != null) {
                            profile.setXp(skill, result.getDouble("xp"));
                        }
                    }
                }
            }
            profile.takeDirty();
            return profile;
        });
    }

    /** Bij het inloggen alvast laden, zodat de hoofdthread niet hoeft te wachten. */
    void preload(UUID uuid) throws Exception {
        if (profiles.containsKey(uuid)) {
            return;
        }
        SkillProfile profile = load(uuid).get(10, TimeUnit.SECONDS);
        profiles.putIfAbsent(uuid, profile);
        loadedAt.put(uuid, System.currentTimeMillis());
    }

    /** Bij het uitloggen: opslaan en uit het geheugen halen. */
    void unload(UUID uuid) {
        SkillProfile profile = profiles.remove(uuid);
        loadedAt.remove(uuid);
        if (profile != null) {
            save(List.of(profile));
        }
    }

    /** Ruimt profielen op van spelers die wel geladen werden maar nooit online kwamen. */
    void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, SkillProfile>> iterator = profiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SkillProfile> entry = iterator.next();
            UUID uuid = entry.getKey();
            if (plugin.getServer().getPlayer(uuid) != null) {
                continue;
            }
            Long loaded = loadedAt.get(uuid);
            if (loaded == null || now - loaded > PRELOAD_TIMEOUT_MS) {
                save(List.of(entry.getValue()));
                iterator.remove();
                loadedAt.remove(uuid);
            }
        }
    }

    /** Slaat alle gewijzigde profielen op. */
    public CompletableFuture<Void> flush() {
        return save(new ArrayList<>(profiles.values()));
    }

    private CompletableFuture<Void> save(List<SkillProfile> list) {
        Map<UUID, Map<Skill, Double>> rows = new LinkedHashMap<>();
        for (SkillProfile profile : list) {
            Map<Skill, Double> changed = profile.takeDirty();
            if (changed != null) {
                rows.put(profile.uuid(), changed);
            }
        }
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return plugin.database().execute(connection -> Database.transaction(connection, tx -> {
            try (PreparedStatement statement = tx.prepareStatement(
                    "INSERT INTO pinda_skills (uuid, skill, xp) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid, skill) DO UPDATE SET xp = excluded.xp")) {
                for (Map.Entry<UUID, Map<Skill, Double>> row : rows.entrySet()) {
                    for (Map.Entry<Skill, Double> skill : row.getValue().entrySet()) {
                        statement.setString(1, row.getKey().toString());
                        statement.setString(2, skill.getKey().id());
                        statement.setDouble(3, skill.getValue());
                        statement.addBatch();
                    }
                }
                statement.executeBatch();
            }
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon skills niet opslaan", error);
            return null;
        });
    }

    void clear() {
        profiles.clear();
        loadedAt.clear();
    }

    // ============================================================ XP verdienen

    /** Geeft een speler XP, met alle regels (werelden, creative, boost) en meldingen. */
    public void addXp(Player player, Skill skill, double base) {
        SkillRules current = rules;
        if (base <= 0 || !current.isEnabled(skill)) {
            return;
        }
        if (current.disabledWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE && !current.creativeGivesXp) {
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        double amount = base * current.xpMultiplier * boostMultiplier();
        if (amount <= 0) {
            return;
        }
        SkillProfile profile = profile(player);
        int before = current.curve.levelOf(profile.xp(skill));
        profile.addXp(skill, amount);
        double total = profile.xp(skill);
        int after = current.curve.levelOf(total);
        long now = System.currentTimeMillis();
        if (after > before) {
            levelUp(player, profile, skill, before, after, now);
            return;
        }
        if (profile.quiet(now) || !plugin.settings().isEnabled(player, SETTING)) {
            return;
        }
        double shown = profile.combo(skill, amount, now, current.comboMillis);
        String code = plugin.lang().languageOf(player);
        if (after >= current.curve.maxLevel()) {
            plugin.lang().send(player, "skills.xp-gain-max", Text.p("amount", formatXp(code, shown)),
                    Text.p("skill", name(skill, code)), Text.p("level", after));
        } else {
            plugin.lang().send(player, "skills.xp-gain", Text.p("amount", formatXp(code, shown)),
                    Text.p("skill", name(skill, code)), Text.p("level", after),
                    Text.p("percent", (int) Math.floor(current.curve.progress(total) * 100)));
        }
    }

    private void levelUp(Player player, SkillProfile profile, Skill skill, int from, int to, long now) {
        profile.quietFor(now, 2500L);
        String code = plugin.lang().languageOf(player);
        double money = 0;
        for (int level = from + 1; level <= to; level++) {
            money += rules.reward(level);
        }
        long cents = Money.toCents(money);
        EconomyService economy = economy();
        boolean paid = economy != null && cents > 0;
        if (paid) {
            economy.give(player.getUniqueId(), cents, false, "skill-level", name(skill) + " " + to);
        }
        plugin.lang().send(player, paid ? "skills.level-up-reward" : "skills.level-up",
                Text.p("skill", name(skill, code)), Text.p("level", to),
                Text.p("reward", paid ? economy.format(cents) : ""));
        plugin.lang().send(player, "skills.level-up-actionbar", Text.p("skill", name(skill, code)), Text.p("level", to));
        plugin.theme().play(player, "levelup");
        for (int level = from + 1; level <= to; level++) {
            if (rules.announceLevels.contains(level)) {
                announce(player, skill, level);
            }
        }
    }

    private void announce(Player achiever, Skill skill, int level) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            String code = plugin.lang().languageOf(online);
            plugin.lang().send(online, "skills.announce", Text.p("player", achiever.getName()),
                    Text.p("skill", name(skill, code)), Text.p("level", level));
        }
        plugin.getServer().getConsoleSender().sendMessage(plugin.lang().component(plugin.lang().defaultLanguage(),
                "skills.announce", Text.p("player", achiever.getName()), Text.p("skill", name(skill)), Text.p("level", level)));
    }

    private EconomyService economy() {
        EconomyModule module = plugin.modules().get(EconomyModule.class);
        return module != null && module.isEnabled() ? module.service() : null;
    }

    /** XP als tekst: hele getallen, of met één decimaal als het weinig is ("2,5"). */
    public String formatXp(String code, double xp) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag(code));
        format.setMaximumFractionDigits(xp < 10 && xp != Math.floor(xp) ? 1 : 0);
        return format.format(xp < 10 ? xp : Math.floor(xp));
    }

    // ============================================================ beheer

    /** Zet de XP van een skill (online of offline). */
    public CompletableFuture<Void> setXp(UUID uuid, Skill skill, double xp) {
        double value = Math.max(0, xp);
        SkillProfile cached = profiles.get(uuid);
        if (cached != null) {
            cached.setXp(skill, value);
            return save(List.of(cached));
        }
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_skills (uuid, skill, xp) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid, skill) DO UPDATE SET xp = excluded.xp")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, skill.id());
                statement.setDouble(3, value);
                statement.executeUpdate();
            }
        });
    }

    /** De XP van een speler in alle skills (online of offline; niet op de hoofdthread afgerond). */
    public CompletableFuture<Map<Skill, Double>> xpOf(UUID uuid) {
        SkillProfile cached = profiles.get(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached.snapshot());
        }
        return load(uuid).thenApply(SkillProfile::snapshot);
    }

    /** De plek op de ranglijst per skill (alleen skills met XP). */
    public CompletableFuture<Map<Skill, Integer>> positions(UUID uuid) {
        return flush().thenCompose(ignored -> plugin.database().query(connection -> {
            Map<Skill, Integer> positions = new EnumMap<>(Skill.class);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT me.skill, (SELECT COUNT(*) FROM pinda_skills other
                                      WHERE other.skill = me.skill AND other.xp > me.xp) + 1 AS position
                    FROM pinda_skills me WHERE me.uuid = ? AND me.xp > 0""")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Skill skill = Skill.find(result.getString(1), null);
                        if (skill != null) {
                            positions.put(skill, result.getInt(2));
                        }
                    }
                }
            }
            return positions;
        }));
    }

    /** De ranglijst van een skill, of van het totaal level als skill null is. */
    public CompletableFuture<List<TopEntry>> top(Skill skill, int limit) {
        SkillCurve curve = rules.curve;
        if (skill != null) {
            return flush().thenCompose(ignored -> plugin.database().query(connection -> {
                List<TopEntry> entries = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT s.uuid, COALESCE(p.name, s.uuid) AS name, s.xp
                        FROM pinda_skills s LEFT JOIN pinda_players p ON p.uuid = s.uuid
                        WHERE s.skill = ? AND s.xp > 0 ORDER BY s.xp DESC LIMIT ?""")) {
                    statement.setString(1, skill.id());
                    statement.setInt(2, limit);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            double xp = result.getDouble("xp");
                            entries.add(new TopEntry(UUID.fromString(result.getString("uuid")), result.getString("name"),
                                    curve.levelOf(xp), xp));
                        }
                    }
                }
                return entries;
            }));
        }
        return flush().thenCompose(ignored -> plugin.database().query(connection -> {
            Map<String, String> names = new HashMap<>();
            Map<String, int[]> levels = new HashMap<>();
            Map<String, double[]> xps = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT s.uuid, COALESCE(p.name, s.uuid) AS name, s.skill, s.xp
                    FROM pinda_skills s LEFT JOIN pinda_players p ON p.uuid = s.uuid WHERE s.xp > 0""");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (Skill.find(result.getString("skill"), null) == null) {
                        continue;
                    }
                    String uuid = result.getString("uuid");
                    double xp = result.getDouble("xp");
                    names.put(uuid, result.getString("name"));
                    levels.computeIfAbsent(uuid, id -> new int[1])[0] += curve.levelOf(xp);
                    xps.computeIfAbsent(uuid, id -> new double[1])[0] += xp;
                }
            }
            List<TopEntry> entries = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : levels.entrySet()) {
                entries.add(new TopEntry(UUID.fromString(entry.getKey()), names.get(entry.getKey()),
                        entry.getValue()[0], xps.get(entry.getKey())[0]));
            }
            entries.sort(Comparator.comparingInt(TopEntry::level).thenComparingDouble(TopEntry::xp).reversed());
            return entries.size() > limit ? new ArrayList<>(entries.subList(0, limit)) : entries;
        }));
    }

    /** Totaal level van een set XP-waarden. */
    public int totalLevel(Map<Skill, Double> xp) {
        int total = 0;
        for (Skill skill : Skill.values()) {
            total += rules.curve.levelOf(xp.getOrDefault(skill, 0.0));
        }
        return total;
    }

    // ============================================================ XP-boost

    /** De huidige XP-boost (1.0 = geen boost). */
    public double boostMultiplier() {
        long until = plugin.serverData().getLong(BOOST_UNTIL, 0);
        if (until <= System.currentTimeMillis()) {
            return 1.0;
        }
        return Math.max(0, plugin.serverData().getDouble(BOOST_MULTIPLIER, 1.0));
    }

    /** Tot wanneer de boost loopt (0 = geen boost). */
    public long boostUntil() {
        long until = plugin.serverData().getLong(BOOST_UNTIL, 0);
        return until > System.currentTimeMillis() ? until : 0;
    }

    public String boostBy() {
        return plugin.serverData().get(BOOST_BY, "?");
    }

    /** Start een XP-boost voor iedereen en meldt dat op de server. */
    public void startBoost(double multiplier, long durationMillis, String by) {
        long until = System.currentTimeMillis() + durationMillis;
        plugin.serverData().set(BOOST_MULTIPLIER, multiplier);
        plugin.serverData().set(BOOST_UNTIL, until);
        plugin.serverData().set(BOOST_BY, by);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            String code = plugin.lang().languageOf(online);
            plugin.lang().send(online, "skills.boost-started", Text.p("multiplier", formatMultiplier(code, multiplier)),
                    Text.p("time", durationText(code, durationMillis)));
            plugin.theme().play(online, "levelup");
        }
    }

    /** Stopt de boost. Geeft false als er geen boost liep. */
    public boolean stopBoost(boolean announce) {
        boolean active = boostUntil() > 0;
        plugin.serverData().remove(BOOST_MULTIPLIER);
        plugin.serverData().remove(BOOST_UNTIL);
        plugin.serverData().remove(BOOST_BY);
        if (active && announce) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                plugin.lang().send(online, "skills.boost-ended");
            }
        }
        return active;
    }

    /** Elke minuut: een verlopen boost netjes afsluiten. */
    void checkBoost() {
        long until = plugin.serverData().getLong(BOOST_UNTIL, 0);
        if (until > 0 && until <= System.currentTimeMillis()) {
            plugin.serverData().remove(BOOST_MULTIPLIER);
            plugin.serverData().remove(BOOST_UNTIL);
            plugin.serverData().remove(BOOST_BY);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                plugin.lang().send(online, "skills.boost-ended");
            }
        }
    }

    public String formatMultiplier(String code, double multiplier) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag(code));
        format.setMaximumFractionDigits(2);
        return format.format(multiplier);
    }

    /** Een duur in woorden in de taal van de speler, bijv. "2u 30m". */
    public String durationText(String code, long millis) {
        String raw = plugin.lang().raw(code, "skills.units");
        String[] units = (raw == null ? "d,u,m,s" : raw).split(",");
        long seconds = Math.max(0, millis / 1000);
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        StringBuilder text = new StringBuilder();
        if (days > 0) {
            text.append(days).append(unit(units, 0)).append(' ');
        }
        if (hours > 0) {
            text.append(hours).append(unit(units, 1)).append(' ');
        }
        if (minutes > 0 || text.isEmpty()) {
            // Minder dan een minuut over? Dan tonen we 1 minuut in plaats van 0.
            long shown = text.isEmpty() && minutes == 0 && seconds > 0 ? 1 : minutes;
            text.append(shown).append(unit(units, 2));
        }
        return text.toString().trim();
    }

    private static String unit(String[] units, int index) {
        return index < units.length ? units[index].trim() : "";
    }

    /** Een voortgangsbalk als component, bijv. ■■■■■□□□□□. */
    public Component bar(double progress, int length) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, progress)) * length);
        return plugin.lang().parse("<success>" + "■".repeat(filled) + "</success><dark_gray>" + "■".repeat(length - filled));
    }
}
