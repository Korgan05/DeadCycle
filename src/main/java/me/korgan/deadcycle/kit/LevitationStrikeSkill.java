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
import java.util.List;

/**
 * Скилл Гравитатора: Levitation Strike (Антигравитация)
 * Отправляет зомби вверх с уроном и эффектом левитации.
 * Стоимость: опыт (XP), если нет - HP.
 */
public class LevitationStrikeSkill implements Skill {

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
        double damage = damageBase + (damagePerLevel * level);

        // Звуковой эффект
        world.playSound(center, Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.2f);

        // Визуальный круг радиуса поражения (фиолетовые частицы)
        spawnRadiusRing(world, center, radius);
        spawnRadiusRing(world, center, radius);

        // Собираем цели
        List<Zombie> targets = new ArrayList<>();

        for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(e instanceof Zombie))
                continue;

            Zombie z = (Zombie) e;
            targets.add(z);

            // Отправляем зомби вверх
            Vector velocity = z.getVelocity().clone();
            velocity.setY(upwardForce);
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

    @Override
    public void reset() {
        loadConfig();
    }
}
