package nl.pinda.framework.modules.utility;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.TargetCommand;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

/** /heal [speler]: volle gezondheid, geen honger, geen vuur en geen effecten. */
public final class HealCommand extends TargetCommand {

    public HealCommand(PindaFramework plugin) {
        super(plugin, "heal", "Genees jezelf of een ander", "pinda.heal", "pinda.heal.others");
    }

    @Override
    protected void apply(CommandSender sender, Player target, boolean self) {
        if (target.isDead()) {
            return;
        }
        AttributeInstance maxHealth = target.getAttribute(Attribute.MAX_HEALTH);
        target.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setExhaustion(0f);
        target.setFireTicks(0);
        for (PotionEffect effect : target.getActivePotionEffects()) {
            target.removePotionEffect(effect.getType());
        }
        feedback(sender, target, self, "utility.healed", "utility.healed-other");
    }
}
