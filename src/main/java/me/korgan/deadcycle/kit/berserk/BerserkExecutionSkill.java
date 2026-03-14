package me.korgan.deadcycle.kit.berserk;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public class BerserkExecutionSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    private double rangeBase;
    private double rangePerLevel;
    private double rangeMax;

    private double damageBase;
    private double damagePerLevel;
    private double executeHealthThreshold;
    private double executeBonusMultiplier;

    public BerserkExecutionSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.berserk.execution.xp_cost", 24));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.berserk.execution.cooldown_ms", 14000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.berserk.execution.cooldown_reduce_per_level_ms", 170L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.berserk.execution.cooldown_min_ms", 5200L));

        this.rangeBase = Math.max(3.0, plugin.getConfig().getDouble("skills.berserk.execution.range_base", 8.5));
        this.rangePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.berserk.execution.range_per_level", 0.20));
        this.rangeMax = Math.max(rangeBase,
                plugin.getConfig().getDouble("skills.berserk.execution.range_max", 12.0));

        this.damageBase = Math.max(0.0, plugin.getConfig().getDouble("skills.berserk.execution.damage_base", 6.0));
        this.damagePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.berserk.execution.damage_per_level", 0.45));
        this.executeHealthThreshold = Math.max(0.05,
                Math.min(0.95, plugin.getConfig().getDouble("skills.berserk.execution.health_threshold", 0.35)));
        this.executeBonusMultiplier = Math.max(0.0,
                plugin.getConfig().getDouble("skills.berserk.execution.execute_bonus_multiplier", 0.45));
    }

    @Override
    public String getId() {
        return "berserk_execution";
    }

    @Override
    public String getDisplayName() {
        return "§4Казнь";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getBerserkLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.BERSERK)
            return false;
        if (findTargetInFront(p, getRange(p)) == null)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.BERSERK)
            return "§cЭтот навык доступен только киту Берсерк.";
        if (findTargetInFront(p, getRange(p)) == null)
            return "§cНет подходящей цели перед тобой.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        LivingEntity target = findTargetInFront(p, getRange(p));
        if (target == null)
            return;

        int level = plugin.progress().getBerserkLevel(p.getUniqueId());
        double damage = damageBase + Math.max(0, level - 1) * damagePerLevel;

        double maxHealth = getMaxHealth(target);
        boolean execute = maxHealth > 0.0 && (target.getHealth() / maxHealth) <= executeHealthThreshold;
        if (execute) {
            damage *= (1.0 + executeBonusMultiplier);
        }

        Location dashTo = findDashLocationNearTarget(p, target);
        if (dashTo != null) {
            p.teleport(dashTo);
            p.setFallDistance(0.0f);
        }

        target.damage(damage, p);

        Location hit = target.getLocation().clone().add(0, Math.max(0.8, target.getHeight() * 0.5), 0);
        target.getWorld().spawnParticle(Particle.CRIT, hit, 18, 0.25, 0.25, 0.25, 0.05);
        target.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, hit, 8, 0.15, 0.15, 0.15, 0.03);
        target.getWorld().playSound(hit, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.85f, execute ? 0.65f : 0.9f);

        if (execute) {
            p.sendActionBar(net.kyori.adventure.text.Component.text("Казнь"));
        }

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private double getRange(Player p) {
        int level = plugin.progress().getBerserkLevel(p.getUniqueId());
        return Math.min(rangeMax, rangeBase + Math.max(0, level - 1) * rangePerLevel);
    }

    private LivingEntity findTargetInFront(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : owner.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isExecutionTarget(owner, living))
                continue;

            Location center = living.getLocation().clone().add(0, Math.max(0.6, living.getHeight() * 0.5), 0);
            Vector to = center.toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 0.001)
                continue;

            double dot = direction.dot(to.clone().normalize());
            if (dot < 0.20)
                continue;

            if (!owner.hasLineOfSight(living))
                continue;

            double score = dist - (dot * 2.8);
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }

        return best;
    }

    private boolean isExecutionTarget(Player owner, LivingEntity target) {
        if (owner == null || target == null)
            return false;
        if (owner.getUniqueId().equals(target.getUniqueId()))
            return false;
        if (!target.isValid() || target.isDead())
            return false;

        if (target instanceof Player other) {
            if (other.getGameMode() == org.bukkit.GameMode.SPECTATOR)
                return false;
            if (plugin.bossDuel() == null || !plugin.bossDuel().isDuelActive())
                return false;
            return plugin.bossDuel().isDuelPlayer(owner.getUniqueId())
                    && plugin.bossDuel().isDuelPlayer(other.getUniqueId());
        }

        if (target instanceof Monster)
            return true;

        if (target instanceof Zombie zombie && plugin.bossDuel() != null) {
            Byte bossMark = zombie.getPersistentDataContainer().get(plugin.bossDuel().bossMarkKey(),
                    PersistentDataType.BYTE);
            if (bossMark != null && bossMark == (byte) 1)
                return true;
        }

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

    private Location findDashLocationNearTarget(Player owner, LivingEntity target) {
        Location targetLoc = target.getLocation().clone();
        Vector away = owner.getLocation().toVector().subtract(targetLoc.toVector()).setY(0.0);
        if (away.lengthSquared() < 0.0001) {
            away = targetLoc.getDirection().setY(0.0).multiply(-1.0);
        }
        if (away.lengthSquared() < 0.0001) {
            away = new Vector(0, 0, 1);
        }

        Vector back = away.normalize();
        Vector right = new Vector(-back.getZ(), 0.0, back.getX());

        Location[] candidates = new Location[] {
                centered(targetLoc.clone().add(back.clone().multiply(1.4))),
                centered(targetLoc.clone().add(back.clone().multiply(1.2)).add(right.clone().multiply(0.9))),
                centered(targetLoc.clone().add(back.clone().multiply(1.2)).add(right.clone().multiply(-0.9))),
                centered(owner.getLocation().clone())
        };

        for (Location candidate : candidates) {
            if (isSafeStandLocation(candidate)) {
                candidate.setYaw(owner.getLocation().getYaw());
                candidate.setPitch(owner.getLocation().getPitch());
                return candidate;
            }
        }

        return null;
    }

    private Location centered(Location loc) {
        if (loc == null)
            return null;
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        return loc;
    }

    private boolean isSafeStandLocation(Location loc) {
        if (loc == null || loc.getWorld() == null)
            return false;

        Location feet = loc.clone();
        Location head = loc.clone().add(0, 1, 0);
        Location below = loc.clone().add(0, -1, 0);

        if (feet.getBlock().getType().isSolid())
            return false;
        if (head.getBlock().getType().isSolid())
            return false;
        return below.getBlock().getType().isSolid();
    }

    private double getMaxHealth(LivingEntity target) {
        if (target == null)
            return 0.0;
        AttributeInstance attr = target.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            return Math.max(1.0, attr.getValue());
        }
        return Math.max(1.0, target.getHealth());
    }
}
