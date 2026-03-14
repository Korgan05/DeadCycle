package me.korgan.deadcycle.kit.fighter;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Пассивка бойца: 3 крит-удара подряд заряжают усиленный 4-й удар.
 */
public class FighterComboManager implements Listener {

    private final DeadCyclePlugin plugin;

    private final Map<UUID, Integer> critChainByPlayer = new HashMap<>();
    private final Map<UUID, Long> comboWindowUntilByPlayer = new HashMap<>();
    private final Map<UUID, Boolean> finisherReadyByPlayer = new HashMap<>();

    private boolean enabled;
    private int unlockLevel;
    private int requiredCriticalHits;
    private long comboWindowMs;
    private double finisherDamageMultiplierBase;
    private double finisherDamageMultiplierPerLevel;
    private double finisherDamageMultiplierCap;

    public FighterComboManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("skills.fighter.combo.enabled", true);
        this.unlockLevel = Math.max(1, plugin.getConfig().getInt("skills.fighter.combo.unlock_level", 3));
        this.requiredCriticalHits = Math.max(1,
                plugin.getConfig().getInt("skills.fighter.combo.required_critical_hits", 3));
        this.comboWindowMs = Math.max(500L,
                plugin.getConfig().getLong("skills.fighter.combo.combo_window_ms", 4500L));
        this.finisherDamageMultiplierBase = Math.max(1.0,
                plugin.getConfig().getDouble("skills.fighter.combo.finisher_damage_multiplier_base", 1.50));
        this.finisherDamageMultiplierPerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("skills.fighter.combo.finisher_damage_multiplier_per_level", 0.04));
        this.finisherDamageMultiplierCap = Math.max(finisherDamageMultiplierBase,
                plugin.getConfig().getDouble("skills.fighter.combo.finisher_damage_multiplier_cap", 2.10));

        if (!enabled) {
            critChainByPlayer.clear();
            comboWindowUntilByPlayer.clear();
            finisherReadyByPlayer.clear();
        }
    }

    public void shutdown() {
        critChainByPlayer.clear();
        comboWindowUntilByPlayer.clear();
        finisherReadyByPlayer.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onFighterDealDamage(EntityDamageByEntityEvent e) {
        if (!enabled)
            return;
        if (!(e.getDamager() instanceof Player player))
            return;
        if (!(e.getEntity() instanceof LivingEntity target))
            return;
        if (target.getUniqueId().equals(player.getUniqueId()))
            return;

        if (plugin.kit().getKit(player.getUniqueId()) != KitManager.Kit.FIGHTER)
            return;

        if (!isMeleeHit(e))
            return;

        int fighterLevel = plugin.progress().getFighterLevel(player.getUniqueId());
        if (fighterLevel < unlockLevel)
            return;

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        long windowUntil = comboWindowUntilByPlayer.getOrDefault(playerId, 0L);
        if (windowUntil > 0L && now > windowUntil && !finisherReadyByPlayer.getOrDefault(playerId, false)) {
            resetComboState(playerId);
        }

        if (finisherReadyByPlayer.getOrDefault(playerId, false)) {
            double multiplier = resolveFinisherMultiplier(fighterLevel);
            e.setDamage(e.getDamage() * multiplier);
            resetComboState(playerId);

            Location fx = target.getLocation().clone().add(0, Math.max(0.8, target.getHeight() * 0.5), 0);
            target.getWorld().spawnParticle(Particle.CRIT, fx, 24, 0.28, 0.22, 0.28, 0.08);
            target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, fx, 4, 0.16, 0.14, 0.16, 0.0);
            target.getWorld().playSound(fx, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.95f, 0.75f);
            player.sendActionBar(Component.text("Комбо-удар x" + formatMultiplier(multiplier)));

            if (plugin.bossDuel() != null) {
                plugin.bossDuel().registerSkillUsage(player, "fighter_combo");
            }
            return;
        }

        if (!isCriticalHit(player)) {
            resetComboState(playerId);
            return;
        }

        int next = Math.max(0, critChainByPlayer.getOrDefault(playerId, 0)) + 1;
        critChainByPlayer.put(playerId, next);
        comboWindowUntilByPlayer.put(playerId, now + comboWindowMs);

        if (next >= requiredCriticalHits) {
            finisherReadyByPlayer.put(playerId, true);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.6f);
            player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().clone().add(0, 1.0, 0),
                    18, 0.25, 0.28, 0.25, 0.02);
            player.sendActionBar(Component.text("Комбо готово: следующий удар усилен"));
        } else {
            player.sendActionBar(Component.text("Крит-комбо: " + next + "/" + requiredCriticalHits));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        resetComboState(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        resetComboState(e.getEntity().getUniqueId());
    }

    private boolean isMeleeHit(EntityDamageByEntityEvent e) {
        if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK)
            return true;
        return e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
    }

    private boolean isCriticalHit(Player player) {
        if (player == null)
            return false;
        if (player.isOnGround())
            return false;
        if (player.isSprinting())
            return false;
        if (player.isInsideVehicle())
            return false;
        if (player.getFallDistance() <= 0.0f)
            return false;
        if (player.getAttackCooldown() < 0.9f)
            return false;
        return !player.hasPotionEffect(PotionEffectType.BLINDNESS);
    }

    private double resolveFinisherMultiplier(int fighterLevel) {
        int levelOffset = Math.max(0, fighterLevel - 1);
        double mult = finisherDamageMultiplierBase + levelOffset * finisherDamageMultiplierPerLevel;
        return Math.min(finisherDamageMultiplierCap, mult);
    }

    private String formatMultiplier(double mult) {
        String text = String.format(java.util.Locale.US, "%.2f", mult);
        text = text.replaceAll("0+$", "");
        return text.replaceAll("\\.$", "");
    }

    private void resetComboState(UUID playerId) {
        if (playerId == null)
            return;
        critChainByPlayer.remove(playerId);
        comboWindowUntilByPlayer.remove(playerId);
        finisherReadyByPlayer.remove(playerId);
    }
}