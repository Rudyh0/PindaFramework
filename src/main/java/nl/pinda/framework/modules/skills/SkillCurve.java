package nl.pinda.framework.modules.skills;

/**
 * Hoeveel XP elk level kost, met de curve van RuneScape: elk level is ongeveer 10% duurder
 * dan het vorige. Level 0 is de start; level 99 kost bij multiplier 1.0 ruim 14 miljoen XP.
 */
public final class SkillCurve {

    private final long[] totals;

    /**
     * @param maxLevel   het hoogste level (meestal 99)
     * @param multiplier 1.0 = RuneScape, 0.1 = tien keer zo snel
     */
    public SkillCurve(int maxLevel, double multiplier) {
        int max = Math.max(1, Math.min(200, maxLevel));
        double factor = multiplier > 0 ? multiplier : 0.1;
        totals = new long[max + 1];
        double points = 0;
        for (int level = 1; level <= max; level++) {
            points += Math.floor(level + 300.0 * Math.pow(2.0, level / 7.0));
            totals[level] = Math.max(totals[level - 1] + 1, (long) Math.floor(points / 4.0 * factor));
        }
    }

    public int maxLevel() {
        return totals.length - 1;
    }

    /** De totale XP die nodig is voor dit level. */
    public long xpFor(int level) {
        return totals[Math.max(0, Math.min(maxLevel(), level))];
    }

    /** Het level bij deze hoeveelheid XP. */
    public int levelOf(double xp) {
        int low = 0;
        int high = maxLevel();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (totals[middle] <= xp) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** Voortgang naar het volgende level, van 0 tot 1 (1 op het hoogste level). */
    public double progress(double xp) {
        int level = levelOf(xp);
        if (level >= maxLevel()) {
            return 1.0;
        }
        long from = totals[level];
        long to = totals[level + 1];
        return Math.max(0, Math.min(1, (xp - from) / (double) (to - from)));
    }
}
