package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class PlayerRulesListener implements Listener {

    private final DeadCyclePlugin plugin;

    public PlayerRulesListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    // после смерти вещи НЕ выпадают
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(true); // на Paper работает, но мы все равно чистим drops
        e.setKeepLevel(true);
    }

    // респавн на базе (если база включена)
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!plugin.base().isEnabled()) return;

        Location spawn = getBaseSpawn();
        if (spawn != null) {
            e.setRespawnLocation(spawn);
        }
    }

    // при заходе — телепорт на базу (если включена)
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.base().isEnabled()) return;

        Player p = e.getPlayer();
        Location spawn = getBaseSpawn();
        if (spawn == null) return;

        // чуть позже, чтобы игрок успел прогрузиться
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.teleport(spawn);
        }, 10L);
    }

    private Location getBaseSpawn() {
        Location c = plugin.base().getCenter();
        if (c == null) return null;

        // спавним на 1 блок выше центра + корректная высота
        Location spawn = c.clone();

        // поднимаем на безопасную высоту
        // (если центр в земле, будет поднято)
        int y = spawn.getWorld().getHighestBlockYAt(spawn) + 1;
        spawn.setY(y);

        // чуть сместим в центр блока
        spawn.setX(Math.floor(spawn.getX()) + 0.5);
        spawn.setZ(Math.floor(spawn.getZ()) + 0.5);

        return spawn;
    }
}
