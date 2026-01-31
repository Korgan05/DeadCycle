package me.korgan.deadcycle.zombies;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;

public class ZombieWaveManager {

    private final DeadCyclePlugin plugin;
    private final Random rng = new Random();
    private BukkitTask task;
    private boolean spawning = false;

    private final NamespacedKey mark;

    public ZombieWaveManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.mark = new NamespacedKey(plugin, "dc_zombie");
    }

    public NamespacedKey zombieMarkKey() { return mark; }
    public boolean isPluginSpawning() { return spawning; }

    public void startNight(int day) {
        stopNight();

        if (!plugin.getConfig().getBoolean("zombies.enabled", true)) return;

        int base = plugin.getConfig().getInt("zombies.base_per_player", 2);
        int add = plugin.getConfig().getInt("zombies.per_night_add", 1);

        // 🔥 мягкий рост
        int perPlayer = Math.min(8, base + (day / 2) + add);

        double hp = plugin.getConfig().getDouble("difficulty.zombie_hp_base", 16)
                + day * plugin.getConfig().getDouble("difficulty.zombie_hp_per_day", 1.2);

        double dmg = plugin.getConfig().getDouble("difficulty.zombie_dmg_base", 2)
                + day * plugin.getConfig().getDouble("difficulty.zombie_dmg_per_day", 0.2);

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) return;

            if (plugin.base().isEnabled() && plugin.base().hasAnyOnBase()) {
                Location c = plugin.base().getCenter();
                for (int i = 0; i < perPlayer * Bukkit.getOnlinePlayers().size(); i++) {
                    spawnZombie(c, hp, dmg, true);
                }
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    for (int i = 0; i < perPlayer; i++) {
                        spawnZombie(p.getLocation(), hp, dmg, false);
                    }
                }
            }

        }, 40L, 20L * 15L); // каждые 15 сек

    }

    public void stopNight() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void spawnZombie(Location center, double hp, double dmg, boolean base) {
        World w = center.getWorld();
        if (w == null) return;

        Location loc = base
                ? randomOutsideBase(center)
                : randomNear(center);

        if (loc == null) return;

        try {
            spawning = true;
            w.spawn(loc, Zombie.class, z -> {
                z.getPersistentDataContainer().set(mark, PersistentDataType.BYTE, (byte) 1);
                z.setBaby(false);
                z.setCanPickupItems(false);

                EntityEquipment eq = z.getEquipment();
                if (eq != null) {
                    eq.clear();
                    eq.setItemInMainHandDropChance(0);
                    eq.setHelmetDropChance(0);
                }

                AttributeInstance mh = z.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (mh != null) {
                    mh.setBaseValue(hp);
                    z.setHealth(hp);
                }

                AttributeInstance ad = z.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
                if (ad != null) ad.setBaseValue(dmg);
            });
        } finally {
            spawning = false;
        }
    }

    private Location randomNear(Location c) {
        double r = 12 + rng.nextInt(20);
        double a = rng.nextDouble() * Math.PI * 2;
        int x = (int) (c.getX() + Math.cos(a) * r);
        int z = (int) (c.getZ() + Math.sin(a) * r);
        int y = c.getWorld().getHighestBlockYAt(x, z) + 1;
        return new Location(c.getWorld(), x + .5, y, z + .5);
    }

    private Location randomOutsideBase(Location c) {
        int baseR = plugin.base().getRadius();
        int min = baseR + 10;
        int max = baseR + 30;

        double r = min + rng.nextDouble() * (max - min);
        double a = rng.nextDouble() * Math.PI * 2;

        int x = (int) (c.getX() + Math.cos(a) * r);
        int z = (int) (c.getZ() + Math.sin(a) * r);
        int y = c.getWorld().getHighestBlockYAt(x, z) + 1;
        return new Location(c.getWorld(), x + .5, y, z + .5);
    }
}
