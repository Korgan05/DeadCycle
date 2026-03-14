package me.korgan.deadcycle.kit.exorcist;

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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class ExorcistPurgeSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private double radiusBase;
    private double radiusPerLevel;
    private double radiusMax;
    private double damageBase;
    private double damagePerLevel;
    private double bossDamageMultiplier;
    private int weaknessSeconds;
    private int weaknessAmplifier;
    private double knockbackPower;

    public ExorcistPurgeSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.exorcist.purge.xp_cost", 24));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.exorcist.purge.cooldown_ms", 11000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.exorcist.purge.cooldown_reduce_per_level_ms", 150L));
        this.minCooldownMs = Math.max(900L,
                plugin.getConfig().getLong("skills.exorcist.purge.cooldown_min_ms", 4300L));

        this.radiusBase = Math.max(2.0, plugin.getConfig().getDouble("skills.exorcist.purge.radius_base", 5.0));
        this.radiusPerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.exorcist.purge.radius_per_level", 0.2));
        this.radiusMax = Math.max(radiusBase,
                plugin.getConfig().getDouble("skills.exorcist.purge.radius_max", 7.2));

        this.damageBase = Math.max(0.0, plugin.getConfig().getDouble("skills.exorcist.purge.damage_base", 5.5));
        this.damagePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.exorcist.purge.damage_per_level", 0.45));
        this.bossDamageMultiplier = Math.max(1.0,
                plugin.getConfig().getDouble("skills.exorcist.purge.boss_damage_multiplier", 1.35));
        this.weaknessSeconds = Math.max(0, plugin.getConfig().getInt("skills.exorcist.purge.weakness_seconds", 4));
        this.weaknessAmplifier = Math.max(0,
                plugin.getConfig().getInt("skills.exorcist.purge.weakness_amplifier", 0));
        this.knockbackPower = Math.max(0.0,
                plugin.getConfig().getDouble("skills.exorcist.purge.knockback_power", 0.75));
    }

    @Override
    public String getId() {
        return "exorcist_purge";
    }

    @Override
    public String getDisplayName() {
        return "§bСвященный изгиб";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getExorcistLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.EXORCIST)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.EXORCIST)
            return "§cЭтот навык доступен только киту Экзорцист.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        int level = plugin.progress().getExorcistLevel(p.getUniqueId());
        double radius = Math.min(radiusMax, radiusBase + Math.max(0, level - 1) * radiusPerLevel);
        double damage = damageBase + Math.max(0, level - 1) * damagePerLevel;

        int hits = 0;
        Location center = p.getLocation();

        for (Entity entity : p.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target))
                continue;
            if (!isPurgeTarget(p, target))
                continue;

            double finalDamage = damage;
            if (isBoss(target)) {
                finalDamage *= bossDamageMultiplier;
            }

            target.damage(finalDamage, p);
            if (weaknessSeconds > 0) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                        weaknessSeconds * 20, weaknessAmplifier, true, false, true));
            }

            if (knockbackPower > 0.0) {
                Vector away = target.getLocation().toVector().subtract(center.toVector()).setY(0.0);
                if (away.lengthSquared() < 0.0001) {
                    away = p.getLocation().getDirection().setY(0.0);
                }
                if (away.lengthSquared() < 0.0001) {
                    away = new Vector(0, 0, 1);
                }

                target.setVelocity(target.getVelocity().multiply(0.2)
                        .add(away.normalize().multiply(knockbackPower).setY(0.12)));
            }

            hits++;
        }

        Location fx = center.clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, fx, 42, radius * 0.20, 0.25, radius * 0.20, 0.03);
        p.getWorld().spawnParticle(Particle.ENCHANT, fx, 28, radius * 0.14, 0.30, radius * 0.14, 0.08);
        p.getWorld().playSound(center, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.9f, 1.25f);
        p.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.15f);

        if (hits > 0) {
            p.sendActionBar(net.kyori.adventure.text.Component.text("Очищение задело: " + hits));
        }

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        long cooldown = getCooldownMs(p);
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldown);
    }

    private boolean isPurgeTarget(Player source, LivingEntity target) {
        if (source == null || target == null)
            return false;
        if (target.getUniqueId().equals(source.getUniqueId()))
            return false;
        if (target.isDead() || !target.isValid())
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

    @Override
    public void reset() {
        loadConfig();
    }
}
