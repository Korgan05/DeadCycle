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

        // don't auto-remove blocks placed by admins/operators
        var player = e.getPlayer();
        if (player != null && (player.isOp() || player.hasPermission("deadcycle.admin")))
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
            } catch (Throwable t) {
                logSuppressed("block data comparison", t);
                return;
            }

            try {
                now.setType(Material.AIR, false);
            } catch (Throwable t) {
                logSuppressed("set AIR without physics", t);
                try {
                    now.setType(Material.AIR);
                } catch (Throwable t2) {
                    logSuppressed("set AIR fallback", t2);
                }
            }
        }, 20L * 10L);
    }

    private void logSuppressed(String context, Throwable t) {
        if (t == null)
            return;
        plugin.getLogger().fine("[TemporaryBlocks] " + context + ": "
                + t.getClass().getSimpleName() + " - " + t.getMessage());
    }
}
