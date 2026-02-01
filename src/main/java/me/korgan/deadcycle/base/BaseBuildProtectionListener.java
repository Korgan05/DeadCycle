package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

public class BaseBuildProtectionListener implements Listener {

    private final DeadCyclePlugin plugin;

    public BaseBuildProtectionListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!plugin.base().isEnabled()) return;

        // запрет только в радиусе базы
        if (plugin.base().isOnBase(e.getBlockPlaced().getLocation())) {
            // можно дать bypass админам
            Player p = e.getPlayer();
            if (p.hasPermission("deadcycle.admin")) return;

            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!plugin.base().isEnabled()) return;

        // запрет только в радиусе базы
        if (plugin.base().isOnBase(e.getBlock().getLocation())) {
            // можно дать bypass админам
            Player p = e.getPlayer();
            if (p.hasPermission("deadcycle.admin")) return;

            e.setCancelled(true);
        }
    }
}
