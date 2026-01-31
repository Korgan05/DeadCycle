package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public class BaseBuildProtectionListener implements Listener {

    private final DeadCyclePlugin plugin;

    public BaseBuildProtectionListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!plugin.base().isEnabled()) return;

        // Запрет только в радиусе базы
        if (plugin.base().isOnBase(e.getBlockPlaced().getLocation())) {
            e.setCancelled(true);
            // без сообщений спама — если хочешь, скажи, добавлю кулдаун и сообщение
        }
    }
}
