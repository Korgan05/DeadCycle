package me.korgan.deadcycle.kit.summoner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.entity.Player;

public class SummonerSacrificeImpulseSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    private int speedSecondsBase;
    private int speedSecondsPerLevel;
    private int speedAmplifier;

    private double damageBonusMultiplier;
    private int damageBonusSecondsBase;
    private int damageBonusSecondsPerLevel;

    public SummonerSacrificeImpulseSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.summoner.sacrifice.xp_cost", 22));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.summoner.sacrifice.cooldown_ms", 12000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.summoner.sacrifice.cooldown_reduce_per_level_ms", 160L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.summoner.sacrifice.cooldown_min_ms", 4800L));

        this.speedSecondsBase = Math.max(1,
                plugin.getConfig().getInt("skills.summoner.sacrifice.speed_seconds_base", 4));
        this.speedSecondsPerLevel = Math.max(0,
                plugin.getConfig().getInt("skills.summoner.sacrifice.speed_seconds_per_level", 0));
        this.speedAmplifier = Math.max(0,
                plugin.getConfig().getInt("skills.summoner.sacrifice.speed_amplifier", 1));

        this.damageBonusMultiplier = Math.max(0.0,
                plugin.getConfig().getDouble("skills.summoner.sacrifice.damage_bonus_multiplier", 0.25));
        this.damageBonusSecondsBase = Math.max(1,
                plugin.getConfig().getInt("skills.summoner.sacrifice.damage_bonus_seconds_base", 5));
        this.damageBonusSecondsPerLevel = Math.max(0,
                plugin.getConfig().getInt("skills.summoner.sacrifice.damage_bonus_seconds_per_level", 0));
    }

    @Override
    public String getId() {
        return "summoner_sacrifice";
    }

    @Override
    public String getDisplayName() {
        return "§cЖертвенный импульс";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getSummonerLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.SUMMONER)
            return false;
        if (plugin.summonerKit() == null)
            return false;

        return plugin.summonerKit().getSacrificeError(p) == null
                && p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.SUMMONER)
            return "§cЭтот навык доступен только киту Призыватель.";
        if (plugin.summonerKit() == null)
            return "§cСистема призыва недоступна.";

        String err = plugin.summonerKit().getSacrificeError(p);
        if (err != null)
            return err;

        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();

        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline() || plugin.summonerKit() == null)
            return;

        boolean ok = plugin.summonerKit().sacrificialImpulse(
                p,
                getSpeedSeconds(p),
                speedAmplifier,
                damageBonusMultiplier,
                getDamageBonusSeconds(p));
        if (!ok)
            return;

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private int getSpeedSeconds(Player p) {
        int level = plugin.progress().getSummonerLevel(p.getUniqueId());
        return speedSecondsBase + Math.max(0, level - 1) * speedSecondsPerLevel;
    }

    private int getDamageBonusSeconds(Player p) {
        int level = plugin.progress().getSummonerLevel(p.getUniqueId());
        return damageBonusSecondsBase + Math.max(0, level - 1) * damageBonusSecondsPerLevel;
    }
}
