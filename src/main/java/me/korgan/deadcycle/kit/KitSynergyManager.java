package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class KitSynergyManager implements Listener {

    private static final class SynergyMark {
        UUID playerId;
        KitManager.Kit kit;
        String skillId;
        long atMs;
    }

    private final DeadCyclePlugin plugin;
    private final Map<UUID, List<SynergyMark>> marksByTarget = new HashMap<>();
    private final Map<UUID, Long> targetCooldownUntil = new HashMap<>();

    private boolean enabled;
    private long comboWindowMs;
    private long targetInternalCooldownMs;
    private double searchRadius;
    private double bonusDamage;
    private int exposeTicks;
    private int slowTicks;
    private int slowAmplifier;

    public KitSynergyManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("skills.synergy.enabled", true);
        this.comboWindowMs = Math.max(800L, plugin.getConfig().getLong("skills.synergy.combo_window_ms", 2600L));
        this.targetInternalCooldownMs = Math.max(800L,
                plugin.getConfig().getLong("skills.synergy.target_internal_cooldown_ms", 3200L));
        this.searchRadius = Math.max(4.0, plugin.getConfig().getDouble("skills.synergy.search_radius", 11.0));
        this.bonusDamage = Math.max(0.0, plugin.getConfig().getDouble("skills.synergy.bonus_damage", 2.5));
        this.exposeTicks = Math.max(0, plugin.getConfig().getInt("skills.synergy.expose_ticks", 30));
        this.slowTicks = Math.max(0, plugin.getConfig().getInt("skills.synergy.slow_ticks", 24));
        this.slowAmplifier = Math.max(0, plugin.getConfig().getInt("skills.synergy.slow_amplifier", 1));

        if (!enabled) {
            marksByTarget.clear();
            targetCooldownUntil.clear();
        }
    }

    public void onSkillActivated(Player actor, String skillId) {
        if (!enabled)
            return;
        if (actor == null || !actor.isOnline() || skillId == null || skillId.isBlank())
            return;

        String normalizedSkill = skillId.toLowerCase();
        if ("clone_mode".equals(normalizedSkill))
            return;

        KitManager.Kit kit = plugin.kit().getKit(actor.getUniqueId());
        if (kit == null || kit == KitManager.Kit.MINER || kit == KitManager.Kit.BUILDER)
            return;

        LivingEntity target = findSynergyTarget(actor);
        if (target == null)
            return;

        long now = System.currentTimeMillis();
        pruneExpired(now);

        UUID targetId = target.getUniqueId();
        List<SynergyMark> marks = marksByTarget.computeIfAbsent(targetId, ignored -> new ArrayList<>());

        long cooldownUntil = targetCooldownUntil.getOrDefault(targetId, 0L);
        if (now >= cooldownUntil) {
            for (int i = marks.size() - 1; i >= 0; i--) {
                SynergyMark previous = marks.get(i);
                if (previous == null)
                    continue;
                if (now - previous.atMs > comboWindowMs)
                    continue;
                if (previous.playerId.equals(actor.getUniqueId()))
                    continue;
                if (previous.kit == kit)
                    continue;

                triggerSynergy(previous, actor, normalizedSkill, target);
                targetCooldownUntil.put(targetId, now + targetInternalCooldownMs);
                marks.clear();
                break;
            }
        }

        SynergyMark mark = new SynergyMark();
        mark.playerId = actor.getUniqueId();
        mark.kit = kit;
        mark.skillId = normalizedSkill;
        mark.atMs = now;
        marks.add(mark);

        if (marks.size() > 8) {
            marks.remove(0);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        marksByTarget.remove(id);
        targetCooldownUntil.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID playerId = e.getPlayer().getUniqueId();
        for (List<SynergyMark> marks : marksByTarget.values()) {
            if (marks == null || marks.isEmpty())
                continue;
            marks.removeIf(mark -> mark != null && playerId.equals(mark.playerId));
        }
    }

    private void triggerSynergy(SynergyMark previous, Player actor, String currentSkill, LivingEntity target) {
        if (target == null || target.isDead() || !target.isValid())
            return;

        Player source = Bukkit.getPlayer(previous.playerId);
        if (bonusDamage > 0.0) {
            target.damage(bonusDamage, actor);
        }

        if (exposeTicks > 0) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, exposeTicks,
                    0, true, false, true));
        }

        if (target instanceof Mob mob) {
            if (slowTicks > 0) {
                mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks,
                        slowAmplifier, true, false, true));
            }
            mob.setTarget(null);
        }

        Location fx = target.getLocation().clone().add(0, Math.max(0.8, target.getHeight() * 0.5), 0);
        target.getWorld().spawnParticle(Particle.WITCH, fx, 12, 0.22, 0.20, 0.22, 0.02);
        target.getWorld().spawnParticle(Particle.ENCHANT, fx, 16, 0.26, 0.22, 0.26, 0.08);
        target.getWorld().playSound(fx, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.35f);

        String comboText = "Синергия: " + readableKit(previous.kit) + " + "
                + readableKit(plugin.kit().getKit(actor.getUniqueId()))
                + " (" + readableSkill(previous.skillId) + " -> " + readableSkill(currentSkill) + ")";
        actor.sendActionBar(Component.text(comboText));
        if (source != null && source.isOnline()) {
            source.sendActionBar(Component.text(comboText));
        }
    }

    private LivingEntity findSynergyTarget(Player actor) {
        if (actor == null || !actor.isOnline())
            return null;

        LivingEntity best = null;
        double bestDist = searchRadius * searchRadius;

        for (Entity entity : actor.getWorld().getNearbyEntities(actor.getLocation(), searchRadius, searchRadius,
                searchRadius)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isValidTarget(actor, living))
                continue;

            double dist = living.getLocation().distanceSquared(actor.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }

        return best;
    }

    private boolean isValidTarget(Player actor, LivingEntity target) {
        if (actor == null || target == null)
            return false;
        if (target.getUniqueId().equals(actor.getUniqueId()))
            return false;
        if (target.isDead() || !target.isValid())
            return false;

        if (isCompanion(target))
            return false;

        if (target instanceof Player other) {
            if (other.getGameMode() == org.bukkit.GameMode.SPECTATOR)
                return false;
            if (plugin.bossDuel() == null || !plugin.bossDuel().isDuelActive())
                return false;
            return plugin.bossDuel().isDuelPlayer(actor.getUniqueId())
                    && plugin.bossDuel().isDuelPlayer(other.getUniqueId());
        }

        return target instanceof Monster;
    }

    private boolean isCompanion(LivingEntity target) {
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

    private void pruneExpired(long now) {
        targetCooldownUntil.entrySet().removeIf(entry -> now >= entry.getValue());

        Iterator<Map.Entry<UUID, List<SynergyMark>>> it = marksByTarget.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<SynergyMark>> entry = it.next();
            List<SynergyMark> marks = entry.getValue();
            if (marks == null || marks.isEmpty()) {
                it.remove();
                continue;
            }

            marks.removeIf(mark -> mark == null || now - mark.atMs > comboWindowMs);
            if (marks.isEmpty()) {
                it.remove();
            }
        }
    }

    private String readableKit(KitManager.Kit kit) {
        if (kit == null)
            return "Неизвестно";
        return switch (kit) {
            case FIGHTER -> "Боец";
            case MINER -> "Шахтёр";
            case BUILDER -> "Строитель";
            case ARCHER -> "Лучник";
            case BERSERK -> "Берсерк";
            case GRAVITATOR -> "Гравитатор";
            case DUELIST -> "Ритуалист";
            case CLONER -> "Клонер";
            case SUMMONER -> "Призыватель";
            case PING -> "Пинг";
            case HARPOONER -> "Гарпунер";
            case CYBORG -> "Киборг";
            case MEDIC -> "Медик";
            case EXORCIST -> "Экзорцист";
        };
    }

    private String readableSkill(String skillId) {
        if (skillId == null || skillId.isBlank())
            return "навык";
        return switch (skillId) {
            case "archer_trap_arrow" -> "Капкан";
            case "archer_ricochet" -> "Рикошет";
            case "berserk_blood_dash" -> "Кровавый рывок";
            case "berserk_execution" -> "Казнь";
            case "duelist_feint" -> "Финт";
            case "duelist_counter_stance" -> "Контрстойка";
            case "summoner_focus" -> "Фокус";
            case "summoner_regroup" -> "Перегруппировка";
            case "summoner_sacrifice" -> "Жертвенный импульс";
            case "ping_jitter" -> "Джиттер";
            case "ping_pulse" -> "Пинг-импульс";
            case "ping_blink" -> "Пинг-рывок";
            case "harpoon_anchor" -> "Якорь";
            case "harpoon_pull" -> "Подтяжка";
            case "cyborg_slam" -> "Реактивный таран";
            case "medic_wave" -> "Полевая терапия";
            case "exorcist_purge" -> "Священный изгиб";
            case "gravity_crush" -> "Гравипресс";
            case "levitation_strike" -> "Левитация";
            default -> skillId;
        };
    }
}