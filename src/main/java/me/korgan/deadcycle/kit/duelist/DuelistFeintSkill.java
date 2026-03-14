package me.korgan.deadcycle.kit.duelist;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DuelistFeintSkill implements Skill, Listener {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private final Map<UUID, Long> empoweredUntilByOwner = new HashMap<>();

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;

    private double sideStepDistance;
    private int empowerWindowSeconds;
    private double empowerDamageMultiplier;

    public DuelistFeintSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.duelist.feint.xp_cost", 16));
        this.cooldownMs = Math.max(500L, plugin.getConfig().getLong("skills.duelist.feint.cooldown_ms", 9000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.duelist.feint.cooldown_reduce_per_level_ms", 130L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.duelist.feint.cooldown_min_ms", 3200L));

        this.sideStepDistance = Math.max(0.8,
                plugin.getConfig().getDouble("skills.duelist.feint.side_step_distance", 2.0));
        this.empowerWindowSeconds = Math.max(1,
                plugin.getConfig().getInt("skills.duelist.feint.empower_window_seconds", 5));
        this.empowerDamageMultiplier = Math.max(0.0,
                plugin.getConfig().getDouble("skills.duelist.feint.empower_damage_multiplier", 0.30));
    }

    @Override
    public String getId() {
        return "duelist_feint";
    }

    @Override
    public String getDisplayName() {
        return "§dФинт";
    }

    @Override
    public double getManaCost(Player p) {
        return manaCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getDuelistLevel(p.getUniqueId());
        long reduced = cooldownMs - Math.max(0, level - 1) * cooldownReducePerLevelMs;
        return Math.max(minCooldownMs, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.DUELIST)
            return false;
        return p.getLevel() >= manaCost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.DUELIST)
            return "§cЭтот навык доступен только киту Дуэлист.";
        if (p.getLevel() < manaCost)
            return "§cНедостаточно маны! Нужно: " + manaCost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        Location from = p.getLocation().clone();
        Location to = chooseSideStepLocation(p);
        if (to != null) {
            p.teleport(to);
            p.setFallDistance(0.0f);
        }

        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 1, true, false, true));
        empoweredUntilByOwner.put(p.getUniqueId(), System.currentTimeMillis() + empowerWindowSeconds * 1000L);

        Location fxFrom = from.clone().add(0, 1.0, 0);
        Location fxTo = p.getLocation().clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.CLOUD, fxFrom, 10, 0.2, 0.2, 0.2, 0.02);
        p.getWorld().spawnParticle(Particle.ENCHANT, fxTo, 18, 0.25, 0.3, 0.25, 0.02);
        p.getWorld().playSound(fxTo, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.75f, 1.45f);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Финт: следующий удар усилен"));

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player owner))
            return;
        if (!(e.getEntity() instanceof LivingEntity target))
            return;

        UUID ownerId = owner.getUniqueId();
        long until = empoweredUntilByOwner.getOrDefault(ownerId, 0L);
        if (until <= 0L)
            return;

        long now = System.currentTimeMillis();
        if (now >= until) {
            empoweredUntilByOwner.remove(ownerId);
            return;
        }

        if (plugin.kit().getKit(ownerId) != KitManager.Kit.DUELIST) {
            empoweredUntilByOwner.remove(ownerId);
            return;
        }

        if (!isCombatTarget(owner, target))
            return;

        empoweredUntilByOwner.remove(ownerId);
        e.setDamage(e.getDamage() * (1.0 + empowerDamageMultiplier));

        Location fx = target.getLocation().clone().add(0, Math.max(0.8, target.getHeight() * 0.5), 0);
        target.getWorld().spawnParticle(Particle.CRIT, fx, 16, 0.25, 0.25, 0.25, 0.04);
        target.getWorld().playSound(fx, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.85f, 1.15f);
        owner.sendActionBar(net.kyori.adventure.text.Component.text("Финт сработал"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        empoweredUntilByOwner.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player player) {
            empoweredUntilByOwner.remove(player.getUniqueId());
        }
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private Location chooseSideStepLocation(Player p) {
        Location origin = p.getLocation().clone();

        Vector forward = origin.getDirection().setY(0.0);
        if (forward.lengthSquared() < 0.0001) {
            forward = p.getEyeLocation().getDirection().setY(0.0);
        }
        if (forward.lengthSquared() < 0.0001) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();

        Vector right = new Vector(-forward.getZ(), 0.0, forward.getX());
        Location rightLoc = centered(origin.clone().add(right.clone().multiply(sideStepDistance)));
        Location leftLoc = centered(origin.clone().add(right.clone().multiply(-sideStepDistance)));

        boolean rightSafe = isSafeStandLocation(rightLoc);
        boolean leftSafe = isSafeStandLocation(leftLoc);

        Location chosen = null;
        if (rightSafe && leftSafe) {
            chosen = Math.random() < 0.5 ? rightLoc : leftLoc;
        } else if (rightSafe) {
            chosen = rightLoc;
        } else if (leftSafe) {
            chosen = leftLoc;
        } else {
            Location forwardLoc = centered(origin.clone().add(forward.clone().multiply(0.8)));
            if (isSafeStandLocation(forwardLoc)) {
                chosen = forwardLoc;
            }
        }

        if (chosen == null)
            return origin;

        chosen.setYaw(origin.getYaw());
        chosen.setPitch(origin.getPitch());
        return chosen;
    }

    private Location centered(Location loc) {
        if (loc == null)
            return null;
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);
        return loc;
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

    private boolean isCombatTarget(Player owner, LivingEntity target) {
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

        if (target instanceof Zombie z && plugin.bossDuel() != null) {
            Byte bossMark = z.getPersistentDataContainer().get(plugin.bossDuel().bossMarkKey(),
                    PersistentDataType.BYTE);
            if (bossMark != null && bossMark == (byte) 1)
                return true;
        }

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
