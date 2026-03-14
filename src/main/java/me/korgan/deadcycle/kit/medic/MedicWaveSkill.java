package me.korgan.deadcycle.kit.medic;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;

public class MedicWaveSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private double radiusBase;
    private double radiusPerLevel;
    private double radiusMax;
    private double healBase;
    private double healPerLevel;
    private int regenSeconds;
    private int regenAmplifier;

    public MedicWaveSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.medic.wave.xp_cost", 18));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.medic.wave.cooldown_ms", 10500L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.medic.wave.cooldown_reduce_per_level_ms", 140L));
        this.minCooldownMs = Math.max(800L, plugin.getConfig().getLong("skills.medic.wave.cooldown_min_ms", 4000L));

        this.radiusBase = Math.max(2.0, plugin.getConfig().getDouble("skills.medic.wave.radius_base", 5.5));
        this.radiusPerLevel = Math.max(0.0, plugin.getConfig().getDouble("skills.medic.wave.radius_per_level", 0.2));
        this.radiusMax = Math.max(radiusBase, plugin.getConfig().getDouble("skills.medic.wave.radius_max", 7.8));

        this.healBase = Math.max(0.5, plugin.getConfig().getDouble("skills.medic.wave.heal_base", 4.0));
        this.healPerLevel = Math.max(0.0, plugin.getConfig().getDouble("skills.medic.wave.heal_per_level", 0.35));

        this.regenSeconds = Math.max(0, plugin.getConfig().getInt("skills.medic.wave.regen_seconds", 4));
        this.regenAmplifier = Math.max(0, plugin.getConfig().getInt("skills.medic.wave.regen_amplifier", 0));
    }

    @Override
    public String getId() {
        return "medic_wave";
    }

    @Override
    public String getDisplayName() {
        return "§aПолевая терапия";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getMedicLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.MEDIC)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.MEDIC)
            return "§cЭтот навык доступен только киту Медик.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        int level = plugin.progress().getMedicLevel(p.getUniqueId());
        double radius = Math.min(radiusMax, radiusBase + Math.max(0, level - 1) * radiusPerLevel);
        double healAmount = healBase + Math.max(0, level - 1) * healPerLevel;

        Set<Player> targets = new HashSet<>();
        targets.add(p);

        for (Entity entity : p.getWorld().getNearbyEntities(p.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof Player other))
                continue;
            if (!canAffectPlayer(p, other))
                continue;
            targets.add(other);
        }

        int healed = 0;
        for (Player target : targets) {
            if (!target.isOnline() || target.isDead())
                continue;

            applyHeal(target, healAmount);
            cleanseDebuffs(target);

            if (regenSeconds > 0) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                        regenSeconds * 20, regenAmplifier, true, false, true));
            }

            Location fx = target.getLocation().clone().add(0, 1.0, 0);
            target.getWorld().spawnParticle(Particle.HEART, fx, 8, 0.30, 0.28, 0.30, 0.0);
            target.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, fx, 12, 0.35, 0.35, 0.35, 0.01);
            healed++;
        }

        Location center = p.getLocation().clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, center, 22, radius * 0.12, 0.2, radius * 0.12, 0.02);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.75f, 1.35f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.7f);

        p.sendActionBar(net.kyori.adventure.text.Component.text("Полевая терапия: целей " + healed));

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        long cooldown = getCooldownMs(p);
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldown);
    }

    private boolean canAffectPlayer(Player source, Player target) {
        if (source == null || target == null)
            return false;
        if (!target.isOnline() || target.isDead())
            return false;
        if (target.getGameMode() == GameMode.SPECTATOR)
            return false;

        if (plugin.bossDuel() != null
                && plugin.bossDuel().isDuelActive()
                && plugin.bossDuel().isDuelPlayer(source.getUniqueId())) {
            return plugin.bossDuel().isDuelPlayer(target.getUniqueId());
        }

        return true;
    }

    private void applyHeal(Player target, double amount) {
        AttributeInstance maxHealth = target.getAttribute(Attribute.MAX_HEALTH);
        double max = (maxHealth == null) ? 20.0 : Math.max(1.0, maxHealth.getValue());
        double next = Math.min(max, target.getHealth() + amount);
        target.setHealth(Math.max(0.1, next));
    }

    private void cleanseDebuffs(Player target) {
        target.removePotionEffect(PotionEffectType.POISON);
        target.removePotionEffect(PotionEffectType.WITHER);
        target.removePotionEffect(PotionEffectType.WEAKNESS);
        target.removePotionEffect(PotionEffectType.SLOWNESS);
        target.removePotionEffect(PotionEffectType.BLINDNESS);
        target.removePotionEffect(PotionEffectType.HUNGER);
        target.removePotionEffect(PotionEffectType.DARKNESS);
    }

    @Override
    public void reset() {
        loadConfig();
    }
}
