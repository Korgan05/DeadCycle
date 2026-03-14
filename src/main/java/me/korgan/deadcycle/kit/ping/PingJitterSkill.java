package me.korgan.deadcycle.kit.ping;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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

public class PingJitterSkill implements Skill, Listener {

    private static final class JitterTrailDecoy {
        UUID ownerId;
        UUID decoyEntityId;
        long expiresAtMs;
    }

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private final Map<UUID, Long> jitterUntilByOwner = new HashMap<>();
    private final Map<UUID, Long> nextPulseAtByOwner = new HashMap<>();
    private final Map<UUID, Long> nextDebuffAtByTarget = new HashMap<>();
    private final Map<UUID, JitterTrailDecoy> trailDecoys = new HashMap<>();
    private final Map<UUID, Long> nextTrailSpawnAtByOwner = new HashMap<>();
    private final Map<UUID, Location> lastTrailSpawnLocByOwner = new HashMap<>();

    private BukkitTask jitterTask;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    private int durationSeconds;
    private int pulseIntervalTicks;
    private double radiusBase;
    private double radiusPerLevel;
    private double radiusMax;

    private int playerBlindnessTicks;
    private int zombieSlowTicks;
    private int monsterWeaknessTicks;
    private int internalDebuffCooldownTicks;

    private boolean trailDecoyEnabled;
    private int trailDecoyDurationTicks;
    private int trailSpawnIntervalTicks;
    private double trailSpawnMinStep;
    private double trailTauntRadius;
    private int trailMaxDecoysPerOwner;

    public PingJitterSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startJitterTask();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.ping.jitter.xp_cost", 20));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.ping.jitter.cooldown_ms", 12000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.ping.jitter.cooldown_reduce_per_level_ms", 150L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.ping.jitter.cooldown_min_ms", 4200L));

        this.durationSeconds = Math.max(1, plugin.getConfig().getInt("skills.ping.jitter.duration_seconds", 3));
        this.pulseIntervalTicks = Math.max(2,
                plugin.getConfig().getInt("skills.ping.jitter.pulse_interval_ticks", 10));

        this.radiusBase = Math.max(1.0, plugin.getConfig().getDouble("skills.ping.jitter.radius_base", 4.8));
        this.radiusPerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.ping.jitter.radius_per_level", 0.20));
        this.radiusMax = Math.max(radiusBase,
                plugin.getConfig().getDouble("skills.ping.jitter.radius_max", 6.8));

        this.playerBlindnessTicks = Math.max(0,
                plugin.getConfig().getInt("skills.ping.jitter.player_blindness_ticks", 18));
        this.zombieSlowTicks = Math.max(0, plugin.getConfig().getInt("skills.ping.jitter.zombie_slow_ticks", 24));
        this.monsterWeaknessTicks = Math.max(0,
                plugin.getConfig().getInt("skills.ping.jitter.monster_weakness_ticks", 24));
        this.internalDebuffCooldownTicks = Math.max(1,
                plugin.getConfig().getInt("skills.ping.jitter.internal_debuff_cooldown_ticks", 10));

        this.trailDecoyEnabled = plugin.getConfig().getBoolean("skills.ping.jitter.trail_decoy_enabled", true);
        this.trailDecoyDurationTicks = Math.max(8,
                plugin.getConfig().getInt("skills.ping.jitter.trail_decoy_duration_ticks", 36));
        this.trailSpawnIntervalTicks = Math.max(2,
                plugin.getConfig().getInt("skills.ping.jitter.trail_spawn_interval_ticks", 4));
        this.trailSpawnMinStep = Math.max(0.2,
                plugin.getConfig().getDouble("skills.ping.jitter.trail_spawn_min_step", 0.7));
        this.trailTauntRadius = Math.max(1.0,
                plugin.getConfig().getDouble("skills.ping.jitter.trail_taunt_radius", 7.0));
        this.trailMaxDecoysPerOwner = Math.max(1,
                plugin.getConfig().getInt("skills.ping.jitter.trail_max_decoys_per_owner", 6));
    }

    @Override
    public String getId() {
        return "ping_jitter";
    }

    @Override
    public String getDisplayName() {
        return "§9Джиттер";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getPingLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.PING)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.PING)
            return "§cЭтот навык доступен только киту Пинг.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        UUID ownerId = p.getUniqueId();
        long now = System.currentTimeMillis();
        jitterUntilByOwner.put(ownerId, now + durationSeconds * 1000L);
        nextPulseAtByOwner.put(ownerId, now);
        nextTrailSpawnAtByOwner.put(ownerId, now);
        lastTrailSpawnLocByOwner.put(ownerId, p.getLocation().clone());

        if (trailDecoyEnabled) {
            spawnTrailDecoy(p, p.getLocation());
        }

        Location fx = p.getLocation().clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.PORTAL, fx, 24, 0.35, 0.45, 0.35, 0.08);
        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, fx, 14, 0.25, 0.25, 0.25, 0.02);
        p.getWorld().playSound(fx, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.65f, 1.55f);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Джиттер активен"));

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        skillManager.setCooldown(ownerId, getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        jitterUntilByOwner.remove(id);
        nextPulseAtByOwner.remove(id);
        nextTrailSpawnAtByOwner.remove(id);
        lastTrailSpawnLocByOwner.remove(id);
        nextDebuffAtByTarget.remove(id);
        removeOwnerTrailDecoys(id, false);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        UUID deadId = e.getEntity().getUniqueId();
        nextDebuffAtByTarget.remove(deadId);
        trailDecoys.entrySet().removeIf(entry -> {
            JitterTrailDecoy decoy = entry.getValue();
            return decoy != null && deadId.equals(decoy.decoyEntityId);
        });
        if (e.getEntity() instanceof Player p) {
            UUID id = p.getUniqueId();
            jitterUntilByOwner.remove(id);
            nextPulseAtByOwner.remove(id);
            nextTrailSpawnAtByOwner.remove(id);
            lastTrailSpawnLocByOwner.remove(id);
            removeOwnerTrailDecoys(id, false);
        }
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private void startJitterTask() {
        if (jitterTask != null)
            jitterTask.cancel();
        jitterTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickJitter, 2L, 2L);
    }

    private void tickJitter() {
        long now = System.currentTimeMillis();
        tickTrailDecoys(now);

        if (jitterUntilByOwner.isEmpty())
            return;

        if (!nextDebuffAtByTarget.isEmpty()) {
            nextDebuffAtByTarget.entrySet().removeIf(entry -> now - entry.getValue() > 20_000L);
        }
        Iterator<Map.Entry<UUID, Long>> it = jitterUntilByOwner.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID ownerId = entry.getKey();
            long until = entry.getValue();

            if (now >= until) {
                it.remove();
                nextPulseAtByOwner.remove(ownerId);
                nextTrailSpawnAtByOwner.remove(ownerId);
                lastTrailSpawnLocByOwner.remove(ownerId);
                continue;
            }

            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline() || plugin.kit().getKit(ownerId) != KitManager.Kit.PING) {
                it.remove();
                nextPulseAtByOwner.remove(ownerId);
                nextTrailSpawnAtByOwner.remove(ownerId);
                lastTrailSpawnLocByOwner.remove(ownerId);
                removeOwnerTrailDecoys(ownerId, false);
                continue;
            }

            double radius = getRadius(owner);
            spawnFakeTraces(owner.getLocation(), radius);

            if (trailDecoyEnabled) {
                long nextTrailAt = nextTrailSpawnAtByOwner.getOrDefault(ownerId, 0L);
                if (now >= nextTrailAt) {
                    Location current = owner.getLocation().clone();
                    Location last = lastTrailSpawnLocByOwner.get(ownerId);
                    boolean movedEnough = last == null || last.getWorld() == null
                            || !last.getWorld().getUID().equals(current.getWorld().getUID())
                            || last.distanceSquared(current) >= trailSpawnMinStep * trailSpawnMinStep;
                    if (movedEnough) {
                        spawnTrailDecoy(owner, current);
                        lastTrailSpawnLocByOwner.put(ownerId, current.clone());
                    }
                    nextTrailSpawnAtByOwner.put(ownerId, now + trailSpawnIntervalTicks * 50L);
                }
            }

            long nextPulseAt = nextPulseAtByOwner.getOrDefault(ownerId, 0L);
            if (now < nextPulseAt)
                continue;

            nextPulseAtByOwner.put(ownerId, now + pulseIntervalTicks * 50L);
            applyPulse(owner, radius);
        }
    }

    private void spawnFakeTraces(Location center, double radius) {
        if (center == null || center.getWorld() == null)
            return;

        World world = center.getWorld();
        for (int i = 0; i < 6; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double dist = 0.4 + Math.random() * Math.min(1.6, radius * 0.45);
            double x = center.getX() + Math.cos(angle) * dist;
            double z = center.getZ() + Math.sin(angle) * dist;
            double y = center.getY() + 0.2 + Math.random() * 1.1;
            world.spawnParticle(Particle.PORTAL, x, y, z, 1, 0.02, 0.02, 0.02, 0.0);
            world.spawnParticle(Particle.ELECTRIC_SPARK, x, y, z, 1, 0.01, 0.01, 0.01, 0.0);
        }
    }

    private void applyPulse(Player owner, double radius) {
        int affected = 0;
        Location center = owner.getLocation();

        for (Entity entity : owner.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target))
                continue;
            if (!isJitterTarget(target, owner))
                continue;

            long nextDebuffAt = nextDebuffAtByTarget.getOrDefault(target.getUniqueId(), 0L);
            if (System.currentTimeMillis() < nextDebuffAt)
                continue;
            nextDebuffAtByTarget.put(target.getUniqueId(),
                    System.currentTimeMillis() + (long) internalDebuffCooldownTicks * 50L);

            if (target instanceof Player) {
                if (playerBlindnessTicks > 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, playerBlindnessTicks,
                            0, true, false, true));
                }
            } else {
                if (target instanceof Zombie && zombieSlowTicks > 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, zombieSlowTicks,
                            1, true, false, true));
                } else if (target instanceof Monster && monsterWeaknessTicks > 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, monsterWeaknessTicks,
                            0, true, false, true));
                }

                if (target instanceof Mob mob) {
                    LivingEntity decoyTarget = findNearestTrailDecoy(owner.getUniqueId(), target.getLocation());
                    if (decoyTarget != null) {
                        mob.setTarget(decoyTarget);
                    } else {
                        mob.setTarget(null);
                    }
                }
            }

            Location fx = target.getLocation().clone().add(0, Math.max(0.7, target.getHeight() * 0.5), 0);
            target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, fx, 5, 0.15, 0.15, 0.15, 0.01);
            affected++;
        }

        if (affected > 0) {
            owner.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.55f, 1.45f);
            owner.sendActionBar(net.kyori.adventure.text.Component.text("Джиттер: целей " + affected));
        }
    }

    private void tickTrailDecoys(long now) {
        if (trailDecoys.isEmpty())
            return;

        Iterator<Map.Entry<UUID, JitterTrailDecoy>> it = trailDecoys.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, JitterTrailDecoy> entry = it.next();
            JitterTrailDecoy decoy = entry.getValue();

            if (decoy == null || decoy.decoyEntityId == null || now >= decoy.expiresAtMs) {
                despawnTrailDecoy(decoy, true);
                it.remove();
                continue;
            }

            Player owner = Bukkit.getPlayer(decoy.ownerId);
            if (owner == null || !owner.isOnline() || owner.isDead()) {
                despawnTrailDecoy(decoy, false);
                it.remove();
                continue;
            }

            Entity entity = Bukkit.getEntity(decoy.decoyEntityId);
            if (!(entity instanceof ArmorStand stand) || stand.isDead() || !stand.isValid()) {
                it.remove();
                continue;
            }

            Location fx = stand.getLocation().clone().add(0, 0.9, 0);
            World world = stand.getWorld();
            world.spawnParticle(Particle.ELECTRIC_SPARK, fx, 4, 0.12, 0.18, 0.12, 0.01);
            world.spawnParticle(Particle.PORTAL, fx, 3, 0.18, 0.20, 0.18, 0.01);

            for (Entity nearby : world.getNearbyEntities(stand.getLocation(), trailTauntRadius, trailTauntRadius,
                    trailTauntRadius)) {
                if (!(nearby instanceof Mob mob))
                    continue;
                if (!isJitterTarget(mob, owner))
                    continue;
                mob.setTarget(stand);
            }
        }
    }

    private void spawnTrailDecoy(Player owner, Location at) {
        if (owner == null || !owner.isOnline())
            return;
        if (at == null || at.getWorld() == null)
            return;

        ArmorStand decoyStand = at.getWorld().spawn(at.clone().add(0, 0.03, 0), ArmorStand.class, stand -> {
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setSilent(true);
            stand.setVisible(false);
            stand.setSmall(true);
            stand.setArms(false);
            stand.setBasePlate(false);
            stand.setMarker(false);
            stand.setCustomNameVisible(false);
            stand.setGlowing(false);
        });

        JitterTrailDecoy decoy = new JitterTrailDecoy();
        decoy.ownerId = owner.getUniqueId();
        decoy.decoyEntityId = decoyStand.getUniqueId();
        decoy.expiresAtMs = System.currentTimeMillis() + trailDecoyDurationTicks * 50L;
        trailDecoys.put(decoy.decoyEntityId, decoy);

        trimOwnerTrailDecoys(owner.getUniqueId());

        Location fx = at.clone().add(0, 0.9, 0);
        at.getWorld().spawnParticle(Particle.PORTAL, fx, 7, 0.16, 0.20, 0.16, 0.02);
        at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, fx, 5, 0.12, 0.18, 0.12, 0.01);
    }

    private void trimOwnerTrailDecoys(UUID ownerId) {
        if (ownerId == null)
            return;

        while (true) {
            int count = 0;
            UUID oldestKey = null;
            long oldestExpiry = Long.MAX_VALUE;

            for (Map.Entry<UUID, JitterTrailDecoy> entry : trailDecoys.entrySet()) {
                JitterTrailDecoy decoy = entry.getValue();
                if (decoy == null || !ownerId.equals(decoy.ownerId))
                    continue;

                count++;
                if (decoy.expiresAtMs < oldestExpiry) {
                    oldestExpiry = decoy.expiresAtMs;
                    oldestKey = entry.getKey();
                }
            }

            if (count <= trailMaxDecoysPerOwner || oldestKey == null)
                break;

            JitterTrailDecoy removed = trailDecoys.remove(oldestKey);
            despawnTrailDecoy(removed, false);
        }
    }

    private void removeOwnerTrailDecoys(UUID ownerId, boolean withFx) {
        if (ownerId == null)
            return;

        Iterator<Map.Entry<UUID, JitterTrailDecoy>> it = trailDecoys.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, JitterTrailDecoy> entry = it.next();
            JitterTrailDecoy decoy = entry.getValue();
            if (decoy == null || !ownerId.equals(decoy.ownerId))
                continue;

            despawnTrailDecoy(decoy, withFx);
            it.remove();
        }
    }

    private void despawnTrailDecoy(JitterTrailDecoy decoy, boolean withFx) {
        if (decoy == null || decoy.decoyEntityId == null)
            return;

        Entity entity = Bukkit.getEntity(decoy.decoyEntityId);
        if (entity == null)
            return;

        if (withFx && entity.getWorld() != null) {
            Location fx = entity.getLocation().clone().add(0, 0.9, 0);
            entity.getWorld().spawnParticle(Particle.CLOUD, fx, 8, 0.16, 0.16, 0.16, 0.01);
            entity.getWorld().spawnParticle(Particle.PORTAL, fx, 6, 0.16, 0.16, 0.16, 0.02);
        }

        entity.remove();
    }

    private LivingEntity findNearestTrailDecoy(UUID ownerId, Location around) {
        if (ownerId == null || around == null || around.getWorld() == null)
            return null;

        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        long now = System.currentTimeMillis();

        for (JitterTrailDecoy decoy : trailDecoys.values()) {
            if (decoy == null || !ownerId.equals(decoy.ownerId))
                continue;
            if (now >= decoy.expiresAtMs)
                continue;

            Entity entity = Bukkit.getEntity(decoy.decoyEntityId);
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid())
                continue;
            if (!living.getWorld().getUID().equals(around.getWorld().getUID()))
                continue;

            double distSq = living.getLocation().distanceSquared(around);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = living;
            }
        }

        return best;
    }

    private double getRadius(Player owner) {
        int level = plugin.progress().getPingLevel(owner.getUniqueId());
        return Math.min(radiusMax, radiusBase + Math.max(0, level - 1) * radiusPerLevel);
    }

    private boolean isJitterTarget(LivingEntity target, Player owner) {
        if (target == null || owner == null)
            return false;
        if (target.getUniqueId().equals(owner.getUniqueId()))
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

        if (isBoss(target))
            return true;

        return isPlayerOwnedCompanion(target);
    }

    private boolean isBoss(LivingEntity target) {
        if (!(target instanceof org.bukkit.entity.Zombie zombie))
            return false;
        if (plugin.bossDuel() == null)
            return false;

        Byte bossMark = zombie.getPersistentDataContainer().get(plugin.bossDuel().bossMarkKey(),
                PersistentDataType.BYTE);
        return bossMark != null && bossMark == (byte) 1;
    }

    private boolean isPlayerOwnedCompanion(LivingEntity target) {
        if (plugin.cloneKit() != null) {
            Byte cloneMark = target.getPersistentDataContainer().get(plugin.cloneKit().cloneMarkKey(),
                    PersistentDataType.BYTE);
            if (cloneMark != null && cloneMark == (byte) 1)
                return true;
        }

        if (plugin.summonerKit() != null) {
            Byte summonMark = target.getPersistentDataContainer().get(plugin.summonerKit().summonMarkKey(),
                    PersistentDataType.BYTE);
            if (summonMark != null && summonMark == (byte) 1)
                return true;
        }

        return false;
    }
}
