package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class BuilderToolListener implements Listener {

    private final DeadCyclePlugin plugin;

    public BuilderToolListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        if (it == null) return;

        // только билдеру
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.BUILDER) return;

        // наковальня -> ремонт
        if (plugin.kit().isBuilderRepairTool(it)) {
            e.setCancelled(true);
            plugin.repairGui().open(p);
            return;
        }

        // smithing table -> меню апгрейда стен
        if (plugin.kit().isBuilderWallUpgradeTool(it)) {
            e.setCancelled(true);
            plugin.wallUpgradeGui().open(p);
        }
    }
}
