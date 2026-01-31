package me.korgan.deadcycle;

import me.korgan.deadcycle.base.*;
import me.korgan.deadcycle.econ.EconomyManager;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.player.ProgressManager;
import me.korgan.deadcycle.scoreboard.BaseScoreboard;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class DeadCyclePlugin extends JavaPlugin {

    private BaseManager base;
    private BaseResourceManager baseResources;
    private EconomyManager economy;
    private KitManager kit;
    private ProgressManager progress;
    private BaseScoreboard scoreboard;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        base = new BaseManager(this);
        baseResources = new BaseResourceManager(this);
        economy = new EconomyManager(this);
        kit = new KitManager(this);
        progress = new ProgressManager(this);
        scoreboard = new BaseScoreboard(this);

        // listeners
        Bukkit.getPluginManager().registerEvents(new ResourceDepositListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BaseBuildProtectionListener(this), this);

        // scoreboard обновление
        Bukkit.getScheduler().runTaskTimer(this, scoreboard::updateAll, 20L, 40L);

        getLogger().info("DeadCycle v0.5 enabled.");
    }

    public BaseManager base() { return base; }
    public BaseResourceManager baseResources() { return baseResources; }
    public EconomyManager economy() { return economy; }
    public KitManager kit() { return kit; }
    public ProgressManager progress() { return progress; }
}
