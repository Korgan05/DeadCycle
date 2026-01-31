package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class BaseManager {

    private final DeadCyclePlugin plugin;

    private boolean enabled = false;
    private World world;
    private Location center;
    private int radius = 30;

    public BaseManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration cfg = plugin.getConfig();

        enabled = cfg.getBoolean("base.enabled", false);

        String worldName = cfg.getString("base.world", "world");
        world = Bukkit.getWorld(worldName);
        if (world == null) {
            // если мир ещё не прогрузился при старте — попробуем позже, но центр пока null
            center = null;
            radius = cfg.getInt("base.radius", 30);
            return;
        }

        int x = cfg.getInt("base.x", 0);
        int y = cfg.getInt("base.y", 64);
        int z = cfg.getInt("base.z", 0);
        radius = cfg.getInt("base.radius", 30);

        center = new Location(world, x + 0.5, y, z + 0.5);
    }

    public boolean isEnabled() {
        return enabled && center != null && world != null;
    }

    public Location getCenter() {
        return center;
    }

    public int getRadius() {
        return radius;
    }

    public void setBase(Location loc, int radius) {
        if (loc == null || loc.getWorld() == null) return;

        this.world = loc.getWorld();
        this.center = loc.clone();
        this.center.setX(Math.floor(this.center.getX()) + 0.5);
        this.center.setZ(Math.floor(this.center.getZ()) + 0.5);

        this.radius = Math.max(5, radius);
        this.enabled = true;

        // сохраняем в конфиг
        plugin.getConfig().set("base.enabled", true);
        plugin.getConfig().set("base.world", world.getName());
        plugin.getConfig().set("base.x", center.getBlockX());
        plugin.getConfig().set("base.y", center.getBlockY());
        plugin.getConfig().set("base.z", center.getBlockZ());
        plugin.getConfig().set("base.radius", this.radius);
        plugin.saveConfig();
    }

    public boolean isOnBase(Location loc) {
        if (!isEnabled() || loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getUID().equals(world.getUID())) return false;

        double dx = loc.getX() - center.getX();
        double dz = loc.getZ() - center.getZ();
        return (dx * dx + dz * dz) <= (radius * radius);
    }

    public int countOnBase() {
        if (!isEnabled()) return 0;

        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOnline() && !p.isDead() && isOnBase(p.getLocation())) {
                count++;
            }
        }
        return count;
    }

    public boolean hasAnyOnBase() {
        return countOnBase() > 0;
    }
}
