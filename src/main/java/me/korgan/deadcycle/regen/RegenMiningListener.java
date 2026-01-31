package me.korgan.deadcycle.regen;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class RegenMiningListener implements Listener {

    private final DeadCyclePlugin plugin;

    public RegenMiningListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();

        if (p.getGameMode() == GameMode.CREATIVE) return;

        // опыт за копание даём ТОЛЬКО майнеру
        if (plugin.kit().getKit(p.getUniqueId()) == KitManager.Kit.MINER) {
            plugin.progress().addMinerExp(p, 2);
        }

        // ❌ никаких денег тут больше нет
    }
}
