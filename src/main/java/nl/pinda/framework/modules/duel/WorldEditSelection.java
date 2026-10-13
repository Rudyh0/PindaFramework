package nl.pinda.framework.modules.duel;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.entity.Player;

/** De selectie van een speler met de WorldEdit-bijl. Alleen gebruiken als WorldEdit aan staat. */
final class WorldEditSelection {

    private WorldEditSelection() {
    }

    /** De selectie in de wereld waar de speler is, of null als hij nog niets (helemaal) selecteerde. */
    static Area of(Player player) {
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
        try {
            Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return Area.of(player.getWorld().getName(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        } catch (IncompleteRegionException e) {
            return null;
        }
    }
}
