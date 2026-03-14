package me.korgan.deadcycle.kit.archer;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ArcherHunterMarkSkill implements Skill, Listener {

    private static final class MarkEntry {
        UUID ownerId;
        long expiresAtMs;
    }

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private final Map<UUID, MarkEntry> markByTarget = new HashMap<>();
    private final Map<UUID, UUID> targetByOwner = new HashMap<>();

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private double rangeBase;
    private double rangePerLevel;
    private double rangeMax;
    private int markDurationSeconds;
    private double bonusDamageMultiplier;

    public ArcherHunterMarkSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.archer.mark.xp_cost", 14));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.archer.mark.cooldown_ms", 10000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.archer.mark.cooldown_reduce_per_level_ms", 180L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.archer.mark.cooldown_min_ms", 4200L));

        this.rangeBase = Math.max(6.0, plugin.getConfig().getDouble("skills.archer.mark.range_base", 18.0));
        this.rangePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.archer.mark.range_per_level", 0.35));
        this.rangeMax = Math.max(rangeBase,
                plugin.getConfig().getDouble("skills.archer.mark.range_max", 24.0));

        this.markDurationSeconds = Math.max(1,
                plugin.getConfig().getInt("skills.archer.mark.duration_seconds", 4));
        this.bonusDamageMultiplier = Math.max(0.0,
                plugin.getConfig().getDouble("skills.archer.mark.bonus_damage_multiplier", 0.25));
    }

    @Override
    public String getId() {
        return "archer_mark";
    }

    @Override
    public String getDisplayName() {
        return "§eМетка охотника";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getArcherLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.ARCHER)
            return false;
        if (findTargetInFront(p, getRange(p)) == null)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.ARCHER)
            return "§cЭтот навык доступен только киту Лучник.";

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

        UUID ownerId = p.getUniqueId();
        clearOwnerMark(ownerId);

        MarkEntry entry = new MarkEntry();
        entry.ownerId = ownerId;
        entry.expiresAtMs = System.currentTimeMillis() + markDurationSeconds * 1000L;
        markByTarget.put(target.getUniqueId(), entry);
        targetByOwner.put(ownerId, target.getUniqueId());

        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                markDurationSeconds * 20, 0, true, false, true));

        Location fx = target.getLocation().clone().add(0, Math.max(0.8, target.getHeight() * 0.6), 0);
        p.getWorld().spawnParticle(Particle.GLOW, fx, 20, 0.35, 0.35, 0.35, 0.02);
        p.getWorld().spawnParticle(Particle.END_ROD, fx, 10, 0.25, 0.25, 0.25, 0.01);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.45f);

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity target))
            return;

        Player owner = resolveOwner(e.getDamager());
        if (owner == null)
            return;

        UUID targetId = target.getUniqueId();
        MarkEntry entry = markByTarget.get(targetId);
        if (entry == null)
            return;

        long now = System.currentTimeMillis();
        if (now >= entry.expiresAtMs) {
            clearTargetMark(targetId);
            return;
        }

        if (!entry.ownerId.equals(owner.getUniqueId()))
            return;

        e.setDamage(e.getDamage() * (1.0 + bonusDamageMultiplier));
        clearTargetMark(targetId);

        Location fx = target.getLocation().clone().add(0, Math.max(0.8, target.getHeight() * 0.6), 0);
        target.getWorld().spawnParticle(Particle.CRIT, fx, 14, 0.25, 0.25, 0.25, 0.05);
        target.getWorld().playSound(fx, Sound.ENTITY_ARROW_HIT_PLAYER, 0.65f, 1.45f);
        owner.sendActionBar(net.kyori.adventure.text.Component.text("Метка сработала"));
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        clearTargetMark(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        clearOwnerMark(id);
        clearTargetMark(id);
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private double getRange(Player p) {
        int level = plugin.progress().getArcherLevel(p.getUniqueId());
        return Math.min(rangeMax, rangeBase + Math.max(0, level - 1) * rangePerLevel);
    }

    private void clearOwnerMark(UUID ownerId) {
        UUID oldTarget = targetByOwner.remove(ownerId);
        if (oldTarget != null) {
            markByTarget.remove(oldTarget);
        }
    }

    private void clearTargetMark(UUID targetId) {
        MarkEntry old = markByTarget.remove(targetId);
        if (old != null) {
            targetByOwner.remove(old.ownerId);
        }
    }

    private Player resolveOwner(Entity damager) {
        if (damager instanceof Player p)
            return p;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p)
            return p;
        return null;
    }

    private LivingEntity findTargetInFront(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : owner.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isMarkTarget(owner, living))
                continue;

            Location center = living.getLocation().clone().add(0, Math.max(0.6, living.getHeight() * 0.5), 0);
            Vector to = center.toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 0.001)
                continue;

            double dot = direction.dot(to.clone().normalize());
            if (dot < 0.22)
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

    private boolean isMarkTarget(Player owner, LivingEntity target) {
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
            return bossMark != null && bossMark == (byte) 1;
        }

        return false;
    }
}
