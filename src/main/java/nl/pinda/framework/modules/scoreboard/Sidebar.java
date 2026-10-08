package nl.pinda.framework.modules.scoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Het scoreboard rechts in beeld van één speler. Elke regel heeft tekst links en (optioneel)
 * een waarde rechts. Alleen regels die veranderen worden opnieuw verstuurd.
 */
final class Sidebar {

    static final int MAX_LINES = 15;

    private final Scoreboard board;
    private final Objective objective;
    private final List<SidebarLine> shown = new ArrayList<>();
    private Component title;

    Sidebar(Scoreboard board, Component title) {
        this.board = board;
        this.title = title;
        this.objective = board.registerNewObjective("pinda_sidebar", Criteria.DUMMY, title);
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.objective.numberFormat(NumberFormat.blank());
    }

    Scoreboard board() {
        return board;
    }

    void update(Component newTitle, List<SidebarLine> lines) {
        if (!newTitle.equals(title)) {
            objective.displayName(newTitle);
            title = newTitle;
        }
        int count = Math.min(MAX_LINES, lines.size());
        boolean rewrite = count != shown.size();
        for (int index = 0; index < count; index++) {
            SidebarLine line = lines.get(index);
            if (!rewrite && shown.get(index).equals(line)) {
                continue;
            }
            Score score = objective.getScore(entry(index));
            score.setScore(count - index);
            score.customName(line.left());
            score.numberFormat(line.right() == null ? NumberFormat.blank() : NumberFormat.fixed(line.right()));
        }
        for (int index = count; index < shown.size(); index++) {
            board.resetScores(entry(index));
        }
        shown.clear();
        shown.addAll(lines.subList(0, count));
    }

    /** Onzichtbare namen per regel (§0§r, §1§r, ...): oude clients zien dan een lege regel. */
    private static String entry(int index) {
        return "§" + Integer.toHexString(index) + "§r";
    }
}
