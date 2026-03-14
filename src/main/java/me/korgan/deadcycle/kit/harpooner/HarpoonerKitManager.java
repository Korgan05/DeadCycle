package me.korgan.deadcycle.kit.harpooner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class HarpoonerKitManager implements Listener {

    private static final class ChainState {
        UUID targetId;
        long expiresAtMs;
        long lastDashPunishAtMs;
        Location lastTargetLoc;
    }

    private final DeadCyclePlugin plugin;
    private final Map<UUID, ChainState> chainByOwner = new HashMap<>();
    private final Map<UUID, UUID> ownerByTarget = new HashMap<>();
    private final Map<UUID, Long> vulnerableUntil = new HashMap<>();
    private BukkitTask tickTask;

    private boolean enabled;
    private long chainDurationMs;
    private double maxChainDistance;
    private double dashThresholdDistance;
    private long dashPunishCooldownMs;
    private double dashPunishDamage;
    private int dashSlowSeconds;
    private double teleportPunishDamage;
    private int teleportSlowSeconds;
    private int breakWeaknessSeconds;
    private int breakVulnerabilitySeconds;
    private double breakVulnerabilityBonus;

    public HarpoonerKitManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        reload();
        startTask();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("harpooner.enabled", true);
        this.chainDurationMs = Math.max(1000L,
                plugin.getConfig().getLong("harpooner.chain_duration_seconds", 5L) * 1000L);
        this.maxChainDistance = Math.max(4.0, plugin.getConfig().getDouble("harpooner.max_chain_distance", 16.0));
        this.dashThresholdDistance = Math.max(1.5,
                plugin.getConfig().getDouble("harpooner.dash_threshold_distance", 4.8));
        this.dashPunishCooldownMs = Math.max(200L,
                plugin.getConfig().getLong("harpooner.dash_punish_cooldown_ms", 900L));
        this.dashPunishDamage = Math.max(0.0, plugin.getConfig().getDouble("harpooner.dash_punish_damage", 3.0));
        this.dashSlowSeconds = Math.max(0, plugin.getConfig().getInt("harpooner.dash_slow_seconds", 1));
        this.teleportPunishDamage = Math.max(0.0,
                plugin.getConfig().getDouble("harpooner.teleport_punish_damage", 4.0));
        this.teleportSlowSeconds = Math.max(0, plugin.getConfig().getInt("harpooner.teleport_slow_seconds", 2));
        this.breakWeaknessSeconds = Math.max(0, plugin.getConfig().getInt("harpooner.break_weakness_seconds", 2));
        this.breakVulnerabilitySeconds = Math.max(0,
                plugin.getConfig().getInt("harpooner.break_vulnerability_seconds", 2));
        this.breakVulnerabilityBonus = Math.max(0.0,
                plugin.getConfig().getDouble("harpooner.break_vulnerability_bonus", 0.12));

        if (!enabled) {
            clearAllChains();
        }
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        clearAllChains();
        vulnerableUntil.clear();
    }

    public void resetAll() {
        clearAllChains();
        vulnerableUntil.clear();
    }

    public void onKitAssigned(Player owner, KitManager.Kit kit) {
        if (owner == null)
            return;
        if (kit == KitManager.Kit.HARPOONER)
            return;

        removeChain(owner.getUniqueId());
    }

    public boolean hasActiveChain(Player owner) {
        if (owner == null)
            return false;
        return getActiveChain(owner.getUniqueId()) != null;
    }

    public String getAnchorError(Player owner, double range) {
        if (!enabled)
            return "§cКит Гарпунер временно отключён.";
        if (owner == null || !owner.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.HARPOONER)
            return "§cЭтот навык доступен только киту Гарпунер.";
        if (findAnchorTarget(owner, range) == null)
            return "§cНет подходящей цели перед тобой.";
        return null;
    }

    public String getPullError(Player owner) {
        if (!enabled)
            return "§cКит Гарпунер временно отключён.";
        if (owner == null || !owner.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.HARPOONER)
            return "§cЭтот навык доступен только киту Гарпунер.";
        if (getActiveChain(owner.getUniqueId()) == null)
            return "§cСначала заякорь цель гарпуном.";
        return null;
    }

    public boolean castAnchor(Player owner, double range, double impactDamage) {
        if (owner == null || !owner.isOnline())
            return false;

        LivingEntity target = findAnchorTarget(owner, range);
        if (target == null)
            return false;

        UUID ownerId = owner.getUniqueId();
        UUID targetId = target.getUniqueId();

        removeChain(ownerId);

        UUID oldOwner = ownerByTarget.get(targetId);
        if (oldOwner != null && !oldOwner.equals(ownerId)) {
            removeChain(oldOwner);
        }

        ChainState state = new ChainState();
        state.targetId = targetId;
        state.expiresAtMs = System.currentTimeMillis() + chainDurationMs;
        state.lastDashPunishAtMs = 0L;
        state.lastTargetLoc = target.getLocation().clone();

        chainByOwner.put(ownerId, state);
        ownerByTarget.put(targetId, ownerId);

        if (impactDamage > 0.0) {
            target.damage(impactDamage, owner);
        }

        playAnchorFx(owner, target);
        owner.sendActionBar(net.kyori.adventure.text.Component.text("Гарпун зацепил цель"));
        return true;
    }

    public boolean triggerPull(Player owner, double targetPullPower, double selfPullPower, double switchDistance) {
        if (owner == null || !owner.isOnline())
            return false;

        ChainState state = getActiveChain(owner.getUniqueId());
        if (state == null)
            return false;

        LivingEntity target = resolveTarget(state);
        if (target == null)
            return false;

        if (!target.getWorld().getUID().equals(owner.getWorld().getUID())) {
            removeChain(owner.getUniqueId());
            return false;
        }

        double dist = owner.getLocation().distance(target.getLocation());

        if (dist >= switchDistance) {
            Vector toward = target.getLocation().toVector().subtract(owner.getLocation().toVector());
            if (toward.lengthSquared() > 0.0001) {
                owner.setVelocity(
                        owner.getVelocity().multiply(0.18).add(toward.normalize().multiply(selfPullPower).setY(0.24)));
            }
            owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.9f, 1.35f);
        } else {
            Vector towardOwner = owner.getLocation().toVector().subtract(target.getLocation().toVector());
            if (towardOwner.lengthSquared() > 0.0001) {
                target.setVelocity(
                        target.getVelocity().multiply(0.22)
                                .add(towardOwner.normalize().multiply(targetPullPower).setY(0.20)));
            }
            owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.75f, 1.15f);
        }

        if (target instanceof Player targetPlayer && targetPlayer.getGameMode() == GameMode.SPECTATOR) {
            removeChain(owner.getUniqueId());
            return false;
        }

        drawChain(owner.getLocation(), target.getLocation(), Particle.CRIT);
        return true;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        removeChain(id);

        UUID owner = ownerByTarget.get(id);
        if (owner != null) {
            removeChain(owner);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        removeChain(id);

        UUID owner = ownerByTarget.get(id);
        if (owner != null) {
            removeChain(owner);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        if (!enabled)
            return;
        if (!isPunishableTeleportCause(e.getCause()))
            return;

        Player target = e.getPlayer();
        UUID targetId = target.getUniqueId();
        UUID ownerId = ownerByTarget.get(targetId);
        if (ownerId == null)
            return;

        ChainState state = getActiveChain(ownerId);
        if (state == null || !targetId.equals(state.targetId))
            return;

        long now = System.currentTimeMillis();
        if (now - state.lastDashPunishAtMs < dashPunishCooldownMs)
            return;

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline() || owner.isDead())
            return;
        if (!owner.getWorld().getUID().equals(target.getWorld().getUID()))
            return;

        applyDashPunish(owner, target, teleportPunishDamage, teleportSlowSeconds);
        state.lastDashPunishAtMs = now;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        UUID owner = ownerByTarget.get(id);
        if (owner != null) {
            removeChain(owner);
        }
        vulnerableUntil.remove(id);
    }

    @EventHandler
    public void onDamageVulnerability(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity target))
            return;

        long until = vulnerableUntil.getOrDefault(target.getUniqueId(), 0L);
        long now = System.currentTimeMillis();
        if (now >= until)
            return;

        e.setDamage(e.getDamage() * (1.0 + breakVulnerabilityBonus));
    }

    private void startTask() {
        if (tickTask != null) {
            tickTask.cancel();
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickChains, 5L, 5L);
    }

    private void tickChains() {
        if (chainByOwner.isEmpty()) {
            cleanupVulnerability();
            return;
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, ChainState>> it = chainByOwner.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, ChainState> entry = it.next();
            UUID ownerId = entry.getKey();
            ChainState state = entry.getValue();

            Player owner = Bukkit.getPlayer(ownerId);
            LivingEntity target = resolveTarget(state);

            if (!enabled || owner == null || !owner.isOnline() || owner.isDead()
                    || plugin.kit().getKit(ownerId) != KitManager.Kit.HARPOONER) {
                ownerByTarget.remove(state.targetId);
                it.remove();
                continue;
            }

            if (target == null || target.isDead() || !target.isValid()) {
                ownerByTarget.remove(state.targetId);
                it.remove();
                continue;
            }

            if (now >= state.expiresAtMs) {
                ownerByTarget.remove(state.targetId);
                it.remove();
                continue;
            }

            if (!owner.getWorld().getUID().equals(target.getWorld().getUID())) {
                ownerByTarget.remove(state.targetId);
                it.remove();
                continue;
            }

            if (target instanceof Player playerTarget && playerTarget.getGameMode() == GameMode.SPECTATOR) {
                ownerByTarget.remove(state.targetId);
                it.remove();
                continue;
            }

            double dist = owner.getLocation().distance(target.getLocation());
            if (dist > maxChainDistance) {
                applyBreakPunish(owner, target);
                ownerByTarget.remove(state.targetId);
                it.remove();
                continue;
            }

            if (state.lastTargetLoc != null && state.lastTargetLoc.getWorld() == target.getWorld()) {
                double moved = state.lastTargetLoc.distance(target.getLocation());
                if (moved >= dashThresholdDistance && now - state.lastDashPunishAtMs >= dashPunishCooldownMs) {
                    applyDashPunish(owner, target, dashPunishDamage, dashSlowSeconds);
                    state.lastDashPunishAtMs = now;
                }
            }

            state.lastTargetLoc = target.getLocation().clone();
            drawChain(owner.getLocation(), target.getLocation(), Particle.ELECTRIC_SPARK);
        }

        cleanupVulnerability();
    }

    private boolean isPunishableTeleportCause(PlayerTeleportEvent.TeleportCause cause) {
        if (cause == null)
            return false;
        return cause != PlayerTeleportEvent.TeleportCause.PLUGIN
                && cause != PlayerTeleportEvent.TeleportCause.COMMAND;
    }

    private void cleanupVulnerability() {
        if (vulnerableUntil.isEmpty())
            return;

        long now = System.currentTimeMillis();
        vulnerableUntil.entrySet().removeIf(entry -> now >= entry.getValue());
    }

    private void clearAllChains() {
        chainByOwner.clear();
        ownerByTarget.clear();
    }

    private void removeChain(UUID ownerId) {
        ChainState old = chainByOwner.remove(ownerId);
        if (old != null) {
            ownerByTarget.remove(old.targetId);
        }
    }

    private ChainState getActiveChain(UUID ownerId) {
        ChainState state = chainByOwner.get(ownerId);
        if (state == null)
            return null;

        long now = System.currentTimeMillis();
        if (now >= state.expiresAtMs) {
            removeChain(ownerId);
            return null;
        }

        LivingEntity target = resolveTarget(state);
        if (target == null || target.isDead() || !target.isValid()) {
            removeChain(ownerId);
            return null;
        }

        return state;
    }

    private LivingEntity resolveTarget(ChainState state) {
        if (state == null)
            return null;
        Entity entity = Bukkit.getEntity(state.targetId);
        if (entity instanceof LivingEntity living)
            return living;
        return null;
    }

    private LivingEntity findAnchorTarget(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : owner.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity target))
                continue;
            if (!isValidAnchorTarget(owner, target))
                continue;

            Location center = target.getLocation().clone().add(0, Math.max(0.6, target.getHeight() * 0.5), 0);
            Vector to = center.toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 0.001)
                continue;

            double dot = direction.dot(to.clone().normalize());
            if (dot < 0.22)
                continue;

            if (!owner.hasLineOfSight(target))
                continue;

            double score = dist - (dot * 2.8);
            if (score < bestScore) {
                bestScore = score;
                best = target;
            }
        }

        return best;
    }

    private boolean isValidAnchorTarget(Player owner, LivingEntity target) {
        if (owner == null || target == null)
            return false;
        if (target.getUniqueId().equals(owner.getUniqueId()))
            return false;
        if (!target.isValid() || target.isDead())
            return false;

        if (target instanceof Player other) {
            return other.getGameMode() != GameMode.SPECTATOR;
        }

        if (target instanceof Monster) {
            return true;
        }

        if (isBoss(target)) {
            return true;
        }

        return isPlayerOwnedCompanion(target);
    }

    private boolean isBoss(LivingEntity target) {
        if (!(target instanceof Zombie z))
            return false;
        if (plugin.bossDuel() == null)
            return false;

        NamespacedKey bossKey = plugin.bossDuel().bossMarkKey();
        Byte bossMark = z.getPersistentDataContainer().get(bossKey, PersistentDataType.BYTE);
        return bossMark != null && bossMark == (byte) 1;
    }

    private boolean isPlayerOwnedCompanion(LivingEntity entity) {
        if (entity == null)
            return false;

        if (plugin.cloneKit() != null) {
            Byte cloneMark = entity.getPersistentDataContainer().get(plugin.cloneKit().cloneMarkKey(),
                    PersistentDataType.BYTE);
            if (cloneMark != null && cloneMark == (byte) 1)
                return true;
        }

        if (plugin.summonerKit() != null) {
            Byte summonMark = entity.getPersistentDataContainer().get(plugin.summonerKit().summonMarkKey(),
                    PersistentDataType.BYTE);
            if (summonMark != null && summonMark == (byte) 1)
                return true;
        }

        return false;
    }

    private void applyDashPunish(Player owner, LivingEntity target, double damage, int slowSeconds) {
        if (damage > 0.0) {
            target.damage(damage, owner);
        }

        if (slowSeconds > 0) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowSeconds * 20, 1, true, false, true));
        }

        World world = owner.getWorld();
        Location fx = target.getLocation().clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.CRIT, fx, 10, 0.25, 0.3, 0.25, 0.05);
        world.playSound(fx, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.65f, 1.35f);
    }

    private void applyBreakPunish(Player owner, LivingEntity target) {
        if (breakWeaknessSeconds > 0) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                    breakWeaknessSeconds * 20, 0, true, false, true));
        }
        if (breakVulnerabilitySeconds > 0) {
            vulnerableUntil.put(target.getUniqueId(),
                    System.currentTimeMillis() + breakVulnerabilitySeconds * 1000L);
        }

        World world = owner.getWorld();
        Location fx = target.getLocation().clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.SONIC_BOOM, fx, 1, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticle(Particle.SMOKE, fx, 20, 0.35, 0.25, 0.35, 0.01);
        world.playSound(fx, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.45f, 1.55f);
    }

    private void playAnchorFx(Player owner, LivingEntity target) {
        World world = owner.getWorld();
        drawChain(owner.getEyeLocation(), target.getLocation().clone().add(0, 1.0, 0), Particle.CRIT);
        world.spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().clone().add(0, 1.0, 0), 4, 0.2, 0.2, 0.2,
                0.0);
        world.playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 0.8f, 0.9f);
        world.playSound(target.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 0.85f);
    }

    private void drawChain(Location from, Location to, Particle particle) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null)
            return;
        if (!from.getWorld().getUID().equals(to.getWorld().getUID()))
            return;

        Vector delta = to.toVector().subtract(from.toVector());
        int points = 10;
        Vector step = delta.multiply(1.0 / points);
        Location current = from.clone();

        for (int i = 0; i <= points; i++) {
            from.getWorld().spawnParticle(particle, current, 1, 0.0, 0.0, 0.0, 0.0);
            current.add(step);
        }
    }
}
