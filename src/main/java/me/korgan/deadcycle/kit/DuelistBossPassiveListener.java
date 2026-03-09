package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DuelistBossPassiveListener implements Listener {

    private final DeadCyclePlugin plugin;
    private final Map<UUID, Long> nextBossHitXpAt = new HashMap<>();

    public DuelistBossPassiveListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDuelistDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p))
            return;
        if (!(e.getEntity() instanceof LivingEntity target))
            return;
        if (!isCombatTarget(target, p))
            return;
        if (!isDuelist(p))
            return;

        if (!plugin.getConfig().getBoolean("kit_buffs.duelist.enabled", true))
            return;

        int level = plugin.progress().getDuelistLevel(p.getUniqueId());
        int threats = countThreatsAround(p,
                plugin.getConfig().getDouble("kit_buffs.duelist.crowd_radius", 8.5));

        double bonusBase = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_damage_bonus_base", 0.10);
        double bonusPer = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_damage_bonus_per_level", 0.018);
        double bonusCap = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_damage_bonus_cap", 0.38);

        double crowdPenaltyPerEnemy = plugin.getConfig().getDouble("kit_buffs.duelist.crowd_damage_penalty_per_enemy",
                0.08);
        double crowdPenaltyCap = plugin.getConfig().getDouble("kit_buffs.duelist.crowd_damage_penalty_cap", 0.35);

        double duelBonus = (threats <= 1)
                ? Math.min(bonusCap, bonusBase + (Math.max(0, level - 1) * bonusPer))
                : 0.0;
        double crowdPenalty = Math.min(crowdPenaltyCap, Math.max(0, threats - 1) * crowdPenaltyPerEnemy);

        double mult = Math.max(0.55, 1.0 + duelBonus - crowdPenalty);
        e.setDamage(Math.max(0.0, e.getDamage() * mult));

        if (threats <= 1) {
            double lifestealBase = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_lifesteal_base", 0.04);
            double lifestealPer = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_lifesteal_per_level",
                    0.003);
            double lifestealCap = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_lifesteal_cap", 0.10);

            double stealPct = Math.min(lifestealCap, lifestealBase + (Math.max(0, level - 1) * lifestealPer));
            double heal = Math.max(0.0, e.getFinalDamage() * stealPct);
            if (heal > 0.0) {
                double max = p.getAttribute(Attribute.MAX_HEALTH) != null
                        ? p.getAttribute(Attribute.MAX_HEALTH).getValue()
                        : 20.0;
                p.setHealth(Math.min(max, p.getHealth() + heal));
            }
        }

        int expPerHit = plugin.getConfig().getInt("kit_xp.duelist.exp_per_hit", 1);
        if (expPerHit > 0) {
            long now = System.currentTimeMillis();
            long cd = Math.max(200L, plugin.getConfig().getLong("kit_buffs.duelist.hit_xp_cooldown_ms", 1000L));
            long next = nextBossHitXpAt.getOrDefault(p.getUniqueId(), 0L);

            if (now >= next) {
                nextBossHitXpAt.put(p.getUniqueId(), now + cd);
                plugin.progress().addDuelistExp(p, expPerHit);
            }
        }
    }

    @EventHandler
    public void onDamageDuelist(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p))
            return;
        if (!(e.getDamager() instanceof LivingEntity source))
            return;
        if (!isCombatTarget(source, p))
            return;
        if (!isDuelist(p))
            return;

        if (!plugin.getConfig().getBoolean("kit_buffs.duelist.enabled", true))
            return;

        int level = plugin.progress().getDuelistLevel(p.getUniqueId());
        int threats = countThreatsAround(p,
                plugin.getConfig().getDouble("kit_buffs.duelist.crowd_radius", 8.5));

        double redBase = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_reduction_base", 0.07);
        double redPer = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_reduction_per_level", 0.014);
        double redCap = plugin.getConfig().getDouble("kit_buffs.duelist.one_vs_one_reduction_cap", 0.32);
        double crowdDecay = plugin.getConfig().getDouble("kit_buffs.duelist.crowd_reduction_decay_per_enemy", 0.20);

        double reduction = Math.min(redCap, Math.max(0.0, redBase + (Math.max(0, level - 1) * redPer)));
        if (threats > 1) {
            reduction *= Math.max(0.25, 1.0 - ((threats - 1) * crowdDecay));
        }

        e.setDamage(Math.max(0.0, e.getDamage() * (1.0 - reduction)));

        if (threats <= 1 && level >= 8) {
            double reflect = e.getFinalDamage() * 0.12;
            if (reflect > 0.0) {
                source.damage(reflect, p);
            }
        }
    }

    private boolean isDuelist(Player p) {
        if (p == null)
            return false;
        return plugin.kit().getKit(p.getUniqueId()) == KitManager.Kit.DUELIST;
    }

    private int countThreatsAround(Player p, double radius) {
        int count = 0;
        for (var entity : p.getWorld().getNearbyEntities(p.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (isCombatTarget(living, p)) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private boolean isCombatTarget(LivingEntity target, Player source) {
        if (target == null || source == null)
            return false;
        if (target.getUniqueId().equals(source.getUniqueId()))
            return false;
        if (!target.isValid() || target.isDead())
            return false;

        if (target instanceof Player other) {
            return other.getGameMode() != org.bukkit.GameMode.SPECTATOR;
        }

        if (target instanceof Monster)
            return true;

        if (target instanceof Zombie z && plugin.bossDuel() != null) {
            Byte mark = z.getPersistentDataContainer().get(plugin.bossDuel().bossMarkKey(), PersistentDataType.BYTE);
            if (mark != null && mark == (byte) 1)
                return true;
        }

        return false;
    }
}
