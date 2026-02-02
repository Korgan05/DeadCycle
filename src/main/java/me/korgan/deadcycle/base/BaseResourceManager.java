package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;

import org.bukkit.Material;

public class BaseResourceManager {

    private final DeadCyclePlugin plugin;

    // Очки базы (общий пул)
    private long basePoints;

    // Для красивого отображения (по типам)
    private final Map<ResourceType, Long> pointsByType = new EnumMap<>(ResourceType.class);

    public BaseResourceManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        for (ResourceType t : ResourceType.values())
            pointsByType.put(t, 0L);
        load();
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        basePoints = cfg.getLong("base_resources.points", 0L);

        for (ResourceType t : ResourceType.values()) {
            long v = cfg.getLong("base_resources.by_type." + t.key, 0L);
            pointsByType.put(t, v);
        }
    }

    public void save() {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("base_resources.points", basePoints);

        for (ResourceType t : ResourceType.values()) {
            cfg.set("base_resources.by_type." + t.key, pointsByType.getOrDefault(t, 0L));
        }
        plugin.saveConfig();
    }

    public long getBasePoints() {
        return basePoints;
    }

    // === compatibility aliases (старые вызовы из GUI) ===
    public long getPoints() {
        return basePoints;
    }

    /**
     * Добавить/списать очки базы. Используй отрицательное значение, чтобы списать.
     * Для списания с проверкой лучше использовать spendPoints().
     */
    public void addPoints(long delta) {
        if (delta == 0)
            return;
        basePoints = Math.max(0L, basePoints + delta);
        save();
    }

    public long getPoints(ResourceType type) {
        return pointsByType.getOrDefault(type, 0L);
    }

    public void addPoints(ResourceType type, long points) {
        if (points <= 0)
            return;
        basePoints += points;
        pointsByType.put(type, getPoints(type) + points);
        save();
    }

    public boolean spendPoints(long points) {
        if (points <= 0)
            return true;
        if (basePoints < points)
            return false;
        basePoints -= points;
        save();
        return true;
    }

    public double moneyPerPoint() {
        return plugin.getConfig().getDouble("base_resources.money_per_point", 0.5);
    }

    public int pointsPer(Material m) {
        // веса как ты попросил
        if (m == Material.COBBLESTONE)
            return 5;
        if (m == Material.COAL)
            return 20;
        if (m == Material.IRON_INGOT)
            return 50;
        if (m == Material.DIAMOND)
            return 500;
        return 0;
    }

    public ResourceType typeOf(Material m) {
        if (m == Material.COBBLESTONE)
            return ResourceType.STONE;
        if (m == Material.COAL)
            return ResourceType.COAL;
        if (m == Material.IRON_INGOT)
            return ResourceType.IRON;
        if (m == Material.DIAMOND)
            return ResourceType.DIAMOND;
        return null;
    }

    public enum ResourceType {
        STONE("stone"),
        COAL("coal"),
        IRON("iron"),
        DIAMOND("diamond");

        public final String key;

        ResourceType(String key) {
            this.key = key;
        }
    }
}
