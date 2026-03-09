package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.entity.Player;

public class CloneModeSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private long cooldownMs;

    public CloneModeSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.cooldownMs = Math.max(100L, plugin.getConfig().getLong("skills.cloner.mode.cooldown_ms", 700L));
    }

    @Override
    public String getId() {
        return "clone_mode";
    }

    @Override
    public String getDisplayName() {
        return "§dРежим Клонов";
    }

    @Override
    public double getManaCost(Player p) {
        return 0;
    }

    @Override
    public long getCooldownMs(Player p) {
        return cooldownMs;
    }

    @Override
    public boolean canUse(Player p) {
        return p != null
                && p.isOnline()
                && plugin.cloneKit() != null
                && plugin.kit().getKit(p.getUniqueId()) == KitManager.Kit.CLONER;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.CLONER)
            return "§cЭтот навык доступен только киту Клонер.";
        if (plugin.cloneKit() == null || !plugin.cloneKit().isEnabled())
            return "§cСистема клонов недоступна.";
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline() || plugin.cloneKit() == null)
            return;

        plugin.cloneKit().cycleMode(p);
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @Override
    public void reset() {
        loadConfig();
    }
}
