package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;

public class PlayerRulesListener implements Listener {

    private final DeadCyclePlugin plugin;

    public PlayerRulesListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    // после смерти вещи НЕ выпадают
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        // Поведение по умолчанию в этом режиме: инвентарь сохраняется.
        // Но ресурсы должны пропадать при смерти (по просьбе).
        boolean keepInv = plugin.getConfig().getBoolean("death.keep_inventory", true);
        boolean removeRes = plugin.getConfig().getBoolean("death.remove_resources", true);

        e.setDroppedExp(0);
        e.setKeepLevel(true);

        if (keepInv) {
            e.setKeepInventory(true);
            e.getDrops().clear();
        }

        if (!removeRes) return;

        // 1) на всякий случай убираем из дропа (если keepInventory=false в будущем)
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (drop == null) continue;
            if (isResource(drop.getType())) it.remove();
        }

        // 2) и главное: убираем из инвентаря игрока (при keepInventory=true)
        Player p = e.getEntity();
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (item == null) continue;
            if (isResource(item.getType())) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    private boolean isResource(Material m) {
        if (m == null) return false;
        // ресурсами считаем то, что используется/продаётся в режиме
        if (m == Material.COBBLESTONE) return true;
        if (m == Material.COAL) return true;
        if (m == Material.IRON_INGOT) return true;
        if (m == Material.GOLD_INGOT) return true;
        if (m == Material.DIAMOND) return true;

        // и всё, что конвертится в очки базы
        try {
            return plugin.baseResources() != null && plugin.baseResources().pointsPer(m) > 0;
        } catch (Throwable ignored) {
            return false;
        }
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
