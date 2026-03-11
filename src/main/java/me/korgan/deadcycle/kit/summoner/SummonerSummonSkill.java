package me.korgan.deadcycle.kit.summoner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.entity.Player;

public class SummonerSummonSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;
    private final SummonerKitManager.SummonType summonType;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    public SummonerSummonSkill(DeadCyclePlugin plugin, SkillManager skillManager,
            SummonerKitManager.SummonType summonType) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        this.summonType = summonType;
        loadConfig();
    }

    private void loadConfig() {
        String base = "skills.summoner." + summonType.configKey();
        this.manaCost = Math.max(1, plugin.getConfig().getInt(base + ".xp_cost", 40));
        this.cooldownMs = Math.max(300L, plugin.getConfig().getLong(base + ".cooldown_ms", 3500L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong(base + ".cooldown_reduce_per_level_ms", 120L));
        this.minCooldownMs = Math.max(300L,
                plugin.getConfig().getLong(base + ".cooldown_min_ms", 1200L));
    }

    @Override
    public String getId() {
        return summonType.skillId();
    }

    @Override
    public String getDisplayName() {
        return summonType.display();
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

        String summonError = plugin.summonerKit().getSummonError(p, summonType);
        if (summonError != null)
            return false;

        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.SUMMONER)
            return "§cЭтот навык доступен только киту Призыватель.";

        if (plugin.summonerKit() == null)
            return "§cСистема призыва недоступна.";

        String summonError = plugin.summonerKit().getSummonError(p, summonType);
        if (summonError != null)
            return summonError;

        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();

        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline() || plugin.summonerKit() == null)
            return;

        boolean ok = plugin.summonerKit().summon(p, summonType);
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
