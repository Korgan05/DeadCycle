package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Глобальная система diminishing returns для контроль-эффектов.
 * Снижает длительность/силу повторных замедлений и оглушений, чтобы контроль не
 * стакался бесконечно.
 */
public class ControlDiminishingManager implements Listener {

    private enum Category {
        SLOW,
        STUN
    }

    private static final class DrState {
        double stacks;
        long lastApplyAtMs;
    }

    private final DeadCyclePlugin plugin;

    private final Map<UUID, EnumMap<Category, DrState>> statesByTarget = new HashMap<>();
    private final Map<UUID, Long> bypassUntilByTarget = new HashMap<>();

    private boolean enabled;
    private boolean pluginOnly;
    private long decayWindowMs;
    private double stackGain;
    private double maxStacks;
    private double durationReductionPerStack;
    private double minDurationMultiplier;
    private int minSlowDurationTicks;
    private int minStunDurationTicks;
    private int stunAmplifierThreshold;
    private double stackDecayPerWindow;
    private double amplifierReduceStartStacks;

    public ControlDiminishingManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("skills.control_dr.enabled", true);
        this.pluginOnly = plugin.getConfig().getBoolean("skills.control_dr.plugin_only", true);
        this.decayWindowMs = Math.max(500L,
                plugin.getConfig().getLong("skills.control_dr.decay_window_ms", 6000L));
        this.stackGain = Math.max(0.1,
                plugin.getConfig().getDouble("skills.control_dr.stack_gain", 1.0));
        this.maxStacks = Math.max(1.0,
                plugin.getConfig().getDouble("skills.control_dr.max_stacks", 4.0));
        this.durationReductionPerStack = Math.max(0.01,
                plugin.getConfig().getDouble("skills.control_dr.duration_reduction_per_stack", 0.22));
        this.minDurationMultiplier = Math.max(0.05,
                Math.min(1.0, plugin.getConfig().getDouble("skills.control_dr.min_duration_multiplier", 0.25)));
        this.minSlowDurationTicks = Math.max(1,
                plugin.getConfig().getInt("skills.control_dr.min_slow_duration_ticks", 8));
        this.minStunDurationTicks = Math.max(1,
                plugin.getConfig().getInt("skills.control_dr.min_stun_duration_ticks", 5));
        this.stunAmplifierThreshold = Math.max(2,
                plugin.getConfig().getInt("skills.control_dr.stun_amplifier_threshold", 4));
        this.stackDecayPerWindow = Math.max(0.1,
                plugin.getConfig().getDouble("skills.control_dr.stack_decay_per_window", 1.0));
        this.amplifierReduceStartStacks = Math.max(1.0,
                plugin.getConfig().getDouble("skills.control_dr.amplifier_reduce_start_stacks", 2.0));

        if (!enabled) {
            statesByTarget.clear();
            bypassUntilByTarget.clear();
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPotionEffectApply(EntityPotionEffectEvent e) {
        if (!enabled)
            return;
        if (!(e.getEntity() instanceof LivingEntity target))
            return;
        if (pluginOnly && e.getCause() != EntityPotionEffectEvent.Cause.PLUGIN)
            return;
        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED
                && e.getAction() != EntityPotionEffectEvent.Action.CHANGED)
            return;

        PotionEffect incoming = e.getNewEffect();
        if (incoming == null)
            return;

        Category category = resolveCategory(incoming);
        if (category == null)
            return;

        long now = System.currentTimeMillis();
        UUID targetId = target.getUniqueId();

        long bypassUntil = bypassUntilByTarget.getOrDefault(targetId, 0L);
        if (now <= bypassUntil)
            return;

        DrState state = getState(targetId, category);
        decayState(state, now);
        state.stacks = Math.min(maxStacks, state.stacks + stackGain);
        state.lastApplyAtMs = now;

        double multiplier = Math.max(minDurationMultiplier,
                1.0 - (state.stacks * durationReductionPerStack));

        int minDuration = (category == Category.STUN) ? minStunDurationTicks : minSlowDurationTicks;
        int scaledDuration = Math.max(minDuration, (int) Math.round(incoming.getDuration() * multiplier));
        int scaledAmplifier = incoming.getAmplifier();

        if (state.stacks >= amplifierReduceStartStacks) {
            if (category == Category.SLOW) {
                scaledAmplifier = Math.max(0, scaledAmplifier - 1);
            } else {
                scaledAmplifier = Math.max(0, scaledAmplifier - 2);
            }
        }

        if (scaledDuration >= incoming.getDuration() && scaledAmplifier == incoming.getAmplifier())
            return;

        e.setCancelled(true);

        PotionEffect scaled = new PotionEffect(
                incoming.getType(),
                scaledDuration,
                scaledAmplifier,
                incoming.isAmbient(),
                incoming.hasParticles(),
                incoming.hasIcon());

        bypassUntilByTarget.put(targetId, now + 250L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            LivingEntity live = (LivingEntity) Bukkit.getEntity(targetId);
            if (live == null || !live.isValid() || live.isDead())
                return;
            live.addPotionEffect(scaled, true);
        });
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        statesByTarget.remove(id);
        bypassUntilByTarget.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        statesByTarget.remove(id);
        bypassUntilByTarget.remove(id);
    }

    private DrState getState(UUID targetId, Category category) {
        EnumMap<Category, DrState> perCategory = statesByTarget.computeIfAbsent(targetId,
                ignored -> new EnumMap<>(Category.class));
        return perCategory.computeIfAbsent(category, ignored -> new DrState());
    }

    private void decayState(DrState state, long nowMs) {
        if (state.lastApplyAtMs <= 0L)
            return;

        long elapsed = Math.max(0L, nowMs - state.lastApplyAtMs);
        if (elapsed <= 0L)
            return;

        double windows = (double) elapsed / (double) decayWindowMs;
        if (windows <= 0.0)
            return;

        state.stacks = Math.max(0.0, state.stacks - (windows * stackDecayPerWindow));
    }

    private Category resolveCategory(PotionEffect effect) {
        PotionEffectType type = effect.getType();
        if (type == null)
            return null;

        if (type == PotionEffectType.SLOWNESS) {
            return effect.getAmplifier() >= stunAmplifierThreshold ? Category.STUN : Category.SLOW;
        }
        if (type == PotionEffectType.WEAKNESS)
            return Category.SLOW;
        if (type == PotionEffectType.BLINDNESS)
            return Category.STUN;
        if (type == PotionEffectType.LEVITATION)
            return Category.STUN;

        return null;
    }
}