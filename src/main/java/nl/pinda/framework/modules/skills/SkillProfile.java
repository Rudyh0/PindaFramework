package nl.pinda.framework.modules.skills;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** De XP van één speler in alle skills. */
public final class SkillProfile {

    private final UUID uuid;
    private final Map<Skill, Double> xp = new EnumMap<>(Skill.class);
    private boolean dirty;

    // Om snel achter elkaar verdiende XP samen te tonen in de actionbar
    private Skill comboSkill;
    private double comboAmount;
    private long comboTime;
    private long quietUntil;

    public SkillProfile(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public synchronized double xp(Skill skill) {
        return xp.getOrDefault(skill, 0.0);
    }

    public synchronized Map<Skill, Double> snapshot() {
        Map<Skill, Double> copy = new EnumMap<>(Skill.class);
        copy.putAll(xp);
        return copy;
    }

    synchronized void setXp(Skill skill, double value) {
        xp.put(skill, Math.max(0, value));
        dirty = true;
    }

    synchronized void addXp(Skill skill, double amount) {
        xp.merge(skill, amount, Double::sum);
        dirty = true;
    }

    synchronized boolean dirty() {
        return dirty;
    }

    /** Een kopie om op te slaan als er iets veranderd is (en markeert het als opgeslagen), anders null. */
    synchronized Map<Skill, Double> takeDirty() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        return snapshot();
    }

    /** Telt XP op bij de reeks van deze skill als die van de afgelopen seconden is, en geeft het totaal. */
    synchronized double combo(Skill skill, double amount, long now, long windowMillis) {
        if (skill == comboSkill && now - comboTime <= windowMillis) {
            comboAmount += amount;
        } else {
            comboSkill = skill;
            comboAmount = amount;
        }
        comboTime = now;
        return comboAmount;
    }

    /** Na een level-up even geen XP-meldingen, zodat de level-up melding zichtbaar blijft. */
    synchronized boolean quiet(long now) {
        return now < quietUntil;
    }

    synchronized void quietFor(long now, long millis) {
        quietUntil = now + millis;
        comboSkill = null;
    }
}
