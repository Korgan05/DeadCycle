package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Location;
import org.bukkit.Particle;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * Скилл Лучника: Rain of Arrows
 * Спавнятся стрелы сверху в направлении взгляда игрока, радиус 10 блоков.
 * Количество стрел зависит от уровня: 10 + (уровень * 5)
 * Стоимость: 5 уровней опыта (базовая)
 * Кулдаун: 30 секунд
 */
public class ArcherRainSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final Random rng = new Random();

    // Конфиг параметры (загружаются из config.yml)
    private int xpCost;
    private long cooldownMs;
    private int baseArrowCount; // 10 по умолчанию
    private int arrowsPerLevel; // 5 по умолчанию
    private double spawnRadius;
    private double spawnHeight;
    private double distanceAhead; // На сколько блоков впереди спавнить центр
    private boolean showPreviewEnabled;

    public ArcherRainSkill(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        this.xpCost = plugin.getConfig().getInt("skills.archer.rain.xp_cost", 5);
        this.cooldownMs = plugin.getConfig().getLong("skills.archer.rain.cooldown_ms", 30000);
        this.baseArrowCount = plugin.getConfig().getInt("skills.archer.rain.arrow_count", 10);
        this.arrowsPerLevel = plugin.getConfig().getInt("skills.archer.rain.arrows_per_level", 5);
        this.spawnRadius = plugin.getConfig().getDouble("skills.archer.rain.radius", 10.0);
        this.spawnHeight = plugin.getConfig().getDouble("skills.archer.rain.spawn_height", 15.0);
        this.distanceAhead = plugin.getConfig().getDouble("skills.archer.rain.distance_ahead", 15.0);
        this.showPreviewEnabled = plugin.getConfig().getBoolean("skills.archer.rain.show_preview", true);
    }

    @Override
    public String getId() {
        return "archer_rain";
    }

    @Override
    public String getDisplayName() {
        return "§6Ливень стрел";
    }

    @Override
    public int getXpCost(Player p) {
        return xpCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        // Кулдаун уменьшается на 1000мс (1 сек) за каждый уровень лучника
        // Минимальный кулдаун: 5 секунд
        int archerLevel = plugin.progress().getArcherLevel(p.getUniqueId());
        long reducedCooldown = cooldownMs - (archerLevel * 1000L);
        long minimumCooldown = 5000L; // 5 секунд минимум
        return Math.max(reducedCooldown, minimumCooldown);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;

        int cost = getXpCost(p);
        if (p.getLevel() < cost)
            return false;

        return true;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cОшибка: игрок не в сети";

        int cost = getXpCost(p);
        if (p.getLevel() < cost)
            return "§cНедостаточно опыта! Нужно: " + cost + ", есть: " + p.getLevel();

        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        Location playerLoc = p.getLocation();
        if (playerLoc.getWorld() == null)
            return;

        // Получаем уровень лучника
        int archerLevel = plugin.progress().getArcherLevel(p.getUniqueId());

        // Вычисляем количество стрел: базовое + уровень * 5
        int arrowCount = baseArrowCount + (archerLevel * arrowsPerLevel);

        p.sendMessage("§6✦ Ливень стрел! Стрел: " + arrowCount);

        // Получаем направление взгляда игрока
        Vector lookDirection = p.getLocation().getDirection().normalize();

        // Центр спавна: впереди игрока на distanceAhead блоков
        Location centerLoc = playerLoc.clone().add(
                lookDirection.getX() * distanceAhead,
                0,
                lookDirection.getZ() * distanceAhead);

        // Спавним стрелы в радиусе вокруг этого центра
        for (int i = 0; i < arrowCount; i++) {
            // Случайная позиция в горизонтальном радиусе
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = rng.nextDouble() * spawnRadius;

            double offsetX = Math.cos(angle) * dist;
            double offsetZ = Math.sin(angle) * dist;

            Location spawnLoc = centerLoc.clone().add(offsetX, spawnHeight, offsetZ);

            // Спавним стрелу
            Arrow arrow = playerLoc.getWorld().spawnArrow(spawnLoc, new Vector(0, -1, 0), 3.5f, 2f);

            // Стрела не наносит урон, не может быть подобрана
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            arrow.setShooter(p);
            arrow.setCritical(true); // Критический урон

            // Удалим стрелу через 10 секунд (если она не упала)
            plugin.getServer().getScheduler().runTaskLater(plugin, arrow::remove, 200L);
        }
    }

    /**
     * Показать preview радиуса белыми партиклами - визуально показать где улетят
     * стрелы
     */
    public void showPreview(Player p) {
        if (!showPreviewEnabled)
            return;

        if (p == null || !p.isOnline())
            return;

        Location playerLoc = p.getLocation();
        if (playerLoc.getWorld() == null)
            return;

        // Получаем направление взгляда игрока
        Vector lookDirection = playerLoc.getDirection().normalize();

        // Центр спавна: впереди игрока
        Location centerLoc = playerLoc.clone().add(
                lookDirection.getX() * distanceAhead,
                0,
                lookDirection.getZ() * distanceAhead);

        // Показываем яркий белый круг на земле и на высоте спавна стрел.
        int segments = 48; // больше точек — круг плотнее
        for (int i = 0; i < segments; i++) {
            double angle = (i / (double) segments) * Math.PI * 2;
            double offsetX = Math.cos(angle) * spawnRadius;
            double offsetZ = Math.sin(angle) * spawnRadius;

            Location groundLoc = centerLoc.clone().add(offsetX, 0.1, offsetZ);
            // несколько частиц в точке для лучшей видимости
            playerLoc.getWorld().spawnParticle(Particle.WHITE_ASH, groundLoc, 6, 0.12, 0.12, 0.12, 0.02);

            Location highLoc = centerLoc.clone().add(offsetX, spawnHeight, offsetZ);
            playerLoc.getWorld().spawnParticle(Particle.WHITE_ASH, highLoc, 4, 0.08, 0.08, 0.08, 0.02);
        }

        // Покажем также заполненный радиус (несколько концентрических кругов),
        // чтобы игрок видел зону более явно
        int rings = 3;
        for (int r = 1; r <= rings; r++) {
            double rr = (spawnRadius * r) / (rings + 1);
            int seg = Math.max(12, (int) (segments * (rr / spawnRadius)));
            for (int i = 0; i < seg; i++) {
                double angle = (i / (double) seg) * Math.PI * 2;
                double offsetX = Math.cos(angle) * rr;
                double offsetZ = Math.sin(angle) * rr;
                Location loc = centerLoc.clone().add(offsetX, 0.1, offsetZ);
                playerLoc.getWorld().spawnParticle(Particle.WHITE_ASH, loc, 3, 0.12, 0.04, 0.12, 0.02);
            }
        }
    }

    @Override
    public void reset() {
        loadConfig();
    }
}
