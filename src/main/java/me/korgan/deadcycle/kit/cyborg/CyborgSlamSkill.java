package me.korgan.deadcycle.kit.cyborg;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class CyborgSlamSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;
    private final Map<UUID, Integer> activeAirTasks = new HashMap<>();

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    private double jumpVelocity;
    private int armTicks;
    private int maxAirTicks;

    private double shockRadiusBase;
    private double shockRadiusPerLevel;
    private double shockRadiusMax;
    private double damageBase;
    private double damagePerLevel;
    private double knockbackPower;
    private int fireTicksBase;
    private int fireTicksPerLevel;

    public CyborgSlamSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.cyborg.slam.xp_cost", 26));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.cyborg.slam.cooldown_ms", 12000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.cyborg.slam.cooldown_reduce_per_level_ms", 170L));
        this.minCooldownMs = Math.max(800L, plugin.getConfig().getLong("skills.cyborg.slam.cooldown_min_ms", 4800L));

        this.jumpVelocity = Math.max(0.6, plugin.getConfig().getDouble("skills.cyborg.slam.jump_velocity", 1.08));
        this.armTicks = Math.max(2, plugin.getConfig().getInt("skills.cyborg.slam.arm_ticks", 6));
        this.maxAirTicks = Math.max(10, plugin.getConfig().getInt("skills.cyborg.slam.max_air_ticks", 40));

        this.shockRadiusBase = Math.max(2.0, plugin.getConfig().getDouble("skills.cyborg.slam.shock_radius_base", 4.0));
        this.shockRadiusPerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.cyborg.slam.shock_radius_per_level", 0.2));
        this.shockRadiusMax = Math.max(shockRadiusBase,
                plugin.getConfig().getDouble("skills.cyborg.slam.shock_radius_max", 6.2));

        this.damageBase = Math.max(0.0, plugin.getConfig().getDouble("skills.cyborg.slam.damage_base", 5.0));
        this.damagePerLevel = Math.max(0.0, plugin.getConfig().getDouble("skills.cyborg.slam.damage_per_level", 0.4));
        this.knockbackPower = Math.max(0.2, plugin.getConfig().getDouble("skills.cyborg.slam.knockback_power", 1.15));
        this.fireTicksBase = Math.max(0, plugin.getConfig().getInt("skills.cyborg.slam.fire_ticks_base", 40));
        this.fireTicksPerLevel = Math.max(0, plugin.getConfig().getInt("skills.cyborg.slam.fire_ticks_per_level", 4));
    }

    @Override
    public String getId() {
        return "cyborg_slam";
    }

    @Override
    public String getDisplayName() {
        return "§6Реактивный таран";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getCyborgLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.CYBORG)
            return false;
        if (activeAirTasks.containsKey(p.getUniqueId()))
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.CYBORG)
            return "§cЭтот навык доступен только киту Киборг.";
        if (activeAirTasks.containsKey(p.getUniqueId()))
            return "§cНавык уже активен.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        int level = plugin.progress().getCyborgLevel(p.getUniqueId());

        Vector launch = p.getVelocity().clone();
        launch.setY(Math.max(launch.getY(), jumpVelocity));
        p.setFallDistance(0f);
        p.setVelocity(launch);

        Location fx = p.getLocation().clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.FLAME, fx, 34, 0.28, 0.35, 0.28, 0.02);
        p.getWorld().spawnParticle(Particle.SMOKE, fx, 20, 0.20, 0.25, 0.20, 0.01);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.9f, 1.35f);

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        long cooldown = getCooldownMs(p);
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldown);

        startAirMonitor(p.getUniqueId(), level);
    }

    private void startAirMonitor(UUID ownerId, int level) {
        cancelAirMonitor(ownerId);

        final int[] lived = { 0 };
        int taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline() || owner.isDead()) {
                cancelAirMonitor(ownerId);
                return;
            }
            if (plugin.kit().getKit(ownerId) != KitManager.Kit.CYBORG) {
                cancelAirMonitor(ownerId);
                return;
            }

            Location loc = owner.getLocation().clone().add(0, 0.2, 0);
            owner.getWorld().spawnParticle(Particle.FLAME, loc, 8, 0.18, 0.12, 0.18, 0.004);
            owner.getWorld().spawnParticle(Particle.SMOKE, loc, 6, 0.14, 0.10, 0.14, 0.002);

            lived[0]++;
            boolean armed = lived[0] >= armTicks;
            if (armed && owner.isOnGround()) {
                triggerShockwave(owner, level);
                cancelAirMonitor(ownerId);
                return;
            }

            if (lived[0] >= maxAirTicks) {
                triggerShockwave(owner, level);
                cancelAirMonitor(ownerId);
            }
        }, 1L, 1L);

        activeAirTasks.put(ownerId, taskId);
    }

    private void triggerShockwave(Player source, int level) {
        if (source == null || !source.isOnline())
            return;

        source.setFallDistance(0f);

        double radius = Math.min(shockRadiusMax, shockRadiusBase + Math.max(0, level - 1) * shockRadiusPerLevel);
        double damage = damageBase + Math.max(0, level - 1) * damagePerLevel;
        int fireTicks = fireTicksBase + Math.max(0, level - 1) * fireTicksPerLevel;

        Location center = source.getLocation();
        int hits = 0;

        for (Entity entity : source.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target))
                continue;
            if (!isShockwaveTarget(source, target))
                continue;

            target.damage(damage, source);
            if (fireTicks > 0) {
                target.setFireTicks(Math.max(target.getFireTicks(), fireTicks));
            }

            Vector away = target.getLocation().toVector().subtract(center.toVector()).setY(0.0);
            if (away.lengthSquared() < 0.0001) {
                away = source.getLocation().getDirection().setY(0.0);
            }
            if (away.lengthSquared() < 0.0001) {
                away = new Vector(0, 0, 1);
            }

            target.setVelocity(target.getVelocity().multiply(0.2)
                    .add(away.normalize().multiply(knockbackPower).setY(0.22)));
            hits++;
        }

        Location fx = center.clone().add(0, 0.15, 0);
        source.getWorld().spawnParticle(Particle.FLAME, fx, 85, radius * 0.20, 0.08, radius * 0.20, 0.06);
        source.getWorld().spawnParticle(Particle.LAVA, fx, 22, radius * 0.12, 0.06, radius * 0.12, 0.01);
        source.getWorld().spawnParticle(Particle.EXPLOSION, fx, 2, 0.2, 0.05, 0.2, 0.0);
        source.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.85f, 1.1f);
        source.getWorld().playSound(center, Sound.ITEM_FIRECHARGE_USE, 0.9f, 0.9f);

        if (hits > 0) {
            source.sendActionBar(net.kyori.adventure.text.Component.text("Ударная волна задела: " + hits));
        }
    }

    private boolean isShockwaveTarget(Player source, LivingEntity target) {
        if (source == null || target == null)
            return false;
        if (target.getUniqueId().equals(source.getUniqueId()))
            return false;
        if (!target.isValid() || target.isDead())
            return false;

        if (target instanceof Player other) {
            if (other.getGameMode() == GameMode.SPECTATOR)
                return false;
            if (plugin.bossDuel() == null || !plugin.bossDuel().isDuelActive())
                return false;
            return plugin.bossDuel().isDuelPlayer(source.getUniqueId())
                    && plugin.bossDuel().isDuelPlayer(other.getUniqueId());
        }

        if (target instanceof Monster) {
            return true;
        }

        if (isBoss(target)) {
            return true;
        }

        return isPlayerOwnedCompanion(target);
    }

    private boolean isBoss(LivingEntity target) {
        if (!(target instanceof Zombie zombie))
            return false;

        if (plugin.bossDuel() != null) {
            Byte bossMark = zombie.getPersistentDataContainer().get(plugin.bossDuel().bossMarkKey(),
                    PersistentDataType.BYTE);
            if (bossMark != null && bossMark == (byte) 1)
                return true;
        }

        if (plugin.miniBoss() != null) {
            Byte miniMark = zombie.getPersistentDataContainer().get(plugin.miniBoss().miniBossMarkKey(),
                    PersistentDataType.BYTE);
            if (miniMark != null && miniMark == (byte) 1)
                return true;
        }

        return false;
    }

    private boolean isPlayerOwnedCompanion(LivingEntity target) {
        if (plugin.cloneKit() != null) {
            Byte cloneMark = target.getPersistentDataContainer().get(plugin.cloneKit().cloneMarkKey(),
                    PersistentDataType.BYTE);
            if (cloneMark != null && cloneMark == (byte) 1)
                return true;
        }

        if (plugin.summonerKit() != null) {
            Byte summonMark = target.getPersistentDataContainer().get(plugin.summonerKit().summonMarkKey(),
                    PersistentDataType.BYTE);
            if (summonMark != null && summonMark == (byte) 1)
                return true;
        }

        return false;
    }

    private void cancelAirMonitor(UUID ownerId) {
        Integer taskId = activeAirTasks.remove(ownerId);
        if (taskId != null) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
    }

    @Override
    public void reset() {
        loadConfig();
        for (UUID ownerId : activeAirTasks.keySet().toArray(new UUID[0])) {
            cancelAirMonitor(ownerId);
        }
    }
}
