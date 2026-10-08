package nl.pinda.framework.modules.scoreboard;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Een eigen scoreboard per speler (voor het scoreboard rechts) zou de teams van het gewone
 * scoreboard verbergen: kleuren van /team, naamlabels en teams van andere plugins. Daarom
 * nemen we die teams steeds over.
 */
final class TeamSync {

    private TeamSync() {
    }

    static void copy(Scoreboard main, Scoreboard target) {
        Set<String> names = new HashSet<>();
        for (Team team : main.getTeams()) {
            try {
                names.add(team.getName());
                Team copy = target.getTeam(team.getName());
                if (copy == null) {
                    copy = target.registerNewTeam(team.getName());
                }
                copy.displayName(team.displayName());
                copy.prefix(team.prefix());
                copy.suffix(team.suffix());
                copy.color(team.hasColor() ? NamedTextColor.nearestTo(team.color()) : null);
                copy.setAllowFriendlyFire(team.allowFriendlyFire());
                copy.setCanSeeFriendlyInvisibles(team.canSeeFriendlyInvisibles());
                for (Team.Option option : Team.Option.values()) {
                    copy.setOption(option, team.getOption(option));
                }
                Set<String> entries = team.getEntries();
                for (String entry : List.copyOf(copy.getEntries())) {
                    if (!entries.contains(entry)) {
                        copy.removeEntry(entry);
                    }
                }
                for (String entry : entries) {
                    if (!copy.hasEntry(entry)) {
                        copy.addEntry(entry);
                    }
                }
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // Team net verwijderd: de volgende keer weer goed
            }
        }
        for (Team team : List.copyOf(target.getTeams())) {
            if (!names.contains(team.getName())) {
                team.unregister();
            }
        }
    }
}
