package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Слушатель для активации скиллов (ПКМ по активатору).
 */
public class SkillListener implements Listener {

    private final DeadCyclePlugin plugin;

    public SkillListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        // Только ПКМ
        if (e.getAction().toString().startsWith("LEFT"))
            return;

        Player p = e.getPlayer();
        ItemStack item = e.getItem();

        // Проверяем основную руку И оффхенд
        if (item == null) {
            item = p.getInventory().getItemInOffHand();
        }

        if (item == null)
            return;

        // Проверяем, это ли активатор скилла
        if (!plugin.kit().isSkillActivator(item))
            return;

        e.setCancelled(true);

        // Определяем какой скилл использовать по предмету
        String skillId = plugin.kit().getSkillIdFromItem(item);
        if (skillId == null)
            return;

        // Пытаемся активировать скилл
        boolean success = plugin.skills().tryActivate(p, skillId);
        if (!success) {
            SkillManager skillManager = plugin.skills();
            Skill skill = skillManager.getSkill(skillId);
            if (skill != null) {
                String err = skill.getErrorMessage(p);
                if (err != null)
                    p.sendMessage(err);
            }
        }
    }
}
