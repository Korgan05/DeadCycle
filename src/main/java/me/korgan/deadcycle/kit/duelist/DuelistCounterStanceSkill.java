package me.korgan.deadcycle.kit.duelist;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.Skill;
import me.korgan.deadcycle.kit.SkillManager;
import org.bukkit.Bukkit;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DuelistCounterStanceSkill implements Skill, Listener {

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private final Map<UUID, Long> activeUntilMs = new HashMap<>();

    private int manaCost;
    private long cooldownMs;
    private long cooldownReducePerLevelMs;
    private long minCooldownMs;
    private int windowTicks;
    private double damageReduction;
    private int weaknessSeconds;
    private int weaknessAmplifier;

    public DuelistCounterStanceSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void loadConfig() {
        this.manaCost = Math.max(1, plugin.getConfig().getInt("skills.duelist.counter_stance.xp_cost", 18));
        this.cooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.duelist.counter_stance.cooldown_ms", 15000L));
        this.cooldownReducePerLevelMs = Math.max(0L,
                plugin.getConfig().getLong("skills.duelist.counter_stance.cooldown_reduce_per_level_ms", 160L));
        this.minCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("skills.duelist.counter_stance.cooldown_min_ms", 6000L));

        this.windowTicks = Math.max(2, plugin.getConfig().getInt("skills.duelist.counter_stance.window_ticks", 16));
        this.damageReduction = Math.max(0.0,
                Math.min(0.95, plugin.getConfig().getDouble("skills.duelist.counter_stance.damage_reduction", 0.70)));
        this.weaknessSeconds = Math.max(0,
                plugin.getConfig().getInt("skills.duelist.counter_stance.weakness_seconds", 2));
        this.weaknessAmplifier = Math.max(0,
                plugin.getConfig().getInt("skills.duelist.counter_stance.weakness_amplifier", 0));
    }

    @Override
    public String getId() {
        return "duelist_counter_stance";
    }

    @Override
    public String getDisplayName() {
        return "§dКонтрстойка";
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

        long until = System.currentTimeMillis() + Math.max(1, windowTicks) * 50L;
        activeUntilMs.put(p.getUniqueId(), until);

        Location fx = p.getLocation().clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.ENCHANT, fx, 18, 0.3, 0.45, 0.3, 0.04);
        p.getWorld().spawnParticle(Particle.WITCH, fx, 8, 0.2, 0.2, 0.2, 0.01);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.2f);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Контрстойка активна"));

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(p, getId());
        }

        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + getCooldownMs(p));
    }

    @EventHandler
    public void onDamaged(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player player))
            return;

        UUID playerId = player.getUniqueId();
        long until = activeUntilMs.getOrDefault(playerId, 0L);
        if (until <= 0L)
            return;

        long now = System.currentTimeMillis();
        if (now > until) {
            activeUntilMs.remove(playerId);
            return;
        }

        if (plugin.kit().getKit(playerId) != KitManager.Kit.DUELIST) {
            activeUntilMs.remove(playerId);
            return;
        }

        activeUntilMs.remove(playerId);

        double remainMult = Math.max(0.05, 1.0 - damageReduction);
        e.setDamage(e.getDamage() * remainMult);

        LivingEntity attacker = resolveAttacker(e.getDamager());
        if (isCounterTarget(player, attacker) && attacker != null) {
            if (weaknessSeconds > 0) {
                attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, weaknessSeconds * 20,
                        weaknessAmplifier, true, false, true));
            }

            Location fx = attacker.getLocation().clone().add(0, Math.max(0.7, attacker.getHeight() * 0.5), 0);
            attacker.getWorld().spawnParticle(Particle.CRIT, fx, 12, 0.25, 0.2, 0.25, 0.04);
            attacker.getWorld().playSound(fx, Sound.ITEM_SHIELD_BLOCK, 0.75f, 1.55f);
        }

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.45f);
        player.sendActionBar(net.kyori.adventure.text.Component.text("Контратака"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        activeUntilMs.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public void reset() {
        loadConfig();
    }

    private LivingEntity resolveAttacker(Entity damager) {
        if (damager instanceof LivingEntity living)
            return living;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living)
            return living;
        return null;
    }

    private boolean isCounterTarget(Player owner, LivingEntity target) {
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
