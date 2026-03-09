package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class DuelistBreachSkill implements Skill {

    private static final Color CUT_COLOR = Color.fromRGB(184, 38, 75);

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int xpCost;
    private long cooldownMs;
    private double range;
    private double crowdRadius;
    private double damageBase;
    private double damagePerLevel;
    private double oneVsOneBonusBase;
    private double oneVsOneBonusPerLevel;
    private double crowdPenaltyPerEnemy;
    private double crowdPenaltyCap;
    private int weakenSecondsBase;
    private int weakenSecondsPerLevel;
    private int weakenAmplifierBase;
    private int slowSeconds;
    private int slowAmplifier;
    private int selfSlowOnCrowdTicks;

    public DuelistBreachSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.xpCost = readInt("skills.duelist.ritual_cut.xp_cost", "skills.duelist.breach.xp_cost", 18);
        this.cooldownMs = readLong("skills.duelist.ritual_cut.cooldown_ms", "skills.duelist.breach.cooldown_ms",
                14000L);
        this.range = readDouble("skills.duelist.ritual_cut.range", "skills.duelist.breach.range", 16.0);
        this.crowdRadius = readDouble("skills.duelist.ritual_cut.crowd_radius", "skills.duelist.breach.crowd_radius",
                8.5);

        this.damageBase = readDouble("skills.duelist.ritual_cut.damage_base", "skills.duelist.breach.bonus_damage_base",
                6.0);
        this.damagePerLevel = readDouble("skills.duelist.ritual_cut.damage_per_level",
                "skills.duelist.breach.bonus_damage_per_level", 0.55);

        this.oneVsOneBonusBase = readDouble("skills.duelist.ritual_cut.one_vs_one_bonus_base",
                "skills.duelist.breach.one_vs_one_bonus_base", 0.20);
        this.oneVsOneBonusPerLevel = readDouble("skills.duelist.ritual_cut.one_vs_one_bonus_per_level",
                "skills.duelist.breach.one_vs_one_bonus_per_level", 0.015);
        this.crowdPenaltyPerEnemy = readDouble("skills.duelist.ritual_cut.crowd_penalty_per_enemy",
                "skills.duelist.breach.crowd_penalty_per_enemy", 0.16);
        this.crowdPenaltyCap = readDouble("skills.duelist.ritual_cut.crowd_penalty_cap",
                "skills.duelist.breach.crowd_penalty_cap", 0.45);

        this.weakenSecondsBase = readInt("skills.duelist.ritual_cut.weaken_seconds_base",
                "skills.duelist.breach.weaken_seconds", 5);
        this.weakenSecondsPerLevel = readInt("skills.duelist.ritual_cut.weaken_seconds_per_level",
                "skills.duelist.breach.weaken_seconds_per_level", 1);
        this.weakenAmplifierBase = readInt("skills.duelist.ritual_cut.weaken_amplifier_base",
                "skills.duelist.breach.weaken_amplifier", 0);
        this.slowSeconds = readInt("skills.duelist.ritual_cut.slow_seconds", "skills.duelist.breach.slow_seconds", 4);
        this.slowAmplifier = readInt("skills.duelist.ritual_cut.slow_amplifier", "skills.duelist.breach.slow_amplifier",
                1);
        this.selfSlowOnCrowdTicks = readInt("skills.duelist.ritual_cut.self_slow_on_crowd_ticks",
                "skills.duelist.breach.self_slow_on_crowd_ticks", 24);
    }

    @Override
    public String getId() {
        return "ritual_cut";
    }

    @Override
    public String getDisplayName() {
        return "§5Ритуальный Разрез";
    }

    @Override
    public double getManaCost(Player p) {
        return xpCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getDuelistLevel(p.getUniqueId());
        long reduced = cooldownMs - (level * 420L);
        return Math.max(4200L, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (findPrimaryTargetInFront(p) == null)
            return false;

        int cost = (int) getManaCost(p);
        return p.getLevel() >= cost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cОшибка: игрок не в сети";
        if (findPrimaryTargetInFront(p) == null)
            return "§cНет подходящей цели перед тобой.";

        int cost = (int) getManaCost(p);
        if (p.getLevel() < cost)
            return "§cНедостаточно маны! Нужно: " + cost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        LivingEntity target = findPrimaryTargetInFront(p);
        if (target == null)
            return;

        if (isBossEntity(target) && plugin.bossDuel() != null && plugin.bossDuel().isDuelPlayer(p.getUniqueId())) {
            plugin.bossDuel().registerSkillUsage(p, "ritual_cut");
        }

        int level = plugin.progress().getDuelistLevel(p.getUniqueId());
        int threats = countThreatsAround(p, crowdRadius);

        double base = damageBase + (damagePerLevel * level);
        double duelBonus = (threats <= 1) ? (oneVsOneBonusBase + Math.max(0, level - 1) * oneVsOneBonusPerLevel) : 0.0;
        double crowdPenalty = Math.min(crowdPenaltyCap, Math.max(0, threats - 1) * crowdPenaltyPerEnemy);
        double multiplier = Math.max(0.50, 1.0 + duelBonus - crowdPenalty);

        double finalDamage = base * multiplier;
        target.damage(finalDamage, p);

        int weakenSeconds = weakenSecondsBase + Math.max(0, level - 1) / Math.max(1, weakenSecondsPerLevel);
        int weakenAmp = Math.max(0, weakenAmplifierBase + (level >= 8 ? 1 : 0));
        if (threats >= 3) {
            weakenAmp = Math.max(0, weakenAmp - 1);
            weakenSeconds = Math.max(2, weakenSeconds - 2);
        }

        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Math.max(20, weakenSeconds * 20), weakenAmp,
                true, false, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Math.max(20, slowSeconds * 20),
                Math.max(0, slowAmplifier), true, false, true));

        Vector launch = target.getLocation().toVector().subtract(p.getLocation().toVector());
        if (launch.lengthSquared() < 0.0001) {
            launch = p.getLocation().getDirection().clone();
        }
        launch.normalize().multiply(0.50 + (threats <= 1 ? 0.15 : 0.0)).setY(0.14);
        target.setVelocity(target.getVelocity().multiply(0.25).add(launch));

        if (threats >= 3) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Math.max(10, selfSlowOnCrowdTicks), 0,
                    true, false, true));
        }

        World world = p.getWorld();
        Location hit = target.getLocation().clone().add(0, Math.max(0.6, target.getHeight() * 0.5), 0);
        Particle.DustOptions dust = new Particle.DustOptions(CUT_COLOR, 1.32f);
        world.spawnParticle(Particle.DUST, hit, 26, 0.35, 0.45, 0.35, 0.0, dust);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, hit, 12, 0.22, 0.25, 0.22, 0.015);
        world.spawnParticle(Particle.ENCHANT, hit, 24, 0.28, 0.35, 0.28, 0.02);
        world.spawnParticle(Particle.SWEEP_ATTACK, hit, 6, 0.2, 0.2, 0.2, 0.0);
        world.playSound(hit, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.56f);
        world.playSound(hit, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.72f, 1.65f);

        long cooldown = getCooldownMs(p);
        if (threats >= 3) {
            cooldown += 900L;
        }
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldown);

        int exp = (threats <= 1) ? 2 : 1;
        plugin.progress().addDuelistExp(p, exp);
    }

    private LivingEntity findPrimaryTargetInFront(Player p) {
        Location eye = p.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : p.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity target))
                continue;
            if (!isCombatTarget(target, p))
                continue;

            Location center = target.getLocation().clone().add(0, Math.max(0.6, target.getHeight() * 0.5), 0);
            Vector to = center.toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 0.001)
                continue;

            double dot = direction.dot(to.clone().normalize());
            if (dot < 0.24)
                continue;

            double score = dist - (dot * 2.2);
            if (score < bestScore) {
                bestScore = score;
                best = target;
            }
        }

        return best;
    }

    private int countThreatsAround(Player p, double radius) {
        int count = 0;
        for (Entity entity : p.getWorld().getNearbyEntities(p.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (isCombatTarget(living, p)) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private boolean isCombatTarget(LivingEntity target, Player source) {
        if (target == null || source == null)
            return false;
        if (target.getUniqueId().equals(source.getUniqueId()))
            return false;
        if (!target.isValid() || target.isDead())
            return false;

        if (target instanceof Player other) {
            return other.getGameMode() != org.bukkit.GameMode.SPECTATOR;
        }

        if (target instanceof Monster)
            return true;

        return isBossEntity(target);
    }

    private boolean isBossEntity(LivingEntity target) {
        if (!(target instanceof Zombie z))
            return false;
        if (plugin.bossDuel() == null)
            return false;

        PersistentDataContainer pdc = z.getPersistentDataContainer();
        Byte mark = pdc.get(plugin.bossDuel().bossMarkKey(), PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private int readInt(String primary, String fallback, int def) {
        if (plugin.getConfig().contains(primary)) {
            return plugin.getConfig().getInt(primary, def);
        }
        return plugin.getConfig().getInt(fallback, def);
    }

    private long readLong(String primary, String fallback, long def) {
        if (plugin.getConfig().contains(primary)) {
            return plugin.getConfig().getLong(primary, def);
        }
        return plugin.getConfig().getLong(fallback, def);
    }

    private double readDouble(String primary, String fallback, double def) {
        if (plugin.getConfig().contains(primary)) {
            return plugin.getConfig().getDouble(primary, def);
        }
        return plugin.getConfig().getDouble(fallback, def);
    }

    @Override
    public void reset() {
        loadConfig();
    }
}
