package me.korgan.deadcycle.kit.harpooner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.entity.Player;

public class HarpoonAnchorSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private double rangeBase;
    private double rangePerLevel;
    private double rangeMax;
    private double impactDamageBase;
    private double impactDamagePerLevel;

    public HarpoonAnchorSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.harpooner.anchor.xp_cost", 20));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.harpooner.anchor.cooldown_ms", 9000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.harpooner.anchor.cooldown_reduce_per_level_ms", 170L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.harpooner.anchor.cooldown_min_ms", 3600L));

        this.rangeBase = Math.max(4.0, plugin.getConfig().getDouble("skills.harpooner.anchor.range_base", 10.0));
        this.rangePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.harpooner.anchor.range_per_level", 0.2));
        this.rangeMax = Math.max(rangeBase,
                plugin.getConfig().getDouble("skills.harpooner.anchor.range_max", 13.0));

        this.impactDamageBase = Math.max(0.0,
                plugin.getConfig().getDouble("skills.harpooner.anchor.impact_damage_base", 3.0));
        this.impactDamagePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.harpooner.anchor.impact_damage_per_level", 0.25));
    }

    @Override
    public String getId() {
        return "harpoon_anchor";
    }

    @Override
    public String getDisplayName() {
        return "§3Якорный Гарпун";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getHarpoonerLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.HARPOONER)
            return false;
        if (plugin.harpoonerKit() == null)
            return false;

        return plugin.harpoonerKit().getAnchorError(p, getRange(p)) == null
                && p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.HARPOONER)
            return "§cЭтот навык доступен только киту Гарпунер.";
        if (plugin.harpoonerKit() == null)
            return "§cСистема Гарпунера недоступна.";

        String anchorError = plugin.harpoonerKit().getAnchorError(p, getRange(p));
        if (anchorError != null)
            return anchorError;

        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline() || plugin.harpoonerKit() == null)
            return;

        boolean ok = plugin.harpoonerKit().castAnchor(p, getRange(p), getImpactDamage(p));
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
        int level = plugin.progress().getHarpoonerLevel(p.getUniqueId());
        return Math.min(rangeMax, rangeBase + Math.max(0, level - 1) * rangePerLevel);
    }

    private double getImpactDamage(Player p) {
        int level = plugin.progress().getHarpoonerLevel(p.getUniqueId());
        return impactDamageBase + Math.max(0, level - 1) * impactDamagePerLevel;
    }
}
