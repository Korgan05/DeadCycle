package me.korgan.deadcycle.kit.archer;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class ArcherTrapArrowSkill implements Skill, Listener {

    private static final class TrapZone {
        UUID ownerId;
        Location center;
        long expiresAtMs;
    }

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private final Map<UUID, Long> armedUntilByOwner = new HashMap<>();
    private final Map<UUID, TrapZone> zones = new HashMap<>();
    private final Map<UUID, Long> rangedVulnerableUntilByTarget = new HashMap<>();
    private final Map<UUID, Long> nextZoneDebuffAtByTarget = new HashMap<>();

    private BukkitTask zoneTask;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private int armWindowSeconds;
    private int zoneDurationTicks;
    private double zoneRadius;
    private int slowSeconds;
    private int slowAmplifier;
    private int internalDebuffCooldownTicks;
    private int vulnerabilityTicks;
    private double rangedBonusMultiplier;

    public ArcherTrapArrowSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startZoneTask();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.archer.trap_arrow.xp_cost", 16));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.archer.trap_arrow.cooldown_ms", 11000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.archer.trap_arrow.cooldown_reduce_per_level_ms", 150L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.archer.trap_arrow.cooldown_min_ms", 4200L));

        this.armWindowSeconds = Math.max(1,
                plugin.getConfig().getInt("skills.archer.trap_arrow.arm_window_seconds", 8));
        this.zoneDurationTicks = Math.max(10,
                plugin.getConfig().getInt("skills.archer.trap_arrow.zone_duration_ticks", 50));
        this.zoneRadius = Math.max(0.8,
                plugin.getConfig().getDouble("skills.archer.trap_arrow.zone_radius", 2.8));

        this.slowSeconds = Math.max(0, plugin.getConfig().getInt("skills.archer.trap_arrow.slow_seconds", 2));
        this.slowAmplifier = Math.max(0, plugin.getConfig().getInt("skills.archer.trap_arrow.slow_amplifier", 1));
        this.internalDebuffCooldownTicks = Math.max(1,
                plugin.getConfig().getInt("skills.archer.trap_arrow.internal_debuff_cooldown_ticks", 12));
        this.vulnerabilityTicks = Math.max(1,
                plugin.getConfig().getInt("skills.archer.trap_arrow.vulnerability_ticks", 30));
        this.rangedBonusMultiplier = Math.max(0.0,
                plugin.getConfig().getDouble("skills.archer.trap_arrow.ranged_bonus_multiplier", 0.10));
    }

    @Override
    public String getId() {
        return "archer_trap_arrow";
    }

    @Override
    public String getDisplayName() {
        return "§6Капкан-стрела";
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

        long until = System.currentTimeMillis() + armWindowSeconds * 1000L;
        armedUntilByOwner.put(p.getUniqueId(), until);

        Location fx = p.getLocation().clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.CRIT, fx, 12, 0.25, 0.20, 0.25, 0.04);
        p.getWorld().spawnParticle(Particle.END_ROD, fx, 8, 0.18, 0.18, 0.18, 0.02);
        p.getWorld().playSound(fx, Sound.ENTITY_ARROW_SHOOT, 0.85f, 0.85f);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Капкан-стрела заряжена"));

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @EventHandler
    public void onArmedArrowHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile projectile))
            return;
        if (!(projectile.getShooter() instanceof Player owner))
            return;
        if (!(e.getEntity() instanceof LivingEntity target))
            return;

        UUID ownerId = owner.getUniqueId();
        long armedUntil = armedUntilByOwner.getOrDefault(ownerId, 0L);
        if (armedUntil <= 0L)
            return;

        long now = System.currentTimeMillis();
        if (now >= armedUntil) {
            armedUntilByOwner.remove(ownerId);
            return;
        }

        if (!isTrapTarget(owner, target))
            return;

        armedUntilByOwner.remove(ownerId);
        createTrapZone(owner, target.getLocation());
        owner.sendActionBar(net.kyori.adventure.text.Component.text("Капкан активирован"));
    }

    @EventHandler
    public void onRangedDamageBonus(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile projectile))
            return;
        if (!(projectile.getShooter() instanceof Player))
            return;
        if (!(e.getEntity() instanceof LivingEntity target))
            return;

        long now = System.currentTimeMillis();
        long until = rangedVulnerableUntilByTarget.getOrDefault(target.getUniqueId(), 0L);
        if (now >= until)
            return;

        e.setDamage(e.getDamage() * (1.0 + rangedBonusMultiplier));

        Location fx = target.getLocation().clone().add(0, Math.max(0.6, target.getHeight() * 0.5), 0);
        target.getWorld().spawnParticle(Particle.CRIT, fx, 6, 0.15, 0.15, 0.15, 0.02);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        UUID deadId = e.getEntity().getUniqueId();
        rangedVulnerableUntilByTarget.remove(deadId);
        nextZoneDebuffAtByTarget.remove(deadId);
        zones.values().removeIf(zone -> zone != null && deadId.equals(zone.ownerId));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        armedUntilByOwner.remove(id);
        zones.values().removeIf(zone -> zone != null && id.equals(zone.ownerId));
        rangedVulnerableUntilByTarget.remove(id);
        nextZoneDebuffAtByTarget.remove(id);
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private void startZoneTask() {
        if (zoneTask != null)
            zoneTask.cancel();
        zoneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickZones, 2L, 2L);
    }

    private void createTrapZone(Player owner, Location at) {
        if (owner == null || at == null || at.getWorld() == null)
            return;

        TrapZone zone = new TrapZone();
        zone.ownerId = owner.getUniqueId();
        zone.center = at.clone();
        zone.expiresAtMs = System.currentTimeMillis() + zoneDurationTicks * 50L;
        zones.put(UUID.randomUUID(), zone);

        World world = zone.center.getWorld();
        if (world != null) {
            Location fx = zone.center.clone().add(0, 0.2, 0);
            world.spawnParticle(Particle.SQUID_INK, fx, 10, 0.25, 0.10, 0.25, 0.02);
            world.playSound(fx, Sound.BLOCK_CHAIN_PLACE, 0.65f, 1.35f);
        }
    }

    private void tickZones() {
        if (zones.isEmpty() && rangedVulnerableUntilByTarget.isEmpty())
            return;

        long now = System.currentTimeMillis();

        if (!rangedVulnerableUntilByTarget.isEmpty()) {
            rangedVulnerableUntilByTarget.entrySet().removeIf(entry -> now >= entry.getValue());
        }
        if (!nextZoneDebuffAtByTarget.isEmpty()) {
            nextZoneDebuffAtByTarget.entrySet().removeIf(entry -> now - entry.getValue() > 20_000L);
        }

        Iterator<Map.Entry<UUID, TrapZone>> it = zones.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TrapZone> entry = it.next();
            TrapZone zone = entry.getValue();

            if (zone == null || zone.center == null || zone.center.getWorld() == null || now >= zone.expiresAtMs) {
                it.remove();
                continue;
            }

            Player owner = Bukkit.getPlayer(zone.ownerId);
            if (owner == null || !owner.isOnline()) {
                it.remove();
                continue;
            }

            World world = zone.center.getWorld();
            world.spawnParticle(Particle.ENCHANT, zone.center.clone().add(0, 0.25, 0), 4,
                    zoneRadius * 0.25, 0.02, zoneRadius * 0.25, 0.01);

            for (Entity entity : world.getNearbyEntities(zone.center, zoneRadius, 1.8, zoneRadius)) {
                if (!(entity instanceof LivingEntity target))
                    continue;
                if (!isTrapTarget(owner, target))
                    continue;

                long nextDebuffAt = nextZoneDebuffAtByTarget.getOrDefault(target.getUniqueId(), 0L);
                if (now < nextDebuffAt)
                    continue;
                nextZoneDebuffAtByTarget.put(target.getUniqueId(), now + (long) internalDebuffCooldownTicks * 50L);

                if (slowSeconds > 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                            Math.max(10, slowSeconds * 20), slowAmplifier, true, false, true));
                }

                long until = now + vulnerabilityTicks * 50L;
                long current = rangedVulnerableUntilByTarget.getOrDefault(target.getUniqueId(), 0L);
                if (until > current) {
                    rangedVulnerableUntilByTarget.put(target.getUniqueId(), until);
                }
            }
        }
    }

    private boolean isTrapTarget(Player owner, LivingEntity target) {
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
