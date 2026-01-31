package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

public class GameRulesController implements Listener {

    private final DeadCyclePlugin plugin;

    public GameRulesController(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        // на всякий случай сразу
        for (World w : plugin.getServer().getWorlds()) {
            w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        e.getWorld().setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
    }
}
