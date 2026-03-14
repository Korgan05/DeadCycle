package me.korgan.deadcycle.kit.summoner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.entity.Player;

public class SummonerRegroupSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    private int shieldSecondsBase;
    private int shieldSecondsPerLevel;
    private int shieldAmplifierBase;
    private int shieldAmplifierLevel8;

    public SummonerRegroupSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.summoner.regroup.xp_cost", 18));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.summoner.regroup.cooldown_ms", 9000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.summoner.regroup.cooldown_reduce_per_level_ms", 140L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.summoner.regroup.cooldown_min_ms", 3400L));

        this.shieldSecondsBase = Math.max(1,
                plugin.getConfig().getInt("skills.summoner.regroup.shield_seconds_base", 4));
        this.shieldSecondsPerLevel = Math.max(0,
                plugin.getConfig().getInt("skills.summoner.regroup.shield_seconds_per_level", 0));
        this.shieldAmplifierBase = Math.max(0,
                plugin.getConfig().getInt("skills.summoner.regroup.shield_amplifier_base", 0));
        this.shieldAmplifierLevel8 = Math.max(shieldAmplifierBase,
                plugin.getConfig().getInt("skills.summoner.regroup.shield_amplifier_level8", 1));
    }

    @Override
    public String getId() {
        return "summoner_regroup";
    }

    @Override
    public String getDisplayName() {
        return "§bПерегруппировка";
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

        return plugin.summonerKit().getRegroupError(p) == null
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

        String err = plugin.summonerKit().getRegroupError(p);
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

        boolean ok = plugin.summonerKit().regroupSummons(p, getShieldSeconds(p), getShieldAmplifier(p));
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

    private int getShieldSeconds(Player p) {
        int level = plugin.progress().getSummonerLevel(p.getUniqueId());
        return shieldSecondsBase + Math.max(0, level - 1) * shieldSecondsPerLevel;
    }

    private int getShieldAmplifier(Player p) {
        int level = plugin.progress().getSummonerLevel(p.getUniqueId());
        if (level >= 8)
            return shieldAmplifierLevel8;
        return shieldAmplifierBase;
    }
}
