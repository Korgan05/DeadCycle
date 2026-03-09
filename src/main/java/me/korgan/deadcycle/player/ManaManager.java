package me.korgan.deadcycle.player;

import me.korgan.deadcycle.DeadCyclePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Управление XP как маной:
 * - Начальный максимум: 100
 * - При использовании скилла максимум растёт
 * - XP автоматически регенерируется
 */
public class ManaManager {

    private final DeadCyclePlugin plugin;
    private final Map<UUID, Integer> maxXpCache = new HashMap<>();
    private final Map<UUID, Double> regenAccumulator = new HashMap<>(); // Для точной регенерации
    private BukkitTask regenTask;
    private BukkitTask displayTask;

    // Конфигурация
    private int initialMaxXp;
    private double maxXpIncreasePerUse;

    public ManaManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        loadConfig();
        startRegeneration();
        startDisplay();
    }

    private void loadConfig() {
        this.initialMaxXp = plugin.getConfig().getInt("mana.initial_max", 100);
        this.maxXpIncreasePerUse = plugin.getConfig().getDouble("mana.max_increase_per_use", 0.5);
    }

    public void reload() {
        loadConfig();
    }

    public void shutdown() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
        if (displayTask != null) {
            displayTask.cancel();
            displayTask = null;
        }
        saveAll();
    }

    // ============================================
    // CORE XP METHODS
    // ============================================

    public int getCurrentXp(Player p) {
        return p.getLevel();
    }

    public int getMaxXp(UUID uuid) {
        if (!maxXpCache.containsKey(uuid)) {
            loadMaxXp(uuid);
        }
        return maxXpCache.getOrDefault(uuid, initialMaxXp);
    }

    public void setCurrentXp(Player p, int amount) {
        int max = getMaxXp(p.getUniqueId());
        p.setLevel(Math.max(0, Math.min(amount, max)));
    }

    public void setMaxXp(UUID uuid, int amount) {
        maxXpCache.put(uuid, Math.max(initialMaxXp, amount));
        saveMaxXp(uuid);
    }

    public boolean hasXp(Player p, int amount) {
        return getCurrentXp(p) >= amount;
    }

    public boolean consumeXp(Player p, int amount) {
        if (amount <= 0)
            return true;

        if (!hasXp(p, amount))
            return false;

        int current = getCurrentXp(p);
        setCurrentXp(p, current - amount);

        // Увеличение максимума XP при использовании
        increaseMaxXp(p.getUniqueId(), maxXpIncreasePerUse);

        return true;
    }

    public void addXp(Player p, int amount) {
        int current = getCurrentXp(p);
        int max = getMaxXp(p.getUniqueId());
        setCurrentXp(p, Math.min(max, current + amount));
    }

    public void increaseMaxXp(UUID uuid, double amount) {
        int current = getMaxXp(uuid);
        int newMax = current + (int) Math.ceil(amount);
        setMaxXp(uuid, newMax);
    }

    public void resetXp(Player p) {
        UUID uuid = p.getUniqueId();
        maxXpCache.put(uuid, initialMaxXp);
        p.setLevel(initialMaxXp);
        saveMaxXp(uuid);
    }

    public void resetAll() {
        maxXpCache.clear();
        regenAccumulator.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            maxXpCache.put(p.getUniqueId(), initialMaxXp);
            p.setLevel(initialMaxXp);
        }
        plugin.playerData().save();
    }

    // ============================================
    // REGENERATION
    // ============================================

    private void startRegeneration() {
        regenTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                int current = getCurrentXp(p);
                int max = getMaxXp(p.getUniqueId());

                if (current < max) {
                    UUID uuid = p.getUniqueId();

                    // Скорость регенерации зависит от максимума: max/1000 в сек
                    // Таск работает каждые 5 тиков (0.25 сек), поэтому за один вызов добавляются:
                    // max/1000 * 0.25 = max/4000
                    double regenThisTick = max / 4000.0;

                    // Накапливаем дробные значения
                    double accumulated = regenAccumulator.getOrDefault(uuid, 0.0);
                    accumulated += regenThisTick;

                    // Если накопилось >= 1, добавляем целый XP
                    if (accumulated >= 1.0) {
                        int toAdd = (int) accumulated;
                        setCurrentXp(p, Math.min(max, current + toAdd));
                        accumulated -= toAdd;
                    }

                    regenAccumulator.put(uuid, accumulated);
                }
            }
        }, 5L, 5L); // Каждые 5 тиков (0.25 секунды)
    }

    // ============================================
    // DISPLAY
    // ============================================

    private void startDisplay() {
        displayTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                displayXp(p);
            }
        }, 0L, 10L); // Каждые 0.5 секунды
    }

    private void displayXp(Player p) {
        int current = getCurrentXp(p);
        int max = getMaxXp(p.getUniqueId());

        // Формат: "⚡ 85/100"
        String text = String.format("§b⚡ %d§7/§3%d", current, max);
        p.sendActionBar(Component.text(text));
    }

    // ============================================
    // DATA MANAGEMENT
    // ============================================

    private void loadMaxXp(UUID uuid) {
        PlayerDataStore store = plugin.playerData();
        int max = store.getInt(uuid, "xp.max", initialMaxXp);
        maxXpCache.put(uuid, max);
    }

    private void saveMaxXp(UUID uuid) {
        PlayerDataStore store = plugin.playerData();
        int max = maxXpCache.getOrDefault(uuid, initialMaxXp);
        store.setInt(uuid, "xp.max", max);
    }

    public void saveAll() {
        for (Map.Entry<UUID, Integer> entry : maxXpCache.entrySet()) {
            saveMaxXp(entry.getKey());
        }
        plugin.playerData().save();
    }

    public void onPlayerJoin(Player p) {
        UUID uuid = p.getUniqueId();
        loadMaxXp(uuid);

        // Устанавливаем начальный XP если игрок новый
        if (p.getLevel() == 0) {
            p.setLevel(initialMaxXp);
        }
    }

    public void onPlayerQuit(UUID uuid) {
        if (maxXpCache.containsKey(uuid)) {
            saveMaxXp(uuid);
            maxXpCache.remove(uuid);
        }
        regenAccumulator.remove(uuid);
        plugin.playerData().save();
    }
}
