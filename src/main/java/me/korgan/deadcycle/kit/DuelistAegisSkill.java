package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class DuelistAegisSkill implements Skill {

    private static final Color TRANCE_COLOR = Color.fromRGB(142, 92, 255);

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;

    private int xpCost;
    private long cooldownMs;
    private int durationSecondsBase;
    private int durationSecondsPerLevel;
    private double crowdRadius;
    private int resistanceAmplifierBase;
    private int resistanceAmplifierLevel7;
    private int speedAmplifierBase;
    private int regenAmplifierBase;
    private int markWeaknessSeconds;
    private int markWeaknessAmplifier;
    private double crowdDurationMultiplier;
    private double crowdResistDecay;

    public DuelistAegisSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.xpCost = readInt("skills.duelist.circle_trance.xp_cost", "skills.duelist.aegis.xp_cost", 14);
        this.cooldownMs = readLong("skills.duelist.circle_trance.cooldown_ms", "skills.duelist.aegis.cooldown_ms",
                18000L);
        this.durationSecondsBase = readInt("skills.duelist.circle_trance.duration_seconds_base",
                "skills.duelist.aegis.duration_seconds", 5);
        this.durationSecondsPerLevel = readInt("skills.duelist.circle_trance.duration_seconds_per_level",
                "skills.duelist.aegis.duration_seconds_per_level", 1);
        this.crowdRadius = readDouble("skills.duelist.circle_trance.crowd_radius", "skills.duelist.aegis.taunt_range",
                9.0);

        this.resistanceAmplifierBase = readInt("skills.duelist.circle_trance.resistance_amplifier_base",
                "skills.duelist.aegis.resistance_amplifier_base", 0);
        this.resistanceAmplifierLevel7 = readInt("skills.duelist.circle_trance.resistance_amplifier_level7",
                "skills.duelist.aegis.resistance_amplifier_level8", 1);
        this.speedAmplifierBase = readInt("skills.duelist.circle_trance.speed_amplifier_base",
                "skills.duelist.aegis.speed_amplifier", 0);
        this.regenAmplifierBase = readInt("skills.duelist.circle_trance.regen_amplifier_base",
                "skills.duelist.aegis.regen_amplifier", 0);
        this.markWeaknessSeconds = readInt("skills.duelist.circle_trance.mark_weakness_seconds",
                "skills.duelist.aegis.mark_weakness_seconds", 4);
        this.markWeaknessAmplifier = readInt("skills.duelist.circle_trance.mark_weakness_amplifier",
                "skills.duelist.aegis.mark_weakness_amplifier", 0);

        this.crowdDurationMultiplier = readDouble("skills.duelist.circle_trance.crowd_duration_multiplier",
                "skills.duelist.aegis.crowd_duration_multiplier", 0.72);
        this.crowdResistDecay = readDouble("skills.duelist.circle_trance.crowd_resist_decay_per_enemy",
                "skills.duelist.aegis.crowd_resist_decay_per_enemy", 0.25);
    }

    @Override
    public String getId() {
        return "circle_trance";
    }

    @Override
    public String getDisplayName() {
        return "§dТранс Круга";
    }

    @Override
    public double getManaCost(Player p) {
        return xpCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        int level = plugin.progress().getDuelistLevel(p.getUniqueId());
        long reduced = cooldownMs - (level * 300L);
        return Math.max(6500L, reduced);
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;

        if (countThreatsAround(p, crowdRadius) <= 0)
            return false;

        int cost = (int) getManaCost(p);
        return p.getLevel() >= cost;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cОшибка: игрок не в сети";
        if (countThreatsAround(p, crowdRadius) <= 0)
            return "§cРядом нет противников для входа в транс.";

        int cost = (int) getManaCost(p);
        if (p.getLevel() < cost)
            return "§cНедостаточно маны! Нужно: " + cost + ", есть: " + p.getLevel();
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        int level = plugin.progress().getDuelistLevel(p.getUniqueId());
        int threats = countThreatsAround(p, crowdRadius);
        if (threats <= 0)
            return;

        int durationSeconds = durationSecondsBase + Math.max(0, level - 1) / Math.max(1, durationSecondsPerLevel);
        if (threats >= 3) {
            durationSeconds = Math.max(2, (int) Math.round(durationSeconds * crowdDurationMultiplier));
        }
        int ticks = Math.max(20, durationSeconds * 20);

        int resistanceAmp = resistanceAmplifierBase;
        if (level >= 7) {
            resistanceAmp = Math.max(resistanceAmp, resistanceAmplifierLevel7);
        }

        if (threats >= 2) {
            double decay = Math.max(0.0, 1.0 - ((threats - 1) * crowdResistDecay));
            resistanceAmp = (decay <= 0.45) ? Math.max(0, resistanceAmp - 1) : resistanceAmp;
        }

        int speedAmp = Math.max(0, speedAmplifierBase + (level >= 6 ? 1 : 0));
        int regenAmp = Math.max(0, regenAmplifierBase + (level >= 9 ? 1 : 0));

        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, Math.max(0, resistanceAmp), true,
                false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, speedAmp, true,
                false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks, regenAmp, true,
                false, true));

        LivingEntity focus = findNearestThreat(p, crowdRadius);
        if (focus != null) {
            if (isBossEntity(focus) && plugin.bossDuel() != null && plugin.bossDuel().isDuelPlayer(p.getUniqueId())) {
                plugin.bossDuel().registerSkillUsage(p, "circle_trance");
            }

            if (focus instanceof Zombie boss) {
                boss.setTarget(p);
            }

            if (threats <= 1) {
                focus.addPotionEffect(
                        new PotionEffect(PotionEffectType.WEAKNESS, Math.max(20, markWeaknessSeconds * 20),
                                Math.max(0, markWeaknessAmplifier), true, false, true));
                focus.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Math.max(20, 40 + (level * 6)), 0,
                        true, false, true));
            }

            Vector back = focus.getLocation().toVector().subtract(p.getLocation().toVector());
            if (back.lengthSquared() < 0.0001) {
                back = p.getLocation().getDirection().clone();
            }
            back.normalize().multiply(threats <= 1 ? 0.60 : 0.40).setY(0.2);
            focus.setVelocity(focus.getVelocity().multiply(0.3).add(back));

            if (threats <= 1 && level >= 5) {
                double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                        ? p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                        : 20.0;
                p.setHealth(Math.min(max, p.getHealth() + 1.0 + (level * 0.08)));
            }
        }

        World world = p.getWorld();
        Location center = p.getLocation().clone().add(0, 1.0, 0);
        Particle.DustOptions dust = new Particle.DustOptions(TRANCE_COLOR, 1.2f);
        world.spawnParticle(Particle.DUST, center, 26, 0.35, 0.45, 0.35, 0.0, dust);
        world.spawnParticle(Particle.WITCH, center, 22, 0.3, 0.45, 0.3, 0.03);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 8, 0.16, 0.28, 0.16, 0.03);
        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.85f, 1.35f);
        world.playSound(center, Sound.ITEM_TOTEM_USE, 0.55f, 1.45f);

        long cooldown = getCooldownMs(p);
        if (threats >= 3) {
            cooldown += 1200L;
        }
        skillManager.setCooldown(p.getUniqueId(), getId(), System.currentTimeMillis() + cooldown);

        int exp = (threats <= 1) ? 2 : 1;
        plugin.progress().addDuelistExp(p, exp);
    }

    private LivingEntity findNearestThreat(Player p, double maxRange) {
        LivingEntity best = null;
        double bestDist = maxRange * maxRange;

        for (Entity entity : p.getWorld().getNearbyEntities(p.getLocation(), maxRange, maxRange, maxRange)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isCombatTarget(living, p))
                continue;

            double dist = living.getLocation().distanceSquared(p.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }

        return best;
    }

    private int countThreatsAround(Player p, double radius) {
        int count = 0;
        for (Entity entity : p.getWorld().getNearbyEntities(p.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living))
                continue;

            if (isCombatTarget(living, p)) {
                count++;
            }
        }

        return count;
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

        return isBossEntity(target);
    }

    private boolean isBossEntity(LivingEntity target) {
        if (!(target instanceof Zombie z))
            return false;
        if (plugin.bossDuel() == null)
            return false;

        PersistentDataContainer pdc = z.getPersistentDataContainer();
        Byte mark = pdc.get(plugin.bossDuel().bossMarkKey(), PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private int readInt(String primary, String fallback, int def) {
        if (plugin.getConfig().contains(primary)) {
            return plugin.getConfig().getInt(primary, def);
        }
        return plugin.getConfig().getInt(fallback, def);
    }

    private long readLong(String primary, String fallback, long def) {
        if (plugin.getConfig().contains(primary)) {
            return plugin.getConfig().getLong(primary, def);
        }
        return plugin.getConfig().getLong(fallback, def);
    }

    private double readDouble(String primary, String fallback, double def) {
        if (plugin.getConfig().contains(primary)) {
            return plugin.getConfig().getDouble(primary, def);
        }
        return plugin.getConfig().getDouble(fallback, def);
    }

    @Override
    public void reset() {
        loadConfig();
    }
}
