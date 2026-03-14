package me.korgan.deadcycle.kit.summoner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.entity.Player;

public class SummonerFocusCommandSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private double rangeBase;
    private double rangePerLevel;
    private double rangeMax;
    private int focusSecondsBase;
    private int focusSecondsPerLevel;

    public SummonerFocusCommandSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.summoner.focus.xp_cost", 20));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.summoner.focus.cooldown_ms", 8000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.summoner.focus.cooldown_reduce_per_level_ms", 130L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.summoner.focus.cooldown_min_ms", 3200L));

        this.rangeBase = Math.max(6.0, plugin.getConfig().getDouble("skills.summoner.focus.range_base", 20.0));
        this.rangePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.summoner.focus.range_per_level", 0.5));
        this.rangeMax = Math.max(rangeBase,
                plugin.getConfig().getDouble("skills.summoner.focus.range_max", 28.0));

        this.focusSecondsBase = Math.max(1,
                plugin.getConfig().getInt("skills.summoner.focus.focus_seconds_base", 3));
        this.focusSecondsPerLevel = Math.max(0,
                plugin.getConfig().getInt("skills.summoner.focus.focus_seconds_per_level", 0));
    }

    @Override
    public String getId() {
        return "summoner_focus";
    }

    @Override
    public String getDisplayName() {
        return "§6Фокус-команда";
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

        return plugin.summonerKit().getFocusError(p, getRange(p)) == null
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

        String focusError = plugin.summonerKit().getFocusError(p, getRange(p));
        if (focusError != null)
            return focusError;

        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();

        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline() || plugin.summonerKit() == null)
            return;

        boolean ok = plugin.summonerKit().focusTarget(p, getRange(p), getFocusSeconds(p));
        if (!ok)
            return;

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        long cooldown = getCooldownMs(p);
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldown);
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private double getRange(Player p) {
        int level = plugin.progress().getSummonerLevel(p.getUniqueId());
        return Math.min(rangeMax, rangeBase + Math.max(0, level - 1) * rangePerLevel);
    }

    private int getFocusSeconds(Player p) {
        int level = plugin.progress().getSummonerLevel(p.getUniqueId());
        return focusSecondsBase + Math.max(0, level - 1) * focusSecondsPerLevel;
    }
}
