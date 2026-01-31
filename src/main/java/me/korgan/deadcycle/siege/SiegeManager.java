package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

public class SiegeManager {

    private final DeadCyclePlugin plugin;
    private final BlockHealthManager blocks;

    private BukkitTask task;

    public SiegeManager(DeadCyclePlugin plugin, BlockHealthManager blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    public void onNightStart(int dayCount) {
        stop();

        if (!plugin.getConfig().getBoolean("siege.enabled", true)) return;
        if (!plugin.base().isEnabled()) return;

        int startDay = plugin.getConfig().getInt("siege.start_day", 3);
        if (dayCount < startDay) return;

        // если на базе никого — осада не нужна (можно поменять потом)
        if (!plugin.base().hasAnyOnBase()) return;

        int interval = Math.max(1, plugin.getConfig().getInt("siege.tick_interval", 10));

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(dayCount), interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick(int dayCount) {
        if (!plugin.getConfig().getBoolean("siege.enabled", true)) return;
        if (!plugin.base().isEnabled()) return;

        // если ночью никто не на базе — можно выключить осаду, чтобы не грузить
        if (!plugin.base().hasAnyOnBase()) return;

        Location center = plugin.base().getCenter();
        if (center.getWorld() == null) return;

        int extra = plugin.getConfig().getInt("siege.extra_radius", 8);
        double zoneRadius = plugin.base().getRadius() + extra;

        double baseDamage = plugin.getConfig().getDouble("siege.block_damage_base", 2.0);
        double perDay = plugin.getConfig().getDouble("siege.block_damage_per_day", 0.3);
        int damage = (int) Math.max(1, Math.round(baseDamage + dayCount * perDay));

        double zoneSq = zoneRadius * zoneRadius;

        for (Entity ent : center.getWorld().getNearbyEntities(center, zoneRadius, 8, zoneRadius)) {
            if (!(ent instanceof Zombie z)) continue;

            // ломают только "наши" зомби
            Byte mark = z.getPersistentDataContainer().get(plugin.zombie().zombieMarkKey(), PersistentDataType.BYTE);
            if (mark == null || mark != (byte) 1) continue;

            // если реально в зоне
            if (z.getLocation().distanceSquared(center) > zoneSq) continue;

            // блок “перед носом” зомби
            Block target = getFrontSolidBlock(z);
            if (target == null) continue;

            Material m = target.getType();
            if (!blocks.isBreakable(m)) continue;

            blocks.damage(target, damage);
        }
    }

    private Block getFrontSolidBlock(Zombie z) {
        Location loc = z.getLocation();
        // берём точку чуть выше ног, чтобы не ломал землю постоянно
        Location eye = loc.clone().add(0, 1.0, 0);

        // направление взгляда
        var dir = eye.getDirection().normalize();

        // проверяем 2 шага вперёд
        for (double step = 0.8; step <= 1.8; step += 0.5) {
            Location p = eye.clone().add(dir.clone().multiply(step));
            Block b = p.getBlock();
            if (b.getType() == Material.AIR) continue;
            if (!b.getType().isSolid()) continue;
            return b;
        }
        return null;
    }
}
