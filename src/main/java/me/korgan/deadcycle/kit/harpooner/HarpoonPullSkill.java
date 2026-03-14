package me.korgan.deadcycle.kit.harpooner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.entity.Player;

public class HarpoonPullSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private double targetPullPowerBase;
    private double targetPullPowerPerLevel;
    private double selfPullPowerBase;
    private double selfPullPowerPerLevel;
    private double switchDistanceBase;
    private double switchDistancePerLevel;

    public HarpoonPullSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.harpooner.pull.xp_cost", 24));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.harpooner.pull.cooldown_ms", 12000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.harpooner.pull.cooldown_reduce_per_level_ms", 180L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.harpooner.pull.cooldown_min_ms", 4600L));

        this.targetPullPowerBase = Math.max(0.2,
                plugin.getConfig().getDouble("skills.harpooner.pull.target_pull_power_base", 1.05));
        this.targetPullPowerPerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.harpooner.pull.target_pull_power_per_level", 0.03));

        this.selfPullPowerBase = Math.max(0.2,
                plugin.getConfig().getDouble("skills.harpooner.pull.self_pull_power_base", 1.15));
        this.selfPullPowerPerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.harpooner.pull.self_pull_power_per_level", 0.035));

        this.switchDistanceBase = Math.max(2.0,
                plugin.getConfig().getDouble("skills.harpooner.pull.switch_distance_base", 6.5));
        this.switchDistancePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.harpooner.pull.switch_distance_per_level", 0.12));
    }

    @Override
    public String getId() {
        return "harpoon_pull";
    }

    @Override
    public String getDisplayName() {
        return "§bПодтяжка";
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

        return plugin.harpoonerKit().getPullError(p) == null
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

        String pullError = plugin.harpoonerKit().getPullError(p);
        if (pullError != null)
            return pullError;

        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline() || plugin.harpoonerKit() == null)
            return;

        boolean ok = plugin.harpoonerKit().triggerPull(
                p,
                getTargetPullPower(p),
                getSelfPullPower(p),
                getSwitchDistance(p));
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

    private double getTargetPullPower(Player p) {
        int level = plugin.progress().getHarpoonerLevel(p.getUniqueId());
        return targetPullPowerBase + Math.max(0, level - 1) * targetPullPowerPerLevel;
    }

    private double getSelfPullPower(Player p) {
        int level = plugin.progress().getHarpoonerLevel(p.getUniqueId());
        return selfPullPowerBase + Math.max(0, level - 1) * selfPullPowerPerLevel;
    }

    private double getSwitchDistance(Player p) {
        int level = plugin.progress().getHarpoonerLevel(p.getUniqueId());
        return switchDistanceBase + Math.max(0, level - 1) * switchDistancePerLevel;
    }
}
