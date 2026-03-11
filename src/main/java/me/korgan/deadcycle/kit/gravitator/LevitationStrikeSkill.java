package me.korgan.deadcycle.kit.gravitator;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Скилл Гравитатора: Levitation Strike (Антигравитация)
 * Отправляет зомби вверх с уроном и эффектом левитации.
 * Стоимость: опыт (XP), если нет - HP.
 */
public class LevitationStrikeSkill implements Skill {

    private static final Color ANTIGRAVITY_LINE_COLOR = Color.fromRGB(180, 80, 255);
    private static final double UPWARD_FORCE_PER_LEVEL = 0.08;
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    // Конфиг параметры
    private int xpCost;
    private double hpCost;
    private long cooldownMs;
    private double radiusBase;
    private double radiusPerLevel;
    private double upwardForce;
    private double damageBase;
    private double damagePerLevel;
    private int levitationAmplifier;
    private int levitationDurationTicks;

    public LevitationStrikeSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.xpCost = plugin.getConfig().getInt("skills.gravitator.levitation.xp_cost", 20);
        this.hpCost = plugin.getConfig().getDouble("skills.gravitator.levitation.hp_cost", 2.0);
        this.cooldownMs = plugin.getConfig().getLong("skills.gravitator.levitation.cooldown_ms", 20000);
        this.radiusBase = plugin.getConfig().getDouble("skills.gravitator.levitation.radius_base", 8.0);
        this.radiusPerLevel = plugin.getConfig().getDouble("skills.gravitator.levitation.radius_per_level", 0.5);
        this.upwardForce = plugin.getConfig().getDouble("skills.gravitator.levitation.upward_force", 1.5);
        this.damageBase = plugin.getConfig().getDouble("skills.gravitator.levitation.damage_base", 3.0);
        this.damagePerLevel = plugin.getConfig().getDouble("skills.gravitator.levitation.damage_per_level", 0.5);
        this.levitationAmplifier = plugin.getConfig().getInt("skills.gravitator.levitation.levitation_amplifier", 2);
        this.levitationDurationTicks = plugin.getConfig()
                .getInt("skills.gravitator.levitation.levitation_duration_ticks", 60);
    }

    @Override
    public String getId() {
        return "levitation_strike";
    }

    @Override
    public String getDisplayName() {
        return "§dАнтигравитация";
    }

    @Override
    public double getManaCost(Player p) {
        return xpCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        return cooldownMs;
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;

        // Можно использовать если есть опыт ИЛИ есть достаточно HP
        int manaCost = (int) getManaCost(p);
        boolean hasXp = p.getLevel() >= manaCost;
        boolean hasHp = p.getHealth() > hpCost;
        return hasXp || hasHp;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cОшибка: игрок не в сети";
        int manaCost = (int) getManaCost(p);
        boolean hasXp = p.getLevel() >= manaCost;
        boolean hasHp = p.getHealth() > hpCost;
        if (!hasXp && !hasHp)
            return "§cНедостаточно опыта или HP!";
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        // Notifies boss that this skill is being used (for adaptation/counters)
        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, "levitation_strike");
        }

        World world = p.getWorld();
        Location center = p.getLocation();

        int level = plugin.progress().getGravitatorLevel(p.getUniqueId());
        double radius = radiusBase + (radiusPerLevel * level);
        double damage = damageBase + (damagePerLevel * level);
        double levelUpwardForce = upwardForce + (UPWARD_FORCE_PER_LEVEL * level);

        // Звуковой эффект
        world.playSound(center, Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.2f);

        // Визуальный круг радиуса поражения (фиолетовые частицы)
        spawnRadiusRing(world, center, radius);
        spawnRadiusRing(world, center, radius);
        startLiftAnimation(world, center, radius, level);

        // Собираем цели
        List<Zombie> targets = new ArrayList<>();

        for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof Zombie))
                continue;

            Zombie z = (Zombie) e;
            targets.add(z);

            // Отправляем зомби вверх
            Vector velocity = z.getVelocity().clone();
            velocity.setY(levelUpwardForce);
            z.setVelocity(velocity);

            // Наносим урон
            z.damage(damage, p);

            // Добавляем эффект левитации
            z.addPotionEffect(
                    new PotionEffect(PotionEffectType.LEVITATION, levitationDurationTicks, levitationAmplifier, true,
                            false, true));

            // Визуальный эффект вокруг зомби
            world.spawnParticle(Particle.CLOUD, z.getLocation().add(0, 0.5, 0), 5, 0.3, 0.3, 0.3, 0.1);
            world.spawnParticle(Particle.PORTAL, z.getLocation().add(0, 0.5, 0), 3, 0.2, 0.2, 0.2, 0.05);
        }

        // Устанавливаем кулдаун ПОСЛЕ использования скилла
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldownMs);

        // Выдаём опыт гравитатору
        plugin.progress().addGravitatorExp(p, 1);
    }

    private void spawnRadiusRing(World world, Location center, double radius) {
        int points = 48;
        for (int i = 0; i < points; i++) {
            double angle = (i / (double) points) * Math.PI * 2;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location loc = center.clone().add(x, 0.5, z);
            world.spawnParticle(Particle.ENCHANT, loc, 3, 0.1, 0.1, 0.1, 0.02);
            world.spawnParticle(Particle.REVERSE_PORTAL, loc, 2, 0.05, 0.02, 0.05, 0.01);
        }
    }

    private void startLiftAnimation(World world, Location center, double radius, int level) {
        int normalizedLevel = Math.max(0, level);
        int totalFrames = 8 + Math.min(10, normalizedLevel / 2);

        new org.bukkit.scheduler.BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                if (frame >= totalFrames) {
                    cancel();
                    return;
                }

                spawnLiftLinesFrame(world, center, radius, normalizedLevel, frame);
                frame++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void spawnLiftLinesFrame(World world, Location center, double radius, int level, int animationTick) {
        int lineCount = Math.min(84, 6 + level * 3 + (int) Math.round(radius * 1.1));
        double zoneRadius = Math.max(0.85, radius * 0.88);
        double baseLineHeight = 1.0 + (level * 0.14);
        double speed = Math.min(0.78, 0.26 + (level * 0.018));
        double phase = animationTick * speed;

        for (int i = 0; i < lineCount; i++) {
            double seed = i * 0.6180339887498949;
            double angle = (i * GOLDEN_ANGLE + animationTick * 0.09) % (Math.PI * 2.0);
            double distance = zoneRadius * Math.sqrt((i + 0.5) / lineCount);
            double x = Math.cos(angle) * distance;
            double z = Math.sin(angle) * distance;

            double baseY = -0.08 + (fract(seed * 9.0) * 0.95);
            double lineHeight = baseLineHeight * (0.72 + (fract(seed * 5.0) * 0.75));
            double progress = fract(seed + phase);
            double headY = baseY + lineHeight * progress;

            Particle.DustOptions dust = createLiftDust(level, seed);
            for (int trail = 0; trail < 4; trail++) {
                double y = headY - (trail * 0.12);
                if (y < baseY - 0.05)
                    break;

                Location point = center.clone().add(x, y, z);
                world.spawnParticle(Particle.DUST, point, 1, 0.008, 0.01, 0.008, 0.0, dust);
            }
        }
    }

    private Particle.DustOptions createLiftDust(int level, double seed) {
        float minSize = 0.18f;
        float maxSize = (float) Math.min(0.52, 0.34 + (level * 0.012));
        float ratio = (float) fract(seed * 13.0);
        float size = minSize + ((maxSize - minSize) * ratio);
        return new Particle.DustOptions(ANTIGRAVITY_LINE_COLOR, size);
    }

    private double fract(double value) {
        return value - Math.floor(value);
    }

    @Override
    public void reset() {
        loadConfig();
    }
}
