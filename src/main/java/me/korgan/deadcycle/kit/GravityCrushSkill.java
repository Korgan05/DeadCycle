package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Скилл Гравитатора: Gravity Crush
 * Усиливает гравитацию, прижимает зомби к земле и наносит урон.
 * Стоимость: здоровье игрока (HP), а не опыт.
 */
public class GravityCrushSkill implements Skill {

    private static final Color PRESS_LINE_COLOR = Color.fromRGB(140, 70, 230);
    private static final Color PRESS_IMPACT_COLOR = Color.fromRGB(95, 40, 180);
    private static final long HOLD_GRACE_MS = 2200L;
    private static final int CHANNEL_PERIOD_TICKS = 2;
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    private final DeadCyclePlugin plugin;
    private final SkillManager skillManager;
    private final Map<UUID, BukkitTask> activeChannels = new HashMap<>();
    private final Map<UUID, Long> holdUntil = new HashMap<>();

    // Конфиг параметры
    private int xpCost;
    private double hpCost;
    private long cooldownMs;
    private double radiusBase;
    private double radiusPerLevel;
    private int durationTicks;
    private double damagePerTickBase;
    private double damagePerLevel;
    private int zombieSlowAmplifier;
    private int playerSlowAmplifier;

    public GravityCrushSkill(DeadCyclePlugin plugin, SkillManager skillManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        loadConfig();
    }

    private void loadConfig() {
        this.xpCost = plugin.getConfig().getInt("skills.gravitator.crush.xp_cost", 25);
        this.hpCost = plugin.getConfig().getDouble("skills.gravitator.crush.hp_cost", 4.0);
        this.cooldownMs = plugin.getConfig().getLong("skills.gravitator.crush.cooldown_ms", 30000);
        this.radiusBase = plugin.getConfig().getDouble("skills.gravitator.crush.radius_base", 6.0);
        this.radiusPerLevel = plugin.getConfig().getDouble("skills.gravitator.crush.radius_per_level", 0.4);
        this.durationTicks = plugin.getConfig().getInt("skills.gravitator.crush.duration_ticks", 80);
        this.damagePerTickBase = plugin.getConfig().getDouble("skills.gravitator.crush.damage_per_tick_base", 2.0);
        this.damagePerLevel = plugin.getConfig().getDouble("skills.gravitator.crush.damage_per_level", 0.4);
        this.zombieSlowAmplifier = plugin.getConfig().getInt("skills.gravitator.crush.zombie_slow_amplifier", 10);
        this.playerSlowAmplifier = plugin.getConfig().getInt("skills.gravitator.crush.player_slow_amplifier", 2);
    }

    @Override
    public String getId() {
        return "gravity_crush";
    }

    @Override
    public String getDisplayName() {
        return "§5Гравитационный пресс";
    }

    @Override
    public double getManaCost(Player p) {
        return xpCost;
    }

    @Override
    public long getCooldownMs(Player p) {
        return cooldownMs;
    }

    @Override
    public boolean canUse(Player p) {
        if (p == null || !p.isOnline())
            return false;

        if (activeChannels.containsKey(p.getUniqueId()))
            return true;

        // Можно использовать если есть опыт ИЛИ есть достаточно HP
        int manaCost = (int) getManaCost(p);
        boolean hasXp = p.getLevel() >= manaCost;
        boolean hasHp = p.getHealth() > hpCost;
        return hasXp || hasHp;
    }

    @Override
    public String getErrorMessage(Player p) {
        if (p == null || !p.isOnline())
            return "§cОшибка: игрок не в сети";

        if (activeChannels.containsKey(p.getUniqueId()))
            return null;

        int manaCost = (int) getManaCost(p);
        boolean hasXp = p.getLevel() >= manaCost;
        boolean hasHp = p.getHealth() > hpCost;
        if (!hasXp && !hasHp)
            return "§cНедостаточно опыта или HP!";
        return null;
    }

    @Override
    public void activate(Player p) {
        if (p == null || !p.isOnline())
            return;

        UUID uuid = p.getUniqueId();
        holdUntil.put(uuid, System.currentTimeMillis() + HOLD_GRACE_MS);

        BukkitTask active = activeChannels.get(uuid);
        if (active != null && !active.isCancelled()) {
            return;
        }

        startChannel(p);
    }

    private void startChannel(Player player) {
        UUID uuid = player.getUniqueId();

        // Notifies boss that this skill is being used (for adaptation/counters)
        if (plugin.bossDuel() != null) {
            plugin.bossDuel().registerSkillUsage(player, "gravity_crush");
        }

        int costIntervalTicks = Math.max(20, durationTicks);

        BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
            final Map<UUID, Boolean> aiState = new HashMap<>();
            int ticksUntilCost = 0;
            int ticksUntilDamage = 0;
            int animationTick = 0;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline() || p.isDead()) {
                    stopChannel(uuid, null, aiState, false);
                    cancel();
                    return;
                }

                if (!isHoldingSkill(p)) {
                    stopChannel(uuid, p, aiState, true);
                    cancel();
                    return;
                }

                animationTick++;

                if (ticksUntilCost <= 0) {
                    if (!consumeCycleCost(p)) {
                        p.sendMessage("§cГравитационный пресс выключен: закончились XP и HP.");
                        stopChannel(uuid, p, aiState, true);
                        cancel();
                        return;
                    }
                    ticksUntilCost = costIntervalTicks;
                }

                boolean applyDamage = false;
                if (ticksUntilDamage <= 0) {
                    applyDamage = true;
                    ticksUntilDamage = 20;
                }

                ticksUntilCost -= CHANNEL_PERIOD_TICKS;
                ticksUntilDamage -= CHANNEL_PERIOD_TICKS;

                World world = p.getWorld();
                Location center = p.getLocation();

                int level = plugin.progress().getGravitatorLevel(uuid);
                double radius = radiusBase + (radiusPerLevel * level);
                double damagePerTick = damagePerTickBase + (damagePerLevel * level);

                p.setVelocity(new Vector(0, -1, 0));
                p.addPotionEffect(
                        new PotionEffect(PotionEffectType.SLOWNESS, 40, playerSlowAmplifier, true, false, true));

                if (animationTick % 4 == 0) {
                    spawnRadiusRing(world, center, radius);
                }
                spawnPressLinesInZone(world, center, radius, level, animationTick);

                NamespacedKey bossKey = (plugin.bossDuel() != null) ? plugin.bossDuel().bossMarkKey() : null;
                for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
                    if (!(e instanceof Zombie z))
                        continue;

                    boolean isBoss = bossKey != null
                            && z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE);

                    if (isBoss) {
                        if (applyDamage) {
                            z.damage(damagePerTick, p);
                        }
                        continue;
                    }

                    if (!aiState.containsKey(z.getUniqueId())) {
                        aiState.put(z.getUniqueId(), z.hasAI());
                        z.setAI(false);
                    }

                    if (applyDamage) {
                        z.damage(damagePerTick, p);
                    }
                    z.setVelocity(new Vector(0, -1, 0));
                    z.addPotionEffect(
                            new PotionEffect(PotionEffectType.SLOWNESS, 40, zombieSlowAmplifier, true, false, true));
                    z.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 40, 1, true, false, true));
                }

                if (animationTick % 5 == 0) {
                    world.playSound(center, Sound.ENTITY_MINECART_RIDING, 0.7f, 0.7f);
                }
            }
        }.runTaskTimer(plugin, 0L, CHANNEL_PERIOD_TICKS);

        activeChannels.put(uuid, task);
    }

    private boolean consumeCycleCost(Player p) {
        int manaCost = (int) getManaCost(p);
        if (plugin.mana().hasXp(p, manaCost)) {
            return plugin.mana().consumeXp(p, manaCost);
        }

        if (p.getHealth() > hpCost) {
            p.damage(hpCost);
            return true;
        }

        return false;
    }

    private boolean isHoldingSkill(Player p) {
        if (p == null || !p.isOnline())
            return false;

        UUID uuid = p.getUniqueId();
        long until = holdUntil.getOrDefault(uuid, 0L);
        boolean holdingWindow = System.currentTimeMillis() <= until || p.isHandRaised();

        if (!holdingWindow)
            return false;

        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();

        return "gravity_crush".equals(plugin.kit().getSkillIdFromItem(main))
                || "gravity_crush".equals(plugin.kit().getSkillIdFromItem(off));
    }

    private void stopChannel(UUID uuid, Player player, Map<UUID, Boolean> aiState, boolean applyCooldown) {
        BukkitTask running = activeChannels.remove(uuid);
        if (running != null && !running.isCancelled()) {
            running.cancel();
        }

        holdUntil.remove(uuid);
        restoreAi(aiState);

        if (applyCooldown) {
            skillManager.setCooldown(uuid, getId(), System.currentTimeMillis() + cooldownMs);
            if (player != null && player.isOnline()) {
                plugin.progress().addGravitatorExp(player, 1);
            }
        }
    }

    private void restoreAi(Map<UUID, Boolean> aiState) {
        for (Map.Entry<UUID, Boolean> entry : aiState.entrySet()) {
            Entity ent = Bukkit.getEntity(entry.getKey());
            if (!(ent instanceof Zombie z))
                continue;
            if (!z.isValid() || z.isDead())
                continue;
            z.setAI(entry.getValue());
        }
        aiState.clear();
    }

    private void spawnRadiusRing(World world, Location center, double radius) {
        int points = 48;
        for (int i = 0; i < points; i++) {
            double angle = (i / (double) points) * Math.PI * 2;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location loc = center.clone().add(x, 0.2, z);
            world.spawnParticle(Particle.SMOKE, loc, 2, 0.05, 0.02, 0.05, 0.01);
            world.spawnParticle(Particle.PORTAL, loc, 2, 0.05, 0.02, 0.05, 0.01);
        }
    }

    private void spawnPressLinesInZone(World world, Location center, double radius, int level, int animationTick) {
        int normalizedLevel = Math.max(0, level);
        int lineCount = Math.min(88, 6 + normalizedLevel * 3 + (int) Math.round(radius * 1.4));
        double zoneRadius = Math.max(0.8, radius * 0.9);
        double topBase = 1.2 + (normalizedLevel * 0.05);
        double depthBase = 1.25 + (normalizedLevel * 0.16);
        double speed = Math.min(0.95, 0.30 + (normalizedLevel * 0.02));
        double phase = animationTick * speed;

        for (int i = 0; i < lineCount; i++) {
            double seed = i * 0.7548776662466927;
            double ring = Math.sqrt((i + 0.5) / lineCount);
            double distance = zoneRadius * ring;
            double angle = (i * GOLDEN_ANGLE + animationTick * 0.11) % (Math.PI * 2.0);
            double x = Math.cos(angle) * distance;
            double z = Math.sin(angle) * distance;

            double topY = topBase + ((fract(seed * 11.0) - 0.5) * 0.65);
            double depth = depthBase * (0.72 + (fract(seed * 7.0) * 0.7));
            double progress = fract(seed + phase);
            double headY = topY - (depth * progress);

            Particle.DustOptions lineDust = createPressLineDust(normalizedLevel, seed);
            for (int trail = 0; trail < 4; trail++) {
                double y = headY + (trail * 0.10);
                if (y > topY + 0.02)
                    break;

                Location point = center.clone().add(x, y, z);
                world.spawnParticle(Particle.DUST, point, 1, 0.008, 0.008, 0.008, 0.0, lineDust);
            }

            if (progress > 0.86) {
                Location impactPoint = center.clone().add(x, topY - depth, z);
                world.spawnParticle(Particle.DUST, impactPoint, 1, 0.01, 0.01, 0.01, 0.0,
                        createPressImpactDust(normalizedLevel, seed));
            }
        }
    }

    private Particle.DustOptions createPressLineDust(int level, double seed) {
        float minSize = 0.18f;
        float maxSize = (float) Math.min(0.52, 0.34 + (level * 0.012));
        float ratio = (float) fract(seed * 17.0);
        float size = minSize + ((maxSize - minSize) * ratio);
        return new Particle.DustOptions(PRESS_LINE_COLOR, size);
    }

    private Particle.DustOptions createPressImpactDust(int level, double seed) {
        float minSize = 0.22f;
        float maxSize = (float) Math.min(0.56, 0.40 + (level * 0.012));
        float ratio = (float) fract(seed * 19.0 + 0.37);
        float size = minSize + ((maxSize - minSize) * ratio);
        return new Particle.DustOptions(PRESS_IMPACT_COLOR, size);
    }

    private double fract(double value) {
        return value - Math.floor(value);
    }

    @Override
    public void reset() {
        for (BukkitTask task : new ArrayList<>(activeChannels.values())) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        activeChannels.clear();
        holdUntil.clear();
        loadConfig();
    }
}
