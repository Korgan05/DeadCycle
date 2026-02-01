package me.korgan.deadcycle.zombies;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.Random;

public class ZombieWaveManager {

    private final DeadCyclePlugin plugin;
    private final Random rng = new Random();
    private BukkitTask spawnTask;
    private BukkitTask targetTask;

    private final NamespacedKey zombieKey;
    private boolean pluginSpawning = false;

    public ZombieWaveManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.zombieKey = new NamespacedKey(plugin, "deadcycle_zombie");
    }

    public boolean isPluginSpawning() { return pluginSpawning; }
    public NamespacedKey zombieMarkKey() { return zombieKey; }

    public void startNight(int dayCount) {
        stopNight();
        if (!plugin.getConfig().getBoolean("zombies.enabled", true)) return;

        int base = plugin.getConfig().getInt("zombies.base_per_player", 2);
        int add  = plugin.getConfig().getInt("zombies.per_night_add", 1);
        int perPlayer = Math.max(1, base + Math.max(0, dayCount - 1) * add);

        double hpBase  = plugin.getConfig().getDouble("difficulty.zombie_hp_base", 16.0);
        double hpPer   = plugin.getConfig().getDouble("difficulty.zombie_hp_per_day", 1.2);
        double dmgBase = plugin.getConfig().getDouble("difficulty.zombie_dmg_base", 2.0);
        double dmgPer  = plugin.getConfig().getDouble("difficulty.zombie_dmg_per_day", 0.2);

        double hp  = hpBase  + dayCount * hpPer;
        double dmg = dmgBase + dayCount * dmgPer;

        Attribute maxHealth = getAttribute("generic.max_health", "max_health");
        Attribute attackDmg = getAttribute("generic.attack_damage", "attack_damage");
        Attribute followRange = getAttribute("generic.follow_range", "follow_range");

        double follow = plugin.getConfig().getDouble("zombies.follow_range", 48.0);

        // волны
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int online = Bukkit.getOnlinePlayers().size();
            if (online <= 0) return;

            // если кто-то на базе — спавним вокруг базы за радиусом
            if (plugin.base().isEnabled() && plugin.base().hasAnyOnBase()) {
                Location center = plugin.base().getCenter();
                int total = perPlayer * online;

                for (int i = 0; i < total; i++) {
                    Location loc = randomSpawnAroundBaseOutsideRadius(center);
                    if (loc == null) continue;
                    spawnZombieClean(loc, hp, dmg, follow, maxHealth, attackDmg, followRange);
                }
                return;
            }

            // иначе — вокруг игроков
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.isOnline() || p.isDead()) continue;

                for (int i = 0; i < perPlayer; i++) {
                    Location loc = randomSpawnNearPlayer(p.getLocation());
                    if (loc == null) continue;
                    spawnZombieClean(loc, hp, dmg, follow, maxHealth, attackDmg, followRange);
                }
            }

        }, 40L, 20L * 20L);

        // “видеть через стены”: периодически принудительно назначаем цель ближайшему игроку
        int scanSeconds = Math.max(1, plugin.getConfig().getInt("zombies.target_scan_seconds", 2));
        targetTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> forceTargets(follow), 20L, scanSeconds * 20L);
    }

    public void stopNight() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (targetTask != null) {
            targetTask.cancel();
            targetTask = null;
        }
    }

    private void forceTargets(double followRange) {
        for (World w : Bukkit.getWorlds()) {
            for (Entity ent : w.getEntities()) {
                if (!(ent instanceof Zombie z)) continue;

                Byte mark = z.getPersistentDataContainer().get(zombieKey, PersistentDataType.BYTE);
                if (mark == null || mark != (byte) 1) continue;

                if (z.isDead()) continue;

                Player nearest = Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.isOnline() && !p.isDead() && p.getWorld() == w)
                        .filter(p -> p.getLocation().distanceSquared(z.getLocation()) <= (followRange * followRange))
                        .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(z.getLocation())))
                        .orElse(null);

                if (nearest != null) {
                    z.setTarget(nearest);
                }
            }
        }
    }

    private void spawnZombieClean(Location loc, double hp, double dmg, double follow,
                                  Attribute maxHealth, Attribute attackDmg, Attribute followRangeAttr) {
        World w = loc.getWorld();
        if (w == null) return;

        try {
            pluginSpawning = true;

            w.spawn(loc, Zombie.class, z -> {
                // помечаем "наш"
                z.getPersistentDataContainer().set(zombieKey, PersistentDataType.BYTE, (byte) 1);

                // обычный зомби: без предметов/брони
                z.setCanPickupItems(false);
                EntityEquipment eq = z.getEquipment();
                if (eq != null) {
                    eq.setItemInMainHand(null);
                    eq.setItemInOffHand(null);
                    eq.setHelmet(null);
                    eq.setChestplate(null);
                    eq.setLeggings(null);
                    eq.setBoots(null);

                    eq.setHelmetDropChance(0f);
                    eq.setChestplateDropChance(0f);
                    eq.setLeggingsDropChance(0f);
                    eq.setBootsDropChance(0f);
                    eq.setItemInMainHandDropChance(0f);
                    eq.setItemInOffHandDropChance(0f);
                }

                z.setBaby(false);

                // статы
                if (maxHealth != null) {
                    AttributeInstance a = z.getAttribute(maxHealth);
                    if (a != null) {
                        a.setBaseValue(hp);
                        z.setHealth(Math.min(hp, a.getValue()));
                    }
                }
                if (attackDmg != null) {
                    AttributeInstance a = z.getAttribute(attackDmg);
                    if (a != null) a.setBaseValue(dmg);
                }

                // дальность агра
                if (followRangeAttr != null) {
                    AttributeInstance a = z.getAttribute(followRangeAttr);
                    if (a != null) a.setBaseValue(follow);
                }
            });

        } finally {
            pluginSpawning = false;
        }
    }

    private Location randomSpawnAroundBaseOutsideRadius(Location baseCenter) {
        World w = baseCenter.getWorld();
        if (w == null) return null;

        int baseRadius = plugin.base().getRadius();

        int minOutside = plugin.getConfig().getInt("zombies.base_spawn_ring.min_outside", 10);
        int maxOutside = plugin.getConfig().getInt("zombies.base_spawn_ring.max_outside", 28);

        int minR = Math.max(1, baseRadius + minOutside);
        int maxR = Math.max(minR + 1, baseRadius + maxOutside);

        int yOff = plugin.getConfig().getInt("zombies.spawn_y_offset", 2);

        double angle = rng.nextDouble() * Math.PI * 2;
        double r = minR + rng.nextDouble() * (maxR - minR);

        int x = (int) Math.round(baseCenter.getX() + Math.cos(angle) * r);
        int z = (int) Math.round(baseCenter.getZ() + Math.sin(angle) * r);
        int y = w.getHighestBlockYAt(x, z) + yOff;

        Location loc = new Location(w, x + 0.5, y, z + 0.5);
        if (loc.getBlock().isLiquid()) return null;
        return loc;
    }

    private Location randomSpawnNearPlayer(Location center) {
        World w = center.getWorld();
        if (w == null) return null;

        int minR = plugin.getConfig().getInt("zombies.spawn_radius_min", 12);
        int maxR = plugin.getConfig().getInt("zombies.spawn_radius_max", 28);
        int yOff = plugin.getConfig().getInt("zombies.spawn_y_offset", 2);

        double angle = rng.nextDouble() * Math.PI * 2;
        double r = minR + rng.nextDouble() * (maxR - minR);

        int x = (int) Math.round(center.getX() + Math.cos(angle) * r);
        int z = (int) Math.round(center.getZ() + Math.sin(angle) * r);
        int y = w.getHighestBlockYAt(x, z) + yOff;

        Location loc = new Location(w, x + 0.5, y, z + 0.5);
        if (loc.getBlock().isLiquid()) return null;
        return loc;
    }

    private Attribute getAttribute(String... keys) {
        for (String k : keys) {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k));
            if (a != null) return a;
        }
        return null;
    }
}
