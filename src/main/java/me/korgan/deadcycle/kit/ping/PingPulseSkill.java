package me.korgan.deadcycle.kit.ping;

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

public class PingPulseSkill implements Skill {

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
    private double knockbackPower;

    public PingPulseSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.ping.pulse.xp_cost", 26));
        this.cooldownMs = Math.max(400L, plugin.getConfig().getLong("skills.ping.pulse.cooldown_ms", 9800L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.ping.pulse.cooldown_reduce_per_level_ms", 180L));
        this.minCooldownMs = Math.max(400L, plugin.getConfig().getLong("skills.ping.pulse.cooldown_min_ms", 3800L));

        this.radiusBase = Math.max(1.5, plugin.getConfig().getDouble("skills.ping.pulse.radius_base", 4.5));
        this.radiusPerLevel = Math.max(0.0, plugin.getConfig().getDouble("skills.ping.pulse.radius_per_level", 0.2));
        this.radiusMax = Math.max(radiusBase, plugin.getConfig().getDouble("skills.ping.pulse.radius_max", 7.0));

        this.damageBase = Math.max(0.0, plugin.getConfig().getDouble("skills.ping.pulse.damage_base", 4.0));
        this.damagePerLevel = Math.max(0.0, plugin.getConfig().getDouble("skills.ping.pulse.damage_per_level", 0.35));
        this.knockbackPower = Math.max(0.2, plugin.getConfig().getDouble("skills.ping.pulse.knockback_power", 1.0));
    }

    @Override
    public String getId() {
        return "ping_pulse";
    }

    @Override
    public String getDisplayName() {
        return "§9Пинг-Импульс";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getPingLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.PING)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.PING)
            return "§cЭтот навык доступен только киту Пинг.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        int level = plugin.progress().getPingLevel(p.getUniqueId());
        double radius = Math.min(radiusMax, radiusBase + Math.max(0, level - 1) * radiusPerLevel);
        double damage = damageBase + Math.max(0, level - 1) * damagePerLevel;

        Location center = p.getLocation();
        int hits = 0;

        for (Entity entity : p.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target))
                continue;
            if (!isPulseTarget(target, p))
                continue;

            target.damage(damage, p);

            Vector away = target.getLocation().toVector().subtract(center.toVector()).setY(0.0);
            if (away.lengthSquared() < 0.0001) {
                away = p.getLocation().getDirection().setY(0.0);
            }
            if (away.lengthSquared() < 0.0001) {
                away = new Vector(0, 0, 1);
            }
            target.setVelocity(target.getVelocity().multiply(0.25)
                    .add(away.normalize().multiply(knockbackPower).setY(0.18)));
            hits++;
        }

        Location fx = center.clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, fx, 40, radius * 0.22, 0.2, radius * 0.22, 0.08);
        p.getWorld().spawnParticle(Particle.END_ROD, fx, 22, radius * 0.16, 0.25, radius * 0.16, 0.02);
        p.getWorld().playSound(center, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.65f, 1.45f);
        p.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.9f);

        if (hits > 0) {
            p.sendActionBar(net.kyori.adventure.text.Component.text("Импульс попал по целям: " + hits));
        }

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        long cooldown = getCooldownMs(p);
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldown);
    }

    private boolean isPulseTarget(LivingEntity target, Player source) {
        if (target == null || source == null)
            return false;
        if (target.getUniqueId().equals(source.getUniqueId()))
            return false;
        if (!target.isValid() || target.isDead())
            return false;

        if (target instanceof Player) {
            return false;
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
        if (plugin.bossDuel() == null)
            return false;

        Byte bossMark = zombie.getPersistentDataContainer().get(plugin.bossDuel().bossMarkKey(),
                PersistentDataType.BYTE);
        return bossMark != null && bossMark == (byte) 1;
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
