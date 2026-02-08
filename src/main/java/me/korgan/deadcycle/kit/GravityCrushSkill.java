package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Скилл Гравитатора: Gravity Crush
 * Усиливает гравитацию, прижимает зомби к земле и наносит урон.
 * Стоимость: здоровье игрока (HP), а не опыт.
 */
public class GravityCrushSkill implements Skill {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    // Конфиг параметры
    private int xpCost;
    private double hpCost;
    private long cooldownMs;
    private double radiusBase;
    private double radiusPerLevel;
    private int durationTicks;
    private double damagePerTickBase;
    private double damagePerLevel;
    private int zombieSlowAmplifier;
    private int playerSlowAmplifier;

    public GravityCrushSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.xpCost = plugin.getConfig().getInt("skills.gravitator.crush.xp_cost", 25);
        this.hpCost = plugin.getConfig().getDouble("skills.gravitator.crush.hp_cost", 4.0);
        this.cooldownMs = plugin.getConfig().getLong("skills.gravitator.crush.cooldown_ms", 30000);
        this.radiusBase = plugin.getConfig().getDouble("skills.gravitator.crush.radius_base", 6.0);
        this.radiusPerLevel = plugin.getConfig().getDouble("skills.gravitator.crush.radius_per_level", 0.4);
        this.durationTicks = plugin.getConfig().getInt("skills.gravitator.crush.duration_ticks", 80);
        this.damagePerTickBase = plugin.getConfig().getDouble("skills.gravitator.crush.damage_per_tick_base", 2.0);
        this.damagePerLevel = plugin.getConfig().getDouble("skills.gravitator.crush.damage_per_level", 0.4);
        this.zombieSlowAmplifier = plugin.getConfig().getInt("skills.gravitator.crush.zombie_slow_amplifier", 10);
        this.playerSlowAmplifier = plugin.getConfig().getInt("skills.gravitator.crush.player_slow_amplifier", 2);
    }

    @Override
    public String getId() {
        return "gravity_crush";
    }

    @Override
    public String getDisplayName() {
        return "§5Гравитационный пресс";
    }

    @Override
    public int getXpCost(Player p) {
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
        boolean hasXp = p.getLevel() >= xpCost;
        boolean hasHp = p.getHealth() > hpCost;
        return hasXp || hasHp;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cОшибка: игрок не в сети";
        boolean hasXp = p.getLevel() >= xpCost;
        boolean hasHp = p.getHealth() > hpCost;
        if (!hasXp && !hasHp)
            return "§cНедостаточно опыта или HP!";
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        World world = p.getWorld();
        Location center = p.getLocation();

        int level = plugin.progress().getGravitatorLevel(p.getUniqueId());
        double radius = radiusBase + (radiusPerLevel * level);
        double damagePerTick = damagePerTickBase + (damagePerLevel * level);

        // Урон игроку за использование - только если опыт не потратили
        // (если опыт потратили, то HP не нужен)
        if (p.getLevel() < xpCost) {
            p.damage(hpCost);
        }

        // Прижимаем игрока к земле
        p.setVelocity(new Vector(0, -1, 0));
        p.addPotionEffect(
                new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, playerSlowAmplifier, true, false, true));

        // Звуковой эффект
        world.playSound(center, Sound.ENTITY_MINECART_RIDING, 1.0f, 0.7f);

        // Визуальный круг радиуса поражения
        spawnRadiusRing(world, center, radius);

        // Собираем цели
        List<Zombie> targets = new ArrayList<>();
        Map<UUID, Boolean> aiState = new HashMap<>();

        for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof Zombie))
                continue;

            Zombie z = (Zombie) e;
            targets.add(z);
            aiState.put(z.getUniqueId(), z.hasAI());

            // Прижимаем и выключаем ИИ
            z.setAI(false);
            z.setVelocity(new Vector(0, -1, 0));
            z.addPotionEffect(
                    new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, zombieSlowAmplifier, true, false, true));
            z.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, durationTicks, 1, true, false, true));
        }

        if (targets.isEmpty()) {
            // Даже если целей нет, устанавливаем кулдаун и выдаём опыт
            skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldownMs);
            plugin.progress().addGravitatorExp(p, 1);
            return;
        }

        // Наносим урон каждую секунду
        int totalSeconds = Math.max(1, durationTicks / 20);
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks += 20;

                for (Zombie z : targets) {
                    if (z == null || !z.isValid() || z.isDead())
                        continue;
                    z.damage(damagePerTick, p);
                    z.setVelocity(new Vector(0, -1, 0));
                }

                if (ticks >= totalSeconds * 20) {
                    // Восстанавливаем ИИ
                    for (Zombie z : targets) {
                        if (z == null || !z.isValid())
                            continue;
                        Boolean prev = aiState.get(z.getUniqueId());
                        if (prev != null)
                            z.setAI(prev);
                    }

                    // Устанавливаем кулдаун ПОСЛЕ завершения скилла
                    skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldownMs);

                    // Выдаём опыт гравитатору
                    plugin.progress().addGravitatorExp(p, 1);

                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void spawnRadiusRing(World world, Location center, double radius) {
        int points = 48;
        for (int i = 0; i < points; i++) {
            double angle = (i / (double) points) * Math.PI * 2;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location loc = center.clone().add(x, 0.2, z);
            world.spawnParticle(Particle.SMOKE, loc, 2, 0.05, 0.02, 0.05, 0.01);
            world.spawnParticle(Particle.PORTAL, loc, 2, 0.05, 0.02, 0.05, 0.01);
        }
    }

    @Override
    public void reset() {
        loadConfig();
    }
}
