package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public class TemporaryBlocksListener implements Listener {

    private final DeadCyclePlugin plugin;

    public TemporaryBlocksListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block placed = e.getBlockPlaced();
        if (placed == null)
            return;

        Material type = placed.getType();
        if (type == Material.AIR)
            return;

        Location loc = placed.getLocation();
        String data = placed.getBlockData().getAsString();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World w = loc.getWorld();
            if (w == null)
                return;

            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            if (!w.isChunkLoaded(cx, cz))
                return;

            Block now = w.getBlockAt(loc);
            if (now.getType() != type)
                return;

            try {
                if (!now.getBlockData().getAsString().equals(data))
                    return;
            } catch (Throwable ignored) {
            }

            try {
                now.setType(Material.AIR, false);
            } catch (Throwable t) {
                try {
                    now.setType(Material.AIR);
                } catch (Throwable ignored) {
                }
            }
        }, 20L * 10L);
    }
}
