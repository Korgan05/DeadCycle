package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.configuration.file.FileConfiguration;

public class BaseUpgradeManager {

    private final DeadCyclePlugin plugin;

    private int wallLevel;
    private int repairLevel;

    public BaseUpgradeManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        wallLevel = cfg.getInt("upgrades.wall.level", 0);
        repairLevel = cfg.getInt("upgrades.repair.level", 0);
    }

    public void save() {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("upgrades.wall.level", wallLevel);
        cfg.set("upgrades.repair.level", repairLevel);
        plugin.saveConfig();
    }

    // ===== WALLS =====
    public int getWallLevel() { return wallLevel; }
    public int getWallMax() { return plugin.getConfig().getInt("upgrades.wall.max_level", 5); }

    public double wallHpMultiplier() {
        double per = plugin.getConfig().getDouble("upgrades.wall.hp_per_level", 0.2);
        return 1.0 + wallLevel * per;
    }

    public int wallCost() {
        int base = plugin.getConfig().getInt("upgrades.wall.cost_base", 500);
        int add  = plugin.getConfig().getInt("upgrades.wall.cost_add", 300);
        return base + wallLevel * add;
    }

    public boolean canUpgradeWall() {
        return wallLevel < getWallMax();
    }

    public boolean upgradeWall() {
        if (!canUpgradeWall()) return false;
        wallLevel++;
        save();
        return true;
    }

    // ===== REPAIR =====
    public int getRepairLevel() { return repairLevel; }
    public int getRepairMax() { return plugin.getConfig().getInt("upgrades.repair.max_level", 5); }

    public double repairMultiplier() {
        double per = plugin.getConfig().getDouble("upgrades.repair.speed_per_level", 0.25);
        return 1.0 + repairLevel * per;
    }

    public int repairCost() {
        int base = plugin.getConfig().getInt("upgrades.repair.cost_base", 400);
        int add  = plugin.getConfig().getInt("upgrades.repair.cost_add", 250);
        return base + repairLevel * add;
    }

    public boolean canUpgradeRepair() {
        return repairLevel < getRepairMax();
    }

    public boolean upgradeRepair() {
        if (!canUpgradeRepair()) return false;
        repairLevel++;
        save();
        return true;
    }
}
