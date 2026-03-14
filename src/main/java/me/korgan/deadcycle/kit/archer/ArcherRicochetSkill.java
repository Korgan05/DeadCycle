package me.korgan.deadcycle.kit.archer;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ArcherRicochetSkill implements Skill, Listener {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private final Map<UUID, Long> armedUntilByOwner = new HashMap<>();

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private int armWindowSeconds;
    private double ricochetRadius;
    private double ricochetDamageMultiplier;

    public ArcherRicochetSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.archer.ricochet.xp_cost", 18));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.archer.ricochet.cooldown_ms", 13000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.archer.ricochet.cooldown_reduce_per_level_ms", 150L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.archer.ricochet.cooldown_min_ms", 4800L));

        this.armWindowSeconds = Math.max(1, plugin.getConfig().getInt("skills.archer.ricochet.arm_window_seconds", 9));
        this.ricochetRadius = Math.max(1.0, plugin.getConfig().getDouble("skills.archer.ricochet.radius", 6.0));
        this.ricochetDamageMultiplier = Math.max(0.05,
                plugin.getConfig().getDouble("skills.archer.ricochet.damage_multiplier", 0.60));
    }

    @Override
    public String getId() {
        return "archer_ricochet";
    }

    @Override
    public String getDisplayName() {
        return "§eРикошет";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getArcherLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.ARCHER)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.ARCHER)
            return "§cЭтот навык доступен только киту Лучник.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        armedUntilByOwner.put(p.getUniqueId(), System.currentTimeMillis() + armWindowSeconds * 1000L);

        Location fx = p.getLocation().clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.CRIT, fx, 12, 0.25, 0.2, 0.25, 0.04);
        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, fx, 10, 0.2, 0.2, 0.2, 0.01);
        p.getWorld().playSound(fx, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.85f, 1.3f);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Рикошет заряжен"));

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @EventHandler
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity primary))
            return;

        Player owner = resolveOwner(e.getDamager());
        if (owner == null)
            return;

        UUID ownerId = owner.getUniqueId();
        long until = armedUntilByOwner.getOrDefault(ownerId, 0L);
        if (until <= 0L)
            return;

        long now = System.currentTimeMillis();
        if (now >= until) {
            armedUntilByOwner.remove(ownerId);
            return;
        }

        if (plugin.kit().getKit(ownerId) != KitManager.Kit.ARCHER) {
            armedUntilByOwner.remove(ownerId);
            return;
        }

        if (!isRicochetTarget(owner, primary))
            return;

        armedUntilByOwner.remove(ownerId);

        LivingEntity secondary = findSecondaryTarget(owner, primary, ricochetRadius);
        if (secondary == null) {
            owner.sendActionBar(net.kyori.adventure.text.Component.text("Рикошет: второй цели нет"));
            return;
        }

        double ricochetDamage = Math.max(0.0, e.getFinalDamage() * ricochetDamageMultiplier);
        if (ricochetDamage > 0.0) {
            secondary.damage(ricochetDamage, owner);
        }

        Location from = primary.getLocation().clone().add(0, Math.max(0.7, primary.getHeight() * 0.5), 0);
        Location to = secondary.getLocation().clone().add(0, Math.max(0.7, secondary.getHeight() * 0.5), 0);
        spawnRicochetLine(from, to);

        secondary.getWorld().playSound(to, Sound.ENTITY_ARROW_HIT_PLAYER, 0.75f, 1.3f);
        owner.sendActionBar(net.kyori.adventure.text.Component.text("Рикошет сработал"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        armedUntilByOwner.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private Player resolveOwner(Entity damager) {
        if (damager instanceof Player p)
            return p;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p)
            return p;
        return null;
    }

    private LivingEntity findSecondaryTarget(Player owner, LivingEntity primary, double radius) {
        LivingEntity best = null;
        double bestDist = radius * radius;

        for (Entity entity : primary.getWorld().getNearbyEntities(primary.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity candidate))
                continue;
            if (candidate.getUniqueId().equals(primary.getUniqueId()))
                continue;
            if (!isRicochetTarget(owner, candidate))
                continue;

            double dist = candidate.getLocation().distanceSquared(primary.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }

        return best;
    }

    private void spawnRicochetLine(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null)
            return;
        if (!from.getWorld().getUID().equals(to.getWorld().getUID()))
            return;

        Vector delta = to.toVector().subtract(from.toVector());
        int points = 10;
        Vector step = delta.multiply(1.0 / points);
        Location cur = from.clone();

        for (int i = 0; i <= points; i++) {
            from.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, cur, 1, 0.01, 0.01, 0.01, 0.0);
            from.getWorld().spawnParticle(Particle.CRIT, cur, 1, 0.02, 0.02, 0.02, 0.0);
            cur.add(step);
        }
    }

    private boolean isRicochetTarget(Player owner, LivingEntity target) {
        if (owner == null || target == null)
            return false;
        if (owner.getUniqueId().equals(target.getUniqueId()))
            return false;
        if (!target.isValid() || target.isDead())
            return false;

        if (target instanceof Player other) {
            if (other.getGameMode() == org.bukkit.GameMode.SPECTATOR)
                return false;
            if (plugin.bossDuel() == null || !plugin.bossDuel().isDuelActive())
                return false;
            return plugin.bossDuel().isDuelPlayer(owner.getUniqueId())
                    && plugin.bossDuel().isDuelPlayer(other.getUniqueId());
        }

        if (target instanceof Monster)
            return true;

        if (target instanceof Zombie zombie && plugin.bossDuel() != null) {
            Byte bossMark = zombie.getPersistentDataContainer().get(plugin.bossDuel().bossMarkKey(),
                    PersistentDataType.BYTE);
            return bossMark != null && bossMark == (byte) 1;
        }

        return false;
    }
}
