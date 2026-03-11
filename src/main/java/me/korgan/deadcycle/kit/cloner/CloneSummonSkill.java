package me.korgan.deadcycle.kit.cloner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.entity.Player;

public class CloneSummonSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    public CloneSummonSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.cloner.summon.xp_cost", 50));
        this.cooldownMs = Math.max(200L, plugin.getConfig().getLong("skills.cloner.summon.cooldown_ms", 3000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.cloner.summon.cooldown_reduce_per_level_ms", 120L));
        this.minCooldownMs = Math.max(250L,
                plugin.getConfig().getLong("skills.cloner.summon.cooldown_min_ms", 1000L));
    }

    @Override
    public String getId() {
        return "clone_summon";
    }

    @Override
    public String getDisplayName() {
        return "§bПризыв Клона";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getClonerLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.CLONER)
            return false;

        String summonError = plugin.cloneKit() != null ? plugin.cloneKit().getSummonError(p)
                : "§cСистема клонов недоступна.";
        if (summonError != null)
            return false;

        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.CLONER)
            return "§cЭтот навык доступен только киту Клонер.";

        if (plugin.cloneKit() == null)
            return "§cСистема клонов недоступна.";

        String summonError = plugin.cloneKit().getSummonError(p);
        if (summonError != null)
            return summonError;

        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();

        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline() || plugin.cloneKit() == null)
            return;

        boolean ok = plugin.cloneKit().summonClone(p);
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
}
