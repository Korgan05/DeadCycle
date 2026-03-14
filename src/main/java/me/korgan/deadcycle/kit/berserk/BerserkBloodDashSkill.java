package me.korgan.deadcycle.kit.berserk;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
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

import java.util.HashSet;
import java.util.Set;

public class BerserkBloodDashSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    private double dashDistanceBase;
    private double dashDistancePerLevel;
    private double dashDistanceMax;
    private double hitRadius;
    private double damageBase;
    private double damagePerLevel;
    private double knockbackPower;

    private int minHitsForRefund;
    private long roarCooldownRefundMs;

    public BerserkBloodDashSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.berserk.blood_dash.xp_cost", 22));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.berserk.blood_dash.cooldown_ms", 12000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.berserk.blood_dash.cooldown_reduce_per_level_ms", 160L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.berserk.blood_dash.cooldown_min_ms", 5200L));

        this.dashDistanceBase = Math.max(3.0,
                plugin.getConfig().getDouble("skills.berserk.blood_dash.distance_base", 6.0));
        this.dashDistancePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.berserk.blood_dash.distance_per_level", 0.20));
        this.dashDistanceMax = Math.max(dashDistanceBase,
                plugin.getConfig().getDouble("skills.berserk.blood_dash.distance_max", 8.5));
        this.hitRadius = Math.max(0.5,
                plugin.getConfig().getDouble("skills.berserk.blood_dash.hit_radius", 1.25));

        this.damageBase = Math.max(0.0,
                plugin.getConfig().getDouble("skills.berserk.blood_dash.damage_base", 4.2));
        this.damagePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.berserk.blood_dash.damage_per_level", 0.35));
        this.knockbackPower = Math.max(0.1,
                plugin.getConfig().getDouble("skills.berserk.blood_dash.knockback_power", 0.9));

        this.minHitsForRefund = Math.max(1,
                plugin.getConfig().getInt("skills.berserk.blood_dash.min_hits_for_roar_refund", 2));
        this.roarCooldownRefundMs = Math.max(0L,
                plugin.getConfig().getLong("skills.berserk.blood_dash.roar_refund_ms", 2000L));
    }

    @Override
    public String getId() {
        return "berserk_blood_dash";
    }

    @Override
    public String getDisplayName() {
        return "§4Кровавый рывок";
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
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.BERSERK)
            return "§cЭтот навык доступен только киту Берсерк.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        int level = plugin.progress().getBerserkLevel(p.getUniqueId());
        double dashDistance = Math.min(dashDistanceMax,
                dashDistanceBase + Math.max(0, level - 1) * dashDistancePerLevel);
        double damage = damageBase + Math.max(0, level - 1) * damagePerLevel;

        Location start = p.getLocation().clone();
        Location end = findDashDestination(p, dashDistance);
        if (end == null)
            end = start.clone();

        Set<LivingEntity> targets = collectTargetsAlongPath(p, start, end, hitRadius);

        for (LivingEntity target : targets) {
            target.damage(damage, p);

            Vector push = target.getLocation().toVector().subtract(start.toVector()).setY(0.0);
            if (push.lengthSquared() < 0.0001) {
                push = p.getLocation().getDirection().setY(0.0);
            }
            if (push.lengthSquared() < 0.0001) {
                push = new Vector(0, 0, 1);
            }
            target.setVelocity(target.getVelocity().multiply(0.2)
                    .add(push.normalize().multiply(knockbackPower).setY(0.20)));
        }

        end.setPitch(p.getLocation().getPitch());
        end.setYaw(p.getLocation().getYaw());
        p.teleport(end);
        p.setFallDistance(0.0f);

        spawnDashParticles(start, end);
        p.getWorld().playSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.65f);
        p.getWorld().playSound(end, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.85f, 0.78f);

        int hitCount = targets.size();
        if (hitCount >= minHitsForRefund && roarCooldownRefundMs > 0L) {
            BerserkListener.reduceProcCooldown(p.getUniqueId(), roarCooldownRefundMs);
            double refundSeconds = roarCooldownRefundMs / 1000.0;
            String refundText = refundSeconds >= 1.0
                    ? String.format(java.util.Locale.US, "%.1f", refundSeconds).replaceAll("\\.0$", "")
                    : String.format(java.util.Locale.US, "%.2f", refundSeconds).replaceAll("0+$", "")
                            .replaceAll("\\.$", "");
            p.sendActionBar(net.kyori.adventure.text.Component.text("Рёв перезаряжен на " + refundText + "с"));
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

    private Location findDashDestination(Player p, double maxDistance) {
        Location start = p.getLocation().clone();
        Vector dir = start.getDirection().setY(0.0);
        if (dir.lengthSquared() < 0.0001) {
            dir = p.getEyeLocation().getDirection().setY(0.0);
        }
        if (dir.lengthSquared() < 0.0001) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();

        Location best = start.clone();
        for (double d = 0.6; d <= maxDistance; d += 0.6) {
            Location candidate = start.clone().add(dir.clone().multiply(d));
            candidate.setX(candidate.getBlockX() + 0.5);
            candidate.setZ(candidate.getBlockZ() + 0.5);

            if (!isSafeDashLocation(candidate)) {
                break;
            }
            best = candidate;
        }

        return best;
    }

    private boolean isSafeDashLocation(Location loc) {
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

    private Set<LivingEntity> collectTargetsAlongPath(Player owner, Location start, Location end, double radius) {
        Set<LivingEntity> result = new HashSet<>();
        if (start == null || end == null || start.getWorld() == null || end.getWorld() == null)
            return result;
        if (!start.getWorld().getUID().equals(end.getWorld().getUID()))
            return result;

        Vector a = start.toVector();
        Vector b = end.toVector();
        Vector mid = a.clone().add(b).multiply(0.5);
        Vector half = b.clone().subtract(a).multiply(0.5);

        double rx = Math.abs(half.getX()) + radius + 1.0;
        double ry = 2.0 + radius;
        double rz = Math.abs(half.getZ()) + radius + 1.0;

        for (Entity entity : owner.getWorld().getNearbyEntities(start.clone().add(mid.clone().subtract(a)), rx, ry,
                rz)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isDashTarget(owner, living))
                continue;

            Vector point = living.getLocation().toVector();
            double distSq = distanceToSegmentSquared(point, a, b);
            if (distSq <= radius * radius) {
                result.add(living);
            }
        }

        return result;
    }

    private double distanceToSegmentSquared(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double lenSq = ab.lengthSquared();
        if (lenSq <= 1.0E-9) {
            return p.clone().subtract(a).lengthSquared();
        }

        double t = p.clone().subtract(a).dot(ab) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        Vector proj = a.clone().add(ab.multiply(t));
        return p.clone().subtract(proj).lengthSquared();
    }

    private boolean isDashTarget(Player owner, LivingEntity target) {
        if (owner == null || target == null)
            return false;
        if (target.getUniqueId().equals(owner.getUniqueId()))
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

        if (target instanceof Zombie z && plugin.bossDuel() != null) {
            Byte bossMark = z.getPersistentDataContainer().get(plugin.bossDuel().bossMarkKey(),
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

    private void spawnDashParticles(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null)
            return;
        if (!from.getWorld().getUID().equals(to.getWorld().getUID()))
            return;

        Vector delta = to.toVector().subtract(from.toVector());
        int points = 16;
        Vector step = delta.multiply(1.0 / points);
        Location cur = from.clone().add(0, 1.0, 0);

        for (int i = 0; i <= points; i++) {
            from.getWorld().spawnParticle(Particle.CRIT, cur, 2, 0.08, 0.06, 0.08, 0.01);
            from.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, cur, 1, 0.02, 0.02, 0.02, 0.0);
            cur.add(step);
        }
    }
}
