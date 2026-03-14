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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class PingBlinkSkill implements Skill, Listener {

    private static final class PhantomTrail {
        UUID ownerId;
        Location location;
        long expiresAtMs;
    }

    private static final class PacketPhantomDecoy {
        UUID ownerId;
        UUID decoyEntityId;
        long expiresAtMs;
        double tauntRadius;
    }

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;
    private final Map<UUID, PhantomTrail> trails = new HashMap<>();
    private final Map<UUID, PacketPhantomDecoy> decoyByOwner = new HashMap<>();

    private BukkitTask trailTask;

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private double distanceBase;
    private double distancePerLevel;
    private double distanceMax;
    private int speedSeconds;
    private int speedAmplifier;

    private boolean phantomTrailEnabled;
    private int phantomTrailDurationTicks;
    private double phantomTrailTriggerRadius;
    private double phantomTrailDamage;
    private int phantomTrailBlindnessSeconds;
    private int phantomTrailZombieSlowSeconds;
    private int phantomTrailMonsterWeaknessSeconds;

    private boolean packetPhantomEnabled;
    private int packetPhantomUnlockLevel;
    private int packetPhantomDurationTicks;
    private double packetPhantomTauntRadius;
    private boolean packetPhantomShowName;

    public PingBlinkSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startTrailTask();
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.ping.blink.xp_cost", 18));
        this.cooldownMs = Math.max(300L, plugin.getConfig().getLong("skills.ping.blink.cooldown_ms", 6200L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.ping.blink.cooldown_reduce_per_level_ms", 160L));
        this.minCooldownMs = Math.max(300L, plugin.getConfig().getLong("skills.ping.blink.cooldown_min_ms", 2200L));

        this.distanceBase = Math.max(2.0, plugin.getConfig().getDouble("skills.ping.blink.distance_base", 6.5));
        this.distancePerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.ping.blink.distance_per_level", 0.25));
        this.distanceMax = Math.max(distanceBase,
                plugin.getConfig().getDouble("skills.ping.blink.max_distance", 10.0));

        this.speedSeconds = Math.max(0, plugin.getConfig().getInt("skills.ping.blink.speed_seconds", 2));
        this.speedAmplifier = Math.max(0, plugin.getConfig().getInt("skills.ping.blink.speed_amplifier", 1));

        this.phantomTrailEnabled = plugin.getConfig().getBoolean("skills.ping.blink.phantom_trail_enabled", true);
        this.phantomTrailDurationTicks = Math.max(5,
                plugin.getConfig().getInt("skills.ping.blink.phantom_trail_duration_ticks", 40));
        this.phantomTrailTriggerRadius = Math.max(0.5,
                plugin.getConfig().getDouble("skills.ping.blink.phantom_trail_trigger_radius", 1.35));
        this.phantomTrailDamage = Math.max(0.0,
                plugin.getConfig().getDouble("skills.ping.blink.phantom_trail_damage", 2.0));
        this.phantomTrailBlindnessSeconds = Math.max(0,
                plugin.getConfig().getInt("skills.ping.blink.phantom_trail_blindness_seconds", 1));
        this.phantomTrailZombieSlowSeconds = Math.max(0,
                plugin.getConfig().getInt("skills.ping.blink.phantom_trail_zombie_slow_seconds", 2));
        this.phantomTrailMonsterWeaknessSeconds = Math.max(0,
                plugin.getConfig().getInt("skills.ping.blink.phantom_trail_monster_weakness_seconds", 2));

        this.packetPhantomEnabled = plugin.getConfig().getBoolean("skills.ping.blink.packet_phantom_enabled", true);
        this.packetPhantomUnlockLevel = Math.max(1,
                plugin.getConfig().getInt("skills.ping.blink.packet_phantom_unlock_level", 8));
        this.packetPhantomDurationTicks = Math.max(20,
                plugin.getConfig().getInt("skills.ping.blink.packet_phantom_duration_ticks", 50));
        this.packetPhantomTauntRadius = Math.max(2.0,
                plugin.getConfig().getDouble("skills.ping.blink.packet_phantom_taunt_radius", 7.5));
        this.packetPhantomShowName = plugin.getConfig().getBoolean("skills.ping.blink.packet_phantom_show_name", true);
    }

    @Override
    public String getId() {
        return "ping_blink";
    }

    @Override
    public String getDisplayName() {
        return "§bПинг-Рывок";
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
        if (findBlinkTarget(p) == null)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.PING)
            return "§cЭтот навык доступен только киту Пинг.";
        if (findBlinkTarget(p) == null)
            return "§cНет безопасной точки для рывка впереди.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        Location from = p.getLocation().clone();
        Location target = findBlinkTarget(p);
        if (target == null)
            return;

        boolean ok = p.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (!ok)
            return;

        p.setFallDistance(0.0f);

        if (speedSeconds > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speedSeconds * 20,
                    Math.max(0, speedAmplifier), true, false, true));
        }

        World world = p.getWorld();
        Location fromFx = from.clone().add(0, 1.0, 0);
        Location toFx = target.clone().add(0, 1.0, 0);

        world.spawnParticle(Particle.PORTAL, fromFx, 24, 0.35, 0.5, 0.35, 0.08);
        world.spawnParticle(Particle.ELECTRIC_SPARK, toFx, 22, 0.3, 0.4, 0.3, 0.02);
        world.spawnParticle(Particle.CRIT, toFx, 8, 0.20, 0.20, 0.20, 0.01);

        Vector segment = toFx.toVector().subtract(fromFx.toVector()).multiply(1.0 / 12.0);
        Location trail = fromFx.clone();
        for (int i = 0; i < 12; i++) {
            trail.add(segment);
            world.spawnParticle(Particle.ELECTRIC_SPARK, trail, 2, 0.05, 0.05, 0.05, 0.01);
        }

        world.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.5f);
        world.playSound(target, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.55f, 1.25f);

        spawnPhantomSilhouette(from.clone(), Particle.ELECTRIC_SPARK, Particle.PORTAL);

        createPhantomTrail(p, from);
        createPacketPhantom(p, from);

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        long cooldown = getCooldownMs(p);
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldown);
    }

    private Location findBlinkTarget(Player p) {
        int level = plugin.progress().getPingLevel(p.getUniqueId());
        double maxDistance = Math.min(distanceMax, distanceBase + Math.max(0, level - 1) * distancePerLevel);
        boolean duelPlayer = plugin.bossDuel() != null
                && plugin.bossDuel().isDuelActive()
                && plugin.bossDuel().isDuelPlayer(p.getUniqueId());

        Location start = p.getLocation().clone();
        Vector direction = start.getDirection().setY(0.0);
        if (direction.lengthSquared() < 0.0001) {
            direction = p.getEyeLocation().getDirection().setY(0.0);
        }
        if (direction.lengthSquared() < 0.0001) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize();

        Location best = null;
        for (double dist = 0.8; dist <= maxDistance; dist += 0.8) {
            Location candidate = start.clone().add(direction.clone().multiply(dist));
            candidate.setX(candidate.getBlockX() + 0.5);
            candidate.setZ(candidate.getBlockZ() + 0.5);

            if (isSafeStandLocation(candidate)) {
                if (duelPlayer && !plugin.bossDuel().isInsideDuelZone(candidate, -1.0)) {
                    continue;
                }
                best = candidate;
            }
        }

        return best;
    }

    private boolean isSafeStandLocation(Location loc) {
        if (loc == null || loc.getWorld() == null)
            return false;

        Location feet = loc.clone();
        Location head = loc.clone().add(0, 1, 0);
        Location below = loc.clone().add(0, -1, 0);

        if (feet.getBlock().getType().isSolid())
            return false;
        if (head.getBlock().getType().isSolid())
            return false;
        return below.getBlock().getType().isSolid();
    }

    @Override
    public void reset() {
        loadConfig();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID playerId = e.getPlayer().getUniqueId();
        trails.entrySet().removeIf(entry -> playerId.equals(entry.getValue().ownerId));
        removePacketPhantom(playerId, false);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        UUID dead = e.getEntity().getUniqueId();
        trails.entrySet().removeIf(entry -> dead.equals(entry.getValue().ownerId));
        removePacketPhantom(dead, false);

        // Если умер сам декой, убираем запись владельца.
        decoyByOwner.entrySet().removeIf(entry -> {
            PacketPhantomDecoy decoy = entry.getValue();
            return decoy != null && dead.equals(decoy.decoyEntityId);
        });
    }

    private void startTrailTask() {
        if (trailTask != null)
            trailTask.cancel();

        trailTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tickPhantomTrails();
            tickPacketPhantoms();
        }, 2L, 2L);
    }

    private void createPhantomTrail(Player owner, Location at) {
        if (!phantomTrailEnabled)
            return;
        if (owner == null || at == null || at.getWorld() == null)
            return;

        PhantomTrail trail = new PhantomTrail();
        trail.ownerId = owner.getUniqueId();
        trail.location = at.clone().add(0, 0.05, 0);
        trail.expiresAtMs = System.currentTimeMillis() + phantomTrailDurationTicks * 50L;
        trails.put(UUID.randomUUID(), trail);
    }

    private void createPacketPhantom(Player owner, Location at) {
        if (!packetPhantomEnabled)
            return;
        if (owner == null || !owner.isOnline())
            return;

        int level = plugin.progress().getPingLevel(owner.getUniqueId());
        if (level < packetPhantomUnlockLevel)
            return;

        if (at == null || at.getWorld() == null)
            return;

        UUID ownerId = owner.getUniqueId();
        removePacketPhantom(ownerId, false);

        ArmorStand phantom = at.getWorld().spawn(at.clone().add(0, 0.03, 0), ArmorStand.class, stand -> {
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setSilent(true);
            stand.setSmall(false);
            stand.setArms(false);
            stand.setBasePlate(false);
            stand.setVisible(false);
            stand.setMarker(false);
            stand.setCustomName(packetPhantomShowName ? "§bПакетный Фантом" : null);
            stand.setCustomNameVisible(packetPhantomShowName);
            stand.setGlowing(false);
        });

        PacketPhantomDecoy decoy = new PacketPhantomDecoy();
        decoy.ownerId = ownerId;
        decoy.decoyEntityId = phantom.getUniqueId();
        decoy.expiresAtMs = System.currentTimeMillis() + packetPhantomDurationTicks * 50L;
        decoy.tauntRadius = packetPhantomTauntRadius;
        decoyByOwner.put(ownerId, decoy);

        World world = at.getWorld();
        world.spawnParticle(Particle.PORTAL, at.clone().add(0, 1.0, 0), 28, 0.25, 0.45, 0.25, 0.08);
        spawnPhantomSilhouette(at.clone(), Particle.ELECTRIC_SPARK, Particle.PORTAL);
        world.playSound(at, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.75f, 1.5f);
        owner.sendActionBar(net.kyori.adventure.text.Component.text("Пакетный фантом активен"));
    }

    private void tickPacketPhantoms() {
        if (decoyByOwner.isEmpty())
            return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PacketPhantomDecoy>> it = decoyByOwner.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PacketPhantomDecoy> entry = it.next();
            PacketPhantomDecoy decoy = entry.getValue();
            if (decoy == null || decoy.decoyEntityId == null) {
                it.remove();
                continue;
            }

            Player owner = Bukkit.getPlayer(decoy.ownerId);
            if (owner == null || !owner.isOnline() || owner.isDead() || now >= decoy.expiresAtMs) {
                despawnDecoyEntity(decoy, true);
                it.remove();
                continue;
            }

            Entity entity = Bukkit.getEntity(decoy.decoyEntityId);
            if (!(entity instanceof ArmorStand stand) || stand.isDead() || !stand.isValid()) {
                it.remove();
                continue;
            }

            World world = stand.getWorld();
            spawnPhantomSilhouette(stand.getLocation().clone(), Particle.ELECTRIC_SPARK, Particle.PORTAL);

            for (Entity nearby : world.getNearbyEntities(stand.getLocation(), decoy.tauntRadius, decoy.tauntRadius,
                    decoy.tauntRadius)) {
                if (!(nearby instanceof Mob mob))
                    continue;
                if (mob.getUniqueId().equals(owner.getUniqueId()))
                    continue;
                if (mob.isDead() || !mob.isValid())
                    continue;
                mob.setTarget(stand);
            }
        }
    }

    private void removePacketPhantom(UUID ownerId, boolean withFx) {
        if (ownerId == null)
            return;

        PacketPhantomDecoy old = decoyByOwner.remove(ownerId);
        if (old == null)
            return;

        despawnDecoyEntity(old, withFx);
    }

    private void despawnDecoyEntity(PacketPhantomDecoy decoy, boolean withFx) {
        if (decoy == null || decoy.decoyEntityId == null)
            return;

        Entity entity = Bukkit.getEntity(decoy.decoyEntityId);
        if (entity == null)
            return;

        if (withFx && entity.getWorld() != null) {
            Location fx = entity.getLocation().clone().add(0, 1.0, 0);
            entity.getWorld().spawnParticle(Particle.CLOUD, fx, 18, 0.25, 0.20, 0.25, 0.02);
            entity.getWorld().spawnParticle(Particle.PORTAL, fx, 12, 0.20, 0.22, 0.20, 0.04);
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.55f, 1.7f);
        }
        entity.remove();
    }

    private void tickPhantomTrails() {
        if (trails.isEmpty())
            return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PhantomTrail>> it = trails.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, PhantomTrail> entry = it.next();
            PhantomTrail trail = entry.getValue();

            if (trail == null || trail.location == null || trail.location.getWorld() == null
                    || now >= trail.expiresAtMs) {
                it.remove();
                continue;
            }

            Player owner = Bukkit.getPlayer(trail.ownerId);
            if (owner == null || !owner.isOnline()) {
                it.remove();
                continue;
            }

            World world = trail.location.getWorld();
            spawnPhantomSilhouette(trail.location.clone(), Particle.ELECTRIC_SPARK, Particle.PORTAL);

            boolean triggered = false;
            for (Entity entity : world.getNearbyEntities(trail.location, phantomTrailTriggerRadius,
                    phantomTrailTriggerRadius, phantomTrailTriggerRadius)) {
                if (!(entity instanceof LivingEntity target))
                    continue;
                if (!isTrailTarget(target, owner))
                    continue;

                applyTrailEffect(owner, target, trail.location);
                triggered = true;
                break;
            }

            if (triggered) {
                it.remove();
            }
        }
    }

    private boolean isTrailTarget(LivingEntity target, Player owner) {
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

    private void spawnPhantomSilhouette(Location base, Particle bodyParticle, Particle accentParticle) {
        if (base == null || base.getWorld() == null)
            return;

        World world = base.getWorld();
        double x = base.getX();
        double y = base.getY() + 0.05;
        double z = base.getZ();

        for (int i = 0; i <= 6; i++) {
            spawnParticlePoint(world, bodyParticle, x, y + 0.25 + (i * 0.22), z);
        }

        for (int i = -2; i <= 2; i++) {
            spawnParticlePoint(world, bodyParticle, x + (i * 0.10), y + 1.45, z);
        }

        for (int i = 0; i <= 3; i++) {
            spawnParticlePoint(world, bodyParticle, x - 0.22, y + 1.20 - (i * 0.16), z);
            spawnParticlePoint(world, bodyParticle, x + 0.22, y + 1.20 - (i * 0.16), z);
        }

        for (int i = 0; i <= 3; i++) {
            spawnParticlePoint(world, bodyParticle, x - 0.11, y + 0.62 - (i * 0.15), z);
            spawnParticlePoint(world, bodyParticle, x + 0.11, y + 0.62 - (i * 0.15), z);
        }

        Particle accent = (accentParticle == null) ? bodyParticle : accentParticle;
        double headY = y + 1.82;
        double headRadius = 0.16;
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2.0 * i) / 8.0;
            double px = x + Math.cos(angle) * headRadius;
            double pz = z + Math.sin(angle) * headRadius;
            spawnParticlePoint(world, accent, px, headY, pz);
        }
    }

    private void spawnParticlePoint(World world, Particle particle, double x, double y, double z) {
        if (world == null || particle == null)
            return;
        world.spawnParticle(particle, x, y, z, 1, 0.01, 0.01, 0.01, 0.0);
    }

    private void applyTrailEffect(Player owner, LivingEntity target, Location trailLoc) {
        if (owner == null || target == null || trailLoc == null)
            return;

        if (phantomTrailDamage > 0.0) {
            target.damage(phantomTrailDamage, owner);
        }

        if (target instanceof org.bukkit.entity.Zombie) {
            // Слепота не работает на зомби, поэтому даём замедление.
            if (phantomTrailZombieSlowSeconds > 0) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                        phantomTrailZombieSlowSeconds * 20, 1, true, false, true));
            }
        } else if (target instanceof Monster) {
            if (phantomTrailMonsterWeaknessSeconds > 0) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                        phantomTrailMonsterWeaknessSeconds * 20, 0, true, false, true));
            }
        } else if (target instanceof Player) {
            if (phantomTrailBlindnessSeconds > 0) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                        phantomTrailBlindnessSeconds * 20, 0, true, false, true));
            }
        }

        World world = trailLoc.getWorld();
        if (world != null) {
            Location fx = target.getLocation().clone().add(0, Math.max(0.7, target.getHeight() * 0.5), 0);
            world.spawnParticle(Particle.CRIT, fx, 6, 0.12, 0.12, 0.12, 0.01);
            world.spawnParticle(Particle.ELECTRIC_SPARK, fx, 14, 0.2, 0.2, 0.2, 0.02);
            world.playSound(fx, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.65f, 1.45f);
        }

        owner.sendActionBar(net.kyori.adventure.text.Component.text("Фантомный след сработал"));
    }
}
