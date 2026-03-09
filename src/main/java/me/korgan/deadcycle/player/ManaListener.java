package me.korgan.deadcycle.player;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class ManaListener implements Listener {

    private final DeadCyclePlugin plugin;

    public ManaListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (plugin.mana() != null) {
            plugin.mana().onPlayerJoin(e.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (plugin.mana() != null) {
            plugin.mana().onPlayerQuit(e.getPlayer().getUniqueId());
        }
    }
}
