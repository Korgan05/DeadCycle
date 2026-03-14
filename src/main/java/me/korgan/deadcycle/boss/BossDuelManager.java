package me.korgan.deadcycle.boss;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.kit.gravitator.GravityCrushSkill;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

import java.util.*;

public class BossDuelManager implements Listener {

    public enum Stage {
        NONE, INIT, FREEZE, FIGHT, END
    }

    private final DeadCyclePlugin plugin;
    private final Random rng = new Random();
    private final BossAdaptationEngine adaptationEngine;

    private final NamespacedKey bossKey;
    private final NamespacedKey minionKey;

    private boolean bossSpawning = false;

    private boolean active = false;
    private Stage stage = Stage.NONE;
    private int duelDay = -1;

    private Location duelCenter;
    private UUID duelPlayerUuid;
    private UUID bossUuid;

    private Location lastInsideLocation;

    private BukkitTask freezeTask;
    private BukkitTask lookTask;
    private BukkitTask barrierTask;
    private BukkitTask targetTask;
    private BukkitTask mobilityTask;
    private BukkitTask bossClampTask;
    private BukkitTask zombieCleanerTask;
    private BukkitTask phaseTask;
    private BukkitTask regenTask;
    private BukkitTask bossBarTask;
    private BukkitTask helpItemTask;
    private BukkitTask unarmedComboDashTask;

    private boolean helpItemGiven = false;

    private boolean internalTeleport = false;

    private final Map<UUID, Long> warnCooldown = new HashMap<>();

    private int recentHitCount = 0;
    private long lastHitAt = 0L;

    private boolean weaponDrawn = false;
    private boolean interestMode = false;
    private boolean seriousMode = false;
    private boolean rageMode = false;

    private final Map<BlockVector, BlockData> arenaSnapshot = new HashMap<>();
    private World arenaWorld = null;

    // Multi-player assist mechanics
    private UUID allyPlayerUuid = null;
    private boolean allyUsed = false;
    private long lastForceSeparationTime = 0L;
    private UUID bossFocusUuid = null;
    private long bossFocusUntil = 0L;
    private final Map<UUID, Long> playerDebuffExpiry = new HashMap<>();
    private final Map<UUID, Long> antiCheatLastHitAt = new HashMap<>();
    private final Map<UUID, Integer> antiCheatFastHitScore = new HashMap<>();
    private final Map<UUID, MovementPatternState> antiCheatMovement = new HashMap<>();
    private final Map<UUID, Long> antiCheatPunishCooldown = new HashMap<>();
    private final Map<UUID, Long> adaptationCounterSpeakCooldown = new HashMap<>();
    private BukkitTask assistCheckTask;

    private static class MovementPatternState {
        long lastMoveAt;
        Vector lastDirection;
        int zigZagScore;
    }

    private static final String BOSS_NAME = "§5§l[?????]";
    private static final String BOSS_PREFIX = "§5§l[?????] §d";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final long FAST_HIT_MIN_INTERVAL_MS = 125L;
    private static final long FAST_HIT_MID_INTERVAL_MS = 180L;
    private static final int FAST_HIT_TRIGGER_SCORE = 8;
    private static final int ZIGZAG_TRIGGER_SCORE = 8;
    private static final long ANTI_CHEAT_PUNISH_COOLDOWN_MS = 2800L;

    private static final int DUEL_RADIUS = 50;
    private static final int MIN_DISTANCE_FROM_BASE = 120;
    private static final int MAX_DISTANCE_FROM_BASE = 220;

    private static final int BOSS_HP = 520;
    private static final double BOSS_DAMAGE = 6.0;
    private static final double BOSS_SPEED = 0.25;
    private static final double BOSS_SPEED_INTEREST = 0.30;
    private static final double BOSS_SPEED_SERIOUS = 0.36;
    private static final double BOSS_SPEED_RAGE = 0.45;
    private static final double BOSS_DAMAGE_INTEREST = 7.5;
    private static final double BOSS_DAMAGE_SERIOUS = 10.0;
    private static final double BOSS_DAMAGE_RAGE = 14.0;
    private static final double BOSS_FOLLOW_RANGE = 64.0;

    private static final int UNARMED_COMBO_FLURRY_HITS = 15;
    private static final long UNARMED_COMBO_FIRST_DELAY_MS = 12_000L;
    private static final long UNARMED_COMBO_FAIL_COOLDOWN_MS = 12_000L;
    private static final long UNARMED_COMBO_COOLDOWN_MIN_MS = 22_000L;
    private static final long UNARMED_COMBO_COOLDOWN_RANDOM_MS = 10_000L;

    private int dashCooldownTicks;
    private int dashWindupTicks;
    private double dashSpeed;
    private double dodgeSlideMin;
    private double dodgeSlideMax;
    private double backstepSlideMin;
    private double backstepSlideMax;

    private long nextDashAtMillis = 0L;
    private boolean dashPreparing = false;
    private boolean unarmedComboActive = false;
    private long nextUnarmedComboAtMillis = 0L;

    public BossDuelManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.adaptationEngine = new BossAdaptationEngine(plugin, this, rng);
        this.bossKey = new NamespacedKey(plugin, "boss_duel_boss");
        this.minionKey = new NamespacedKey(plugin, "boss_duel_minion");
        loadConfig();
    }

    private void loadConfig() {
        this.dashCooldownTicks = Math.max(20, plugin.getConfig().getInt("boss.mobility.dash_cooldown_ticks", 60));
        this.dashWindupTicks = Math.max(1, plugin.getConfig().getInt("boss.mobility.dash_windup_ticks", 8));
        this.dashSpeed = Math.max(0.3, plugin.getConfig().getDouble("boss.mobility.dash_speed", 1.3));

        this.dodgeSlideMin = Math.max(0.2, plugin.getConfig().getDouble("boss.mobility.dodge_speed_min", 0.9));
        this.dodgeSlideMax = Math.max(this.dodgeSlideMin,
                plugin.getConfig().getDouble("boss.mobility.dodge_speed_max", 1.4));

        this.backstepSlideMin = Math.max(0.2, plugin.getConfig().getDouble("boss.mobility.backstep_speed_min", 0.8));
        this.backstepSlideMax = Math.max(this.backstepSlideMin,
                plugin.getConfig().getDouble("boss.mobility.backstep_speed_max", 1.2));
    }

    public void reload() {
        loadConfig();
    }

    public NamespacedKey bossMarkKey() {
        return bossKey;
    }

    public NamespacedKey minionMarkKey() {
        return minionKey;
    }

    public boolean isBossSpawning() {
        return bossSpawning;
    }

    public boolean isDuelActive() {
        return active;
    }

    public boolean isDuelPlayer(UUID uuid) {
        return (duelPlayerUuid != null && duelPlayerUuid.equals(uuid))
                || (allyPlayerUuid != null && allyPlayerUuid.equals(uuid));
    }

    public boolean hasAlly() {
        return allyPlayerUuid != null;
    }

    public void setAlly(Player ally) {
        if (ally == null)
            return;
        allyPlayerUuid = ally.getUniqueId();
        allyUsed = true;
    }

    public Location getDuelCenter() {
        return duelCenter;
    }

    public boolean isInsideDuelZone(Location loc, double extra) {
        if (!active || duelCenter == null || loc == null || loc.getWorld() == null)
            return false;
        if (!loc.getWorld().getUID().equals(duelCenter.getWorld().getUID()))
            return false;
        double r = DUEL_RADIUS + Math.max(0.0, extra);
        return loc.distanceSquared(duelCenter) <= r * r;
    }

    public void trySpawnBoss(int dayCount) {
        if (active) {
            plugin.getLogger().info("[BossDuel] Уже активна дуэль.");
            return;
        }
        if (dayCount < 10) {
            plugin.getLogger().info("[BossDuel] День " + dayCount + " < 10. Спавна не будет.");
            return;
        }
        if (duelDay == dayCount) {
            plugin.getLogger().info("[BossDuel] Уже спавнили на день " + dayCount);
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            plugin.getLogger().info("[BossDuel] Ни один игрок онлайн.");
            return;
        }

        if (plugin.base() == null || !plugin.base().isEnabled()) {
            plugin.getLogger().info("[BossDuel] База отключена или не инициализирована.");
            return;
        }
        Location baseCenter = plugin.base().getCenter();
        if (baseCenter == null) {
            plugin.getLogger().info("[BossDuel] Центр базы не задан.");
            return;
        }
        World baseWorld = baseCenter.getWorld();
        if (baseWorld == null) {
            plugin.getLogger().info("[BossDuel] Мир базы = null");
            return;
        }

        plugin.getLogger().info("[BossDuel] Ночь " + dayCount + ": поиск ритуалиста...");
        Player duelPlayer = selectDuelist(baseWorld);
        if (duelPlayer == null) {
            plugin.getLogger().info("[BossDuel] Ритуалист не найден.");
            return;
        }

        plugin.getLogger().info("[BossDuel] Ритуалист найден: " + duelPlayer.getName() + ". Поиск локации спавна...");
        Location spawn = findBossSpawn(baseWorld);
        if (spawn == null) {
            plugin.getLogger().info("[BossDuel] Локация спавна не найдена.");
            return;
        }

        plugin.getLogger().info("[BossDuel] СПАВН БОССА для " + duelPlayer.getName() + " в " + spawn);
        duelDay = dayCount;
        startDuel(duelPlayer, spawn);
    }

    public void forceEnd(String reason) {
        if (!active && stage == Stage.NONE)
            return;
        endDuel(reason, false, false);
    }

    public void clearSkillAdaptationData() {
        adaptationEngine.clearData();
    }

    private Player selectDuelist(World baseWorld) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline() || p.isDead())
                continue;
            if (p.getWorld() != baseWorld)
                continue;
            // ✅ Убрали проверку isOnBase - босс телепортирует везде
            candidates.add(p);
        }
        plugin.getLogger().info("[BossDuel] Найдено кандидатов: " + candidates.size());
        if (candidates.isEmpty())
            return null;

        candidates.sort((a, b) -> {
            int lvlA = plugin.progress().getPlayerLevel(a.getUniqueId());
            int lvlB = plugin.progress().getPlayerLevel(b.getUniqueId());
            plugin.getLogger().info("[BossDuel] Сравнение " + a.getName() + " (уровень " + lvlA + ") vs " + b.getName()
                    + " (" + lvlB + ")");
            if (lvlA != lvlB)
                return Integer.compare(lvlB, lvlA);

            long moneyA = plugin.econ().getMoney(a.getUniqueId());
            long moneyB = plugin.econ().getMoney(b.getUniqueId());
            if (moneyA != moneyB)
                return Long.compare(moneyB, moneyA);

            KitManager.Kit kitA = plugin.kit().getKit(a.getUniqueId());
            KitManager.Kit kitB = plugin.kit().getKit(b.getUniqueId());
            int kitLvlA = plugin.progress().getKitLevel(a.getUniqueId(), kitA);
            int kitLvlB = plugin.progress().getKitLevel(b.getUniqueId(), kitB);
            if (kitLvlA != kitLvlB)
                return Integer.compare(kitLvlB, kitLvlA);

            return 0;
        });

        Player selected = candidates.get(0);
        plugin.getLogger().info("[BossDuel] Выбран ритуалист: " + selected.getName());
        return selected;
    }

    private void startDuel(Player duelPlayer, Location bossSpawn) {
        active = true;
        stage = Stage.INIT;
        duelCenter = bossSpawn.clone();
        duelPlayerUuid = duelPlayer.getUniqueId();
        lastInsideLocation = duelPlayer.getLocation().clone();
        weaponDrawn = false;
        interestMode = false;
        seriousMode = false;
        rageMode = false;
        allyPlayerUuid = null;
        allyUsed = false;

        removeNearbyZombies(bossSpawn, DUEL_RADIUS + 8);

        spawnBoss(bossSpawn);
        startBossBar();

        prepareTeleportScene(duelPlayer, bossSpawn);
        startFreezePhase(duelPlayer);
        startBarrierEffects();
    }

    private void prepareTeleportScene(Player duelPlayer, Location bossSpawn) {
        double angle = rng.nextDouble() * Math.PI * 2;
        Vector offset = new Vector(Math.cos(angle), 0, Math.sin(angle)).multiply(10);

        Location playerLoc = bossSpawn.clone().add(offset);
        playerLoc.setY(bossSpawn.getY());
        playerLoc.add(0, 0.1, 0);

        Location bossLoc = bossSpawn.clone();
        bossLoc.add(0, 0.1, 0);

        internalTeleport = true;
        duelPlayer.teleport(playerLoc);
        // Не сбрасываем флаг сразу - ждем 2 тика
        Bukkit.getScheduler().runTaskLater(plugin, () -> internalTeleport = false, 2L);

        faceEachOther(duelPlayer, bossLoc);

        Zombie boss = getBoss();
        if (boss != null) {
            boss.teleport(bossLoc);
            faceEntity(boss, duelPlayer.getLocation());
        }
    }

    private void startFreezePhase(Player duelPlayer) {
        stage = Stage.FREEZE;

        // Замораживаем босса
        Zombie boss = getBoss();
        if (boss != null) {
            boss.setAI(false);
        }

        // Removed intro message

        freezeTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int seconds = 5;

            @Override
            public void run() {
                if (!active || stage != Stage.FREEZE) {
                    freezeTask.cancel();
                    return;
                }
                if (seconds <= 0) {
                    freezeTask.cancel();
                    startFightPhase();
                    return;
                }
                sendBossMessage("Начинаем через " + seconds + "...");
                seconds--;
            }
        }, 0L, 20L);

        lookTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FREEZE)
                return;
            Player p = getDuelPlayer();
            Zombie z = getBoss();
            if (p == null || z == null)
                return;
            faceEntity(p, z.getLocation());
        }, 0L, 3L);
    }

    private void startFightPhase() {
        stage = Stage.FIGHT;
        helpItemGiven = false;
        dashPreparing = false;
        nextDashAtMillis = System.currentTimeMillis() + (long) dashCooldownTicks * 50L;
        unarmedComboActive = false;
        cancelUnarmedComboDashTask();
        nextUnarmedComboAtMillis = System.currentTimeMillis() + UNARMED_COMBO_FIRST_DELAY_MS;

        if (lookTask != null) {
            lookTask.cancel();
            lookTask = null;
        }

        // Задача: через 150 секунд (половина ночи) выдать игроку свиток призыва помощи
        helpItemTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active || stage != Stage.FIGHT || helpItemGiven)
                return;

            Player p = getDuelPlayer();
            Zombie boss = getBoss();
            if (p == null || boss == null || boss.isDead())
                return;

            // Выдаем свиток призыва помощи
            BossHelpScrollListener scrollListener = plugin.getBossHelpScrollListener();
            if (scrollListener != null) {
                ItemStack scroll = scrollListener.createBossHelpScroll(1);
                p.getInventory().addItem(scroll);
                p.sendMessage("§6Этот бой затягивается...");
                p.sendMessage("§eСвиток призовёт игрока на помощь!");
                helpItemGiven = true;
            }
        }, 20L * 150L); // 150 секунд

        Player p = getDuelPlayer();
        Zombie boss = getBoss();
        if (p == null || boss == null) {
            endDuel("missing_entity", false, false);
            return;
        }

        // Размораживаем босса
        boss.setAI(true);

        teleportBossBehindPlayer(p, boss);
        boss.setTarget(p);

        startTargetScan();
        startMobility();
        startBossClamp();
        startZombieCleaner();
        startPhaseMonitor();
        startRegen();
        startAssistCheck();
        startAdaptationCheck();
    }

    private void startTargetScan() {
        targetTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            Zombie boss = getBoss();
            if (boss == null)
                return;

            LivingEntity target = pickCombatTarget(boss);
            if (target == null)
                return;

            boss.setTarget(target);
        }, 20L, 20L * 2L);
    }

    private void startMobility() {
        mobilityTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;

            Zombie boss = getBoss();
            if (boss == null || boss.isDead())
                return;

            LivingEntity target = pickCombatTarget(boss);
            if (target == null)
                return;

            // Включаем AI и даём цель — пусть сам идёт по навигации
            boss.setAI(true);
            boss.setTarget(target);

            long now = System.currentTimeMillis();
            if (tryStartUnarmedCombo(boss, target, now))
                return;

            if (dashPreparing || now < nextDashAtMillis)
                return;

            if (!boss.isOnGround())
                return;

            double dist = boss.getLocation().distance(target.getLocation());
            if (dist < 3.0 || dist > 20.0)
                return;

            dashPreparing = true;

            Location telegraph = boss.getLocation().add(0, 1.0, 0);
            boss.getWorld().spawnParticle(Particle.CRIT, telegraph, 18, 0.35, 0.3, 0.35, 0.05);
            boss.getWorld().spawnParticle(Particle.SMOKE, telegraph, 10, 0.25, 0.2, 0.25, 0.01);
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.9f, 0.8f);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                dashPreparing = false;
                if (!active || stage != Stage.FIGHT)
                    return;

                Zombie currentBoss = getBoss();
                LivingEntity dashTarget = (currentBoss != null) ? pickCombatTarget(currentBoss) : null;
                if (currentBoss == null || dashTarget == null || currentBoss.isDead())
                    return;

                Vector dir = dashTarget.getLocation().toVector().subtract(currentBoss.getLocation().toVector()).setY(0);
                if (dir.lengthSquared() < 0.0001)
                    return;
                dir.normalize();

                Vector slide = dir.multiply(dashSpeed).setY(0.0);
                if (duelCenter != null
                        && currentBoss.getLocation().distanceSquared(duelCenter) > (DUEL_RADIUS - 3)
                                * (DUEL_RADIUS - 3)) {
                    slide = slide.multiply(0.6);
                }

                currentBoss.setVelocity(slide);
                Location fx = currentBoss.getLocation().add(0, 0.2, 0);
                currentBoss.getWorld().spawnParticle(Particle.CLOUD, fx, 16, 0.35, 0.08, 0.35, 0.02);
                currentBoss.getWorld().playSound(currentBoss.getLocation(), Sound.ENTITY_ZOMBIE_HURT, 0.7f, 0.6f);

                nextDashAtMillis = System.currentTimeMillis() + (long) dashCooldownTicks * 50L;
            }, dashWindupTicks);

        }, 0L, 4L); // Плавное движение + контролируемый рывок с телеграфом
    }

    private void startBossClamp() {
        bossClampTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || duelCenter == null)
                return;
            Zombie boss = getBoss();
            if (boss == null)
                return;
            if (boss.getWorld() != duelCenter.getWorld())
                return;

            double distSq = boss.getLocation().distanceSquared(duelCenter);
            if (distSq <= DUEL_RADIUS * DUEL_RADIUS)
                return;

            Vector back = duelCenter.toVector().subtract(boss.getLocation().toVector()).normalize();
            Location newLoc = boss.getLocation().clone().add(back.multiply(2.0));
            boss.teleport(newLoc);
        }, 20L, 10L);
    }

    private void startZombieCleaner() {
        zombieCleanerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || duelCenter == null)
                return;
            World w = duelCenter.getWorld();
            if (w == null)
                return;

            // Чистим всех зомби кроме босса и миньонов в радиусе 70 блоков
            double clearRadius = 70.0;
            for (Entity ent : w.getNearbyEntities(duelCenter, clearRadius, 30, clearRadius)) {
                if (!(ent instanceof Zombie z))
                    continue;
                if (shouldKeepDuringDuelCleanup(z))
                    continue;
                z.remove();
            }
        }, 20L, 20L * 3L); // Каждые 3 секунды
    }

    private void startPhaseMonitor() {
        phaseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            Zombie boss = getBoss();
            if (boss == null)
                return;

            double max = getBossMaxHealth(boss);
            if (max <= 0)
                return;
            double hp = boss.getHealth();
            double ratio = hp / max;

            if (!interestMode && ratio <= 0.90) {
                interestMode = true;
                setBossSpeed(boss, BOSS_SPEED_INTEREST);
                setBossDamage(boss, BOSS_DAMAGE_INTEREST);
                sendBossMessage("Интересно...");
            }

            if (!seriousMode && ratio <= 0.50) {
                seriousMode = true;
                equipBossWeaponIfNeeded(boss);
                setBossSpeed(boss, BOSS_SPEED_SERIOUS);
                setBossDamage(boss, BOSS_DAMAGE_SERIOUS);
                sendBossMessage("Хватит игр.");
            }

            if (!rageMode && ratio <= 0.05) {
                rageMode = true;
                setBossSpeed(boss, BOSS_SPEED_RAGE);
                setBossDamage(boss, BOSS_DAMAGE_RAGE);
                sendBossMessage("Ярость.");
            }
        }, 20L, 10L);
    }

    private void startRegen() {
        regenTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            Zombie boss = getBoss();
            if (boss == null || boss.isDead())
                return;

            double max = getBossMaxHealth(boss);
            if (max <= 0)
                return;

            double hp = boss.getHealth();
            if (hp >= max)
                return;

            double add = rageMode ? 1.0 : (seriousMode ? 0.8 : (interestMode ? 0.6 : 0.4));
            boss.setHealth(Math.min(max, hp + add));
        }, 20L, 20L);
    }

    private void startBarrierEffects() {
        barrierTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || duelCenter == null)
                return;
            World w = duelCenter.getWorld();
            if (w == null)
                return;

            int points = 18;
            for (int i = 0; i < points; i++) {
                double angle = (Math.PI * 2 * i) / points;
                double x = duelCenter.getX() + Math.cos(angle) * DUEL_RADIUS;
                double z = duelCenter.getZ() + Math.sin(angle) * DUEL_RADIUS;
                double y = duelCenter.getY() + 1.0;
                w.spawnParticle(Particle.PORTAL, x, y, z, 1, 0, 0, 0, 0);
            }
        }, 20L, 10L);
    }

    private void endDuel(String reason, boolean playerWon, boolean bossWon) {
        active = false;
        stage = Stage.END;

        cancelTask(freezeTask);
        cancelTask(lookTask);
        cancelTask(barrierTask);
        cancelTask(targetTask);
        cancelTask(mobilityTask);
        cancelTask(bossClampTask);
        cancelTask(zombieCleanerTask);
        cancelTask(phaseTask);
        cancelTask(regenTask);
        cancelTask(bossBarTask);
        cancelTask(helpItemTask);
        cancelTask(assistCheckTask);
        stopAdaptationCheck();

        restoreArena();

        helpItemGiven = false;
        UUID allyIdSnapshot = allyPlayerUuid;
        allyPlayerUuid = null;
        allyUsed = false;
        bossFocusUuid = null;
        bossFocusUntil = 0L;
        playerDebuffExpiry.clear();
        antiCheatLastHitAt.clear();
        antiCheatFastHitScore.clear();
        antiCheatMovement.clear();
        antiCheatPunishCooldown.clear();
        adaptationCounterSpeakCooldown.clear();
        warnCooldown.clear();
        lastForceSeparationTime = 0L;

        if (shouldClearAdaptationOnEnd(reason)) {
            clearSkillAdaptationData();
        }

        Zombie boss = getBoss();
        if (boss != null && !boss.isDead()) {
            boss.setVelocity(new Vector(0, 0, 0)); // Очистим velocity босса
            boss.remove();
        }

        // Телепортируем игрока обратно на базу
        Player duelPlayer = duelPlayerUuid != null ? Bukkit.getPlayer(duelPlayerUuid) : null;
        Player allyPlayer = allyIdSnapshot != null ? Bukkit.getPlayer(allyIdSnapshot) : null;
        if (duelPlayer != null && duelPlayer.isOnline()) {
            Location baseLocation = plugin.base() != null ? plugin.base().getCenter() : null;
            if (baseLocation != null) {
                duelPlayer.teleport(baseLocation);
            } else if (lastInsideLocation != null) {
                duelPlayer.teleport(lastInsideLocation);
            }
        }

        if (allyPlayer != null && allyPlayer.isOnline()) {
            Location baseLocation = plugin.base() != null ? plugin.base().getCenter() : null;
            if (baseLocation != null) {
                allyPlayer.teleport(baseLocation);
            } else if (lastInsideLocation != null) {
                allyPlayer.teleport(lastInsideLocation);
            }
        }

        duelCenter = null;
        duelPlayerUuid = null;
        bossUuid = null;
        lastInsideLocation = null;

        recentHitCount = 0;
        lastHitAt = 0L;
        weaponDrawn = false;
        interestMode = false;
        seriousMode = false;
        rageMode = false;
        dashPreparing = false;
        nextDashAtMillis = 0L;
        unarmedComboActive = false;
        cancelUnarmedComboDashTask();
        nextUnarmedComboAtMillis = 0L;
        stage = Stage.NONE;
    }

    private boolean shouldClearAdaptationOnEnd(String reason) {
        return "boss_dead".equalsIgnoreCase(reason)
                || "all_players_dead".equalsIgnoreCase(reason);
    }

    private void recordArenaBlock(World w, int x, int y, int z) {
        if (w == null)
            return;
        if (arenaWorld == null)
            arenaWorld = w;
        if (arenaWorld != w)
            return;
        BlockVector key = new BlockVector(x, y, z);
        arenaSnapshot.putIfAbsent(key, w.getBlockAt(x, y, z).getBlockData().clone());
    }

    private void restoreArena() {
        if (arenaWorld == null || arenaSnapshot.isEmpty()) {
            arenaSnapshot.clear();
            arenaWorld = null;
            return;
        }

        for (Map.Entry<BlockVector, BlockData> entry : arenaSnapshot.entrySet()) {
            BlockVector key = entry.getKey();
            BlockData data = entry.getValue();
            arenaWorld.getBlockAt(key.getBlockX(), key.getBlockY(), key.getBlockZ()).setBlockData(data, false);
        }

        arenaSnapshot.clear();
        arenaWorld = null;
    }

    private void startBossBar() {
        bossBarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active) {
                if (bossBarTask != null) {
                    bossBarTask.cancel();
                    bossBarTask = null;
                }
                return;
            }

            Zombie boss = getBoss();
            if (boss == null || boss.isDead())
                return;

            double maxHealth = getBossMaxHealth(boss);
            double currentHealth = boss.getHealth();
            int hpInt = (int) Math.round(currentHealth);
            int maxHpInt = (int) Math.round(maxHealth);

            String hpText = "§5§l[?????] §d" + hpInt + "/" + maxHpInt;
            boss.customName(LEGACY.deserialize(hpText));
        }, 0L, 2L);
    }

    private void sendBossMessage(String text) {
        if (text == null || text.isBlank())
            return;

        String message = BOSS_PREFIX + text;
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }

    private void sendSkillAdaptationMessage(Player player, String skillName, int tier) {
        String who = (player != null) ? player.getName() : "игрок";
        String skill = humanReadableSkillName(skillName);
        String tierText = switch (Math.max(1, Math.min(3, tier))) {
            case 1 -> "I";
            case 2 -> "II";
            default -> "III";
        };
        String message = BOSS_PREFIX + "... " + "Я адаптируюсь: " + who + ", " + skill + " (уровень " + tierText
                + ").";
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }

    void announceAdaptationCounter(Player player, String skillName, int tier) {
        if (player == null || skillName == null)
            return;

        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        long last = adaptationCounterSpeakCooldown.getOrDefault(playerId, 0L);
        if (now - last < 2200L)
            return;
        adaptationCounterSpeakCooldown.put(playerId, now);

        String skill = humanReadableSkillName(skillName);
        String who = player.getName();
        String line = buildTypedCounterLine(who, skillName, skill, tier);

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(BOSS_PREFIX + line);
        }
    }

    private enum CounterReadType {
        MOBILITY,
        CONTROL,
        SUMMON,
        OTHER
    }

    private String buildTypedCounterLine(String who, String skillName, String humanSkill, int tier) {
        int t = Math.max(1, Math.min(3, tier));
        CounterReadType type = resolveCounterReadType(skillName);

        return switch (type) {
            case MOBILITY -> switch (t) {
                case 1 -> "Читаю мобильность, " + who + ": " + humanSkill + ".";
                case 2 -> "Прочитал мобильность, " + who + ": " + humanSkill + ".";
                default -> "Мобильность закрыта, " + who + ": " + humanSkill + ".";
            };
            case CONTROL -> switch (t) {
                case 1 -> "Читаю контроль, " + who + ": " + humanSkill + ".";
                case 2 -> "Прочитал контроль, " + who + ": " + humanSkill + ".";
                default -> "Контроль закрыт, " + who + ": " + humanSkill + ".";
            };
            case SUMMON -> switch (t) {
                case 1 -> "Читаю призыв, " + who + ": " + humanSkill + ".";
                case 2 -> "Прочитал призыв, " + who + ": " + humanSkill + ".";
                default -> "Призыв закрыт, " + who + ": " + humanSkill + ".";
            };
            case OTHER -> switch (t) {
                case 1 -> "Запомнил твой темп, " + who + ": " + humanSkill + ".";
                case 2 -> "Читаю этот прием, " + who + ": " + humanSkill + ".";
                default -> "Этот ход закрыт, " + who + ": " + humanSkill + ".";
            };
        };
    }

    private CounterReadType resolveCounterReadType(String skillName) {
        if (skillName == null || skillName.isBlank())
            return CounterReadType.OTHER;

        return switch (skillName) {
            case "ping_blink", "berserk_blood_dash", "duelist_feint", "harpoon_pull", "cyborg_slam" ->
                CounterReadType.MOBILITY;

            case "gravity_crush", "levitation_strike", "archer_trap_arrow", "archer_ricochet",
                    "ping_pulse", "ping_jitter", "harpoon_anchor", "duelist_counter_stance",
                    "circle_trance", "exorcist_purge" ->
                CounterReadType.CONTROL;

            case "clone_summon", "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex",
                    "summoner_focus", "summoner_regroup", "summoner_sacrifice" ->
                CounterReadType.SUMMON;

            default -> CounterReadType.OTHER;
        };
    }

    private String humanReadableSkillName(String skillName) {
        if (skillName == null)
            return "неизвестный прием";
        return switch (skillName) {
            case "gravity_crush" -> "Гравитационный пресс";
            case "levitation_strike" -> "Левитационный удар";
            case "archer_rain" -> "Дождь стрел";
            case "archer_mark" -> "Метка охотника";
            case "archer_trap_arrow" -> "Капкан-стрела";
            case "archer_ricochet" -> "Рикошет";
            case "berserk" -> "Берсерк";
            case "berserk_blood_dash" -> "Кровавый рывок";
            case "berserk_execution" -> "Казнь";
            case "ritual_cut" -> "Ритуальный разрез";
            case "circle_trance" -> "Трансовый круг";
            case "duelist_counter_stance" -> "Контрстойка";
            case "duelist_feint" -> "Финт";
            case "fighter_combo" -> "Комбо бойца";
            case "clone_summon" -> "Призыв клонов";
            case "summoner_wolves" -> "Призыв волков";
            case "summoner_phantom" -> "Призыв фантома";
            case "summoner_golem" -> "Призыв голема";
            case "summoner_vex" -> "Призыв векса";
            case "summoner_focus" -> "Фокус-команда";
            case "summoner_regroup" -> "Перегруппировка";
            case "summoner_sacrifice" -> "Жертвенный импульс";
            case "ping_blink" -> "Пинг-рывок";
            case "ping_pulse" -> "Пинг-импульс";
            case "ping_jitter" -> "Джиттер";
            case "harpoon_anchor" -> "Якорный гарпун";
            case "harpoon_pull" -> "Подтяжка";
            case "cyborg_slam" -> "Реактивный таран";
            case "medic_wave" -> "Полевая терапия";
            case "exorcist_purge" -> "Священный изгиб";
            default -> skillName;
        };
    }

    private void spawnBoss(Location loc) {
        World w = loc.getWorld();
        if (w == null)
            return;

        try {
            bossSpawning = true;
            Zombie boss = w.spawn(loc, Zombie.class, z -> {
                z.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
                z.customName(LEGACY.deserialize(BOSS_NAME));
                z.setCustomNameVisible(true);
                z.setRemoveWhenFarAway(false);
                z.setPersistent(true);
                z.setAdult();
                z.setCanPickupItems(false);

                EntityEquipment eq = z.getEquipment();
                if (eq != null) {
                    eq.setHelmet(new ItemStack(org.bukkit.Material.IRON_HELMET));
                    eq.setChestplate(new ItemStack(org.bukkit.Material.DIAMOND_CHESTPLATE));
                    eq.setLeggings(new ItemStack(org.bukkit.Material.DIAMOND_LEGGINGS));
                    eq.setBoots(new ItemStack(org.bukkit.Material.IRON_BOOTS));
                    // Сначала без оружия
                    eq.setItemInMainHand(null);

                    eq.setHelmetDropChance(0f);
                    eq.setChestplateDropChance(0f);
                    eq.setLeggingsDropChance(0f);
                    eq.setBootsDropChance(0f);
                    eq.setItemInMainHandDropChance(0f);
                }

                Attribute maxHealthAttr = getAttribute("generic.max_health", "max_health");
                AttributeInstance maxHealth = (maxHealthAttr != null) ? z.getAttribute(maxHealthAttr) : null;
                if (maxHealth != null) {
                    maxHealth.setBaseValue(BOSS_HP);
                    z.setHealth(Math.min(BOSS_HP, maxHealth.getValue()));
                } else {
                    z.setHealth(Math.min(BOSS_HP, z.getHealth()));
                }

                setBossDamage(z, BOSS_DAMAGE);
                setBossSpeed(z, BOSS_SPEED);

                Attribute followAttr = getAttribute("generic.follow_range", "follow_range");
                AttributeInstance follow = (followAttr != null) ? z.getAttribute(followAttr) : null;
                if (follow != null)
                    follow.setBaseValue(BOSS_FOLLOW_RANGE);

                Attribute kbAttr = getAttribute("generic.knockback_resistance", "knockback_resistance");
                AttributeInstance knockbackResistance = (kbAttr != null) ? z.getAttribute(kbAttr) : null;
                if (knockbackResistance != null)
                    knockbackResistance.setBaseValue(1.0);

                // Без ускорения - идет пафосно
            });

            bossUuid = boss.getUniqueId();
        } finally {
            bossSpawning = false;
        }
    }

    private void teleportBossBehindPlayer(Player p, Zombie boss) {
        Vector backward = p.getLocation().getDirection().normalize().multiply(-2.0);
        Location target = p.getLocation().clone().add(backward);
        target.setY(p.getLocation().getY());

        if (duelCenter != null && target.distanceSquared(duelCenter) > DUEL_RADIUS * DUEL_RADIUS) {
            target = duelCenter.clone();
        }

        boss.teleport(target);
        faceEntity(boss, p.getLocation());
    }

    private void faceEachOther(Player p, Location bossLoc) {
        faceEntity(p, bossLoc);
    }

    private void faceEntity(Entity entity, Location target) {
        Location loc = entity.getLocation();
        loc.setDirection(target.toVector().subtract(loc.toVector()));
        entity.teleport(loc);
    }

    private Player getDuelPlayer() {
        if (duelPlayerUuid == null)
            return null;
        return Bukkit.getPlayer(duelPlayerUuid);
    }

    private Player getAllyPlayer() {
        if (allyPlayerUuid == null)
            return null;
        return Bukkit.getPlayer(allyPlayerUuid);
    }

    private boolean isValidDuelTarget(Player p) {
        if (p == null || !p.isOnline() || p.isDead())
            return false;
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR)
            return false;
        if (!isDuelPlayer(p.getUniqueId()))
            return false;
        if (duelCenter == null || duelCenter.getWorld() == null)
            return true;
        if (!p.getWorld().getUID().equals(duelCenter.getWorld().getUID()))
            return false;
        return p.getLocation().distanceSquared(duelCenter) <= (DUEL_RADIUS + 3.0) * (DUEL_RADIUS + 3.0);
    }

    private double getHealthRatio(Player p) {
        if (p == null)
            return 1.0;
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = (max != null) ? max.getValue() : 20.0;
        if (maxHealth <= 0.0)
            return 1.0;
        return Math.max(0.0, Math.min(1.0, p.getHealth() / maxHealth));
    }

    private double targetScore(Zombie boss, Player target) {
        if (boss == null || target == null || boss.getWorld() != target.getWorld())
            return Double.MAX_VALUE;
        double distance = boss.getLocation().distance(target.getLocation());
        return distance * 1.8 + getHealthRatio(target) * 8.0;
    }

    private Player pickSmartTarget(Zombie boss) {
        Player main = getDuelPlayer();
        Player ally = getAllyPlayer();

        List<Player> candidates = new ArrayList<>(2);
        if (isValidDuelTarget(main))
            candidates.add(main);
        if (isValidDuelTarget(ally))
            candidates.add(ally);

        if (candidates.isEmpty())
            return null;

        if (candidates.size() == 1) {
            Player only = candidates.get(0);
            bossFocusUuid = only.getUniqueId();
            bossFocusUntil = System.currentTimeMillis() + 1200L;
            return only;
        }

        long now = System.currentTimeMillis();
        Player current = null;
        if (boss != null && boss.getTarget() instanceof Player currentTarget && isValidDuelTarget(currentTarget)) {
            current = currentTarget;
        }

        if (current != null && bossFocusUuid != null
                && current.getUniqueId().equals(bossFocusUuid)
                && now < bossFocusUntil) {
            return current;
        }

        Player first = candidates.get(0);
        Player second = candidates.get(1);
        Player chosen;
        if (current != null) {
            Player other = current.getUniqueId().equals(first.getUniqueId()) ? second : first;
            double scoreCurrent = targetScore(boss, current);
            double scoreOther = targetScore(boss, other);

            boolean shouldSwitch = scoreOther <= scoreCurrent + 4.0 || rng.nextDouble() < 0.45;
            chosen = shouldSwitch ? other : current;
        } else {
            double scoreFirst = targetScore(boss, first);
            double scoreSecond = targetScore(boss, second);
            chosen = (scoreFirst <= scoreSecond) ? first : second;
        }

        bossFocusUuid = chosen.getUniqueId();
        bossFocusUntil = now + 1200L + rng.nextInt(1200);
        return chosen;
    }

    private boolean isSummonedOrCloneAlly(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead())
            return false;

        if (entity.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
            return false;
        if (entity.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE))
            return false;

        if (plugin.summonerKit() != null) {
            Byte summonMark = entity.getPersistentDataContainer().get(plugin.summonerKit().summonMarkKey(),
                    PersistentDataType.BYTE);
            if (summonMark != null && summonMark == (byte) 1)
                return true;
        }

        if (plugin.cloneKit() != null) {
            Byte cloneMark = entity.getPersistentDataContainer().get(plugin.cloneKit().cloneMarkKey(),
                    PersistentDataType.BYTE);
            if (cloneMark != null && cloneMark == (byte) 1)
                return true;
        }

        return false;
    }

    private boolean isValidBossMobTarget(LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead())
            return false;
        if (!isSummonedOrCloneAlly(target))
            return false;

        if (!active || duelCenter == null || duelCenter.getWorld() == null)
            return true;
        if (!target.getWorld().getUID().equals(duelCenter.getWorld().getUID()))
            return false;

        double maxDist = (DUEL_RADIUS + 8.0) * (DUEL_RADIUS + 8.0);
        return target.getLocation().distanceSquared(duelCenter) <= maxDist;
    }

    private boolean isValidBossCombatTarget(LivingEntity target) {
        if (target == null)
            return false;
        if (target instanceof Player player)
            return isValidDuelTarget(player);
        return isValidBossMobTarget(target);
    }

    private void triggerSummonerCounterShockwave(Zombie boss, int tier) {
        if (boss == null || boss.isDead())
            return;

        double radius = Math.max(3.0, plugin.getConfig().getDouble("boss.summoner_counter.shockwave_radius", 6.5));
        double basePower = Math.max(0.2, plugin.getConfig().getDouble("boss.summoner_counter.shockwave_power", 1.0));
        double powerPerTier = Math.max(0.0,
                plugin.getConfig().getDouble("boss.summoner_counter.shockwave_tier_bonus", 0.18));
        double baseUpward = Math.max(0.05,
                plugin.getConfig().getDouble("boss.summoner_counter.shockwave_upward", 0.24));
        double upwardPerTier = Math.max(0.0,
                plugin.getConfig().getDouble("boss.summoner_counter.shockwave_upward_tier_bonus", 0.03));

        int tierOffset = Math.max(0, tier - 1);
        double power = basePower + powerPerTier * tierOffset;
        double upward = baseUpward + upwardPerTier * tierOffset;

        Location center = boss.getLocation();
        int affected = 0;

        for (Entity entity : boss.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (living.getUniqueId().equals(boss.getUniqueId()))
                continue;
            if (living.isDead() || !living.isValid())
                continue;

            if (living instanceof Player player) {
                if (!isValidDuelTarget(player))
                    continue;
            } else if (!isValidBossMobTarget(living)) {
                continue;
            }

            Vector push = living.getLocation().toVector().subtract(center.toVector()).setY(0.0);
            if (push.lengthSquared() < 0.0001) {
                push = new Vector(rng.nextDouble() - 0.5, 0.0, rng.nextDouble() - 0.5);
            }
            if (push.lengthSquared() < 0.0001) {
                push = new Vector(0, 0, 1);
            }

            living.setVelocity(living.getVelocity().multiply(0.2).add(push.normalize().multiply(power).setY(upward)));
            affected++;
        }

        if (affected > 0) {
            Location fx = center.clone().add(0, 1.0, 0);
            boss.getWorld().spawnParticle(Particle.SONIC_BOOM, fx, 1, 0.0, 0.0, 0.0, 0.0);
            boss.getWorld().spawnParticle(Particle.CLOUD, fx, 24, 0.45, 0.22, 0.45, 0.01);
            boss.getWorld().playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.65f, 1.35f);
        }
    }

    private LivingEntity findNearestMobAllyTarget(Zombie boss, double radius) {
        LivingEntity best = null;
        double bestDist = radius * radius;

        for (Entity entity : boss.getWorld().getNearbyEntities(boss.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isValidBossMobTarget(living))
                continue;

            double dist = living.getLocation().distanceSquared(boss.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }

        return best;
    }

    private LivingEntity pickCombatTarget(Zombie boss) {
        if (boss == null || boss.isDead())
            return null;

        LivingEntity current = (boss.getTarget() instanceof LivingEntity living) ? living : null;
        if (isValidBossMobTarget(current)) {
            double keepDist = boss.getLocation().distanceSquared(current.getLocation());
            boolean flyingCurrent = current instanceof org.bukkit.entity.Phantom
                    || current instanceof org.bukkit.entity.Vex;
            if (!flyingCurrent && keepDist <= 20.0 * 20.0)
                return current;
        }

        Player playerTarget = pickSmartTarget(boss);
        LivingEntity mobTarget = findNearestMobAllyTarget(boss, 22.0);

        if (mobTarget == null)
            return playerTarget;
        if (playerTarget == null)
            return mobTarget;

        double mobDist = boss.getLocation().distanceSquared(mobTarget.getLocation());
        double playerDist = boss.getLocation().distanceSquared(playerTarget.getLocation());

        boolean flyingMob = mobTarget instanceof org.bukkit.entity.Phantom
                || mobTarget instanceof org.bukkit.entity.Vex;
        if (flyingMob)
            return playerTarget;

        if (mobDist + 4.0 < playerDist && rng.nextDouble() < 0.55)
            return mobTarget;
        return playerTarget;
    }

    private LivingEntity resolvePreferredPetResponseTarget(LivingEntity petDamager) {
        if (petDamager == null)
            return null;

        Player duel = getDuelPlayer();
        Player ally = getAllyPlayer();

        Player nearestPlayer = null;
        double bestDist = Double.MAX_VALUE;

        if (isValidDuelTarget(duel)) {
            double dist = duel.getLocation().distanceSquared(petDamager.getLocation());
            nearestPlayer = duel;
            bestDist = dist;
        }
        if (isValidDuelTarget(ally)) {
            double dist = ally.getLocation().distanceSquared(petDamager.getLocation());
            if (nearestPlayer == null || dist < bestDist) {
                nearestPlayer = ally;
                bestDist = dist;
            }
        }

        boolean flyingPet = petDamager instanceof org.bukkit.entity.Phantom
                || petDamager instanceof org.bukkit.entity.Vex;
        if (flyingPet && nearestPlayer != null) {
            return nearestPlayer;
        }

        if (nearestPlayer != null && bestDist <= (7.0 * 7.0)) {
            return nearestPlayer;
        }

        return petDamager;
    }

    private LivingEntity resolveCombatPetDamager(Entity damager) {
        if (damager instanceof LivingEntity living)
            return living;

        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter)
            return shooter;

        return null;
    }

    private Zombie getBoss() {
        if (bossUuid == null)
            return null;
        Entity ent = Bukkit.getEntity(bossUuid);
        if (ent instanceof Zombie z)
            return z;
        return null;
    }

    private void removeNearbyZombies(Location center, double radius) {
        if (center == null || center.getWorld() == null)
            return;
        double r2 = radius * radius;
        for (Entity ent : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(ent instanceof Zombie z))
                continue;
            if (shouldKeepDuringDuelCleanup(z))
                continue;
            if (z.getLocation().distanceSquared(center) <= r2)
                z.remove();
        }
    }

    private boolean shouldKeepDuringDuelCleanup(Zombie zombie) {
        if (zombie == null)
            return true;
        if (zombie.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
            return true;
        if (zombie.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE))
            return true;
        return isSummonedOrCloneAlly(zombie);
    }

    private Location findBossSpawn(World w) {
        Location base = plugin.base().getCenter();
        if (base == null)
            return null;

        // Ищем хорошее место для спавна
        for (int i = 0; i < 30; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = MIN_DISTANCE_FROM_BASE + rng.nextDouble() * (MAX_DISTANCE_FROM_BASE - MIN_DISTANCE_FROM_BASE);

            int centerX = (int) Math.round(base.getX() + Math.cos(angle) * dist);
            int centerZ = (int) Math.round(base.getZ() + Math.sin(angle) * dist);

            // Находим безопасную Y (самая высокая точка в радиусе)
            int baseY = w.getHighestBlockYAt(centerX, centerZ);
            if (baseY < 5)
                continue; // Слишком низко (вода?)
            if (baseY > 256)
                baseY = 256; // Слишком высоко

            // Создаем плоскую арену размером 80x80
            createFlatArena(w, centerX, baseY, centerZ);

            Location spawnLoc = new Location(w, centerX + 0.5, baseY + 1.5, centerZ + 0.5);
            plugin.getLogger().info("[BossDuel] Арена создана на высоте Y=" + baseY + " в " + centerX + ", " + centerZ);
            return spawnLoc;
        }

        return null;
    }

    private void createFlatArena(World w, int centerX, int baseY, int centerZ) {
        restoreArena();
        arenaWorld = w;

        int arenaRadius = 40;
        int platformHeight = baseY;

        // Выравниваем платформу: удаляем все выше, заполняем камнем/землей
        for (int x = centerX - arenaRadius; x <= centerX + arenaRadius; x++) {
            for (int z = centerZ - arenaRadius; z <= centerZ + arenaRadius; z++) {
                // Удаляем все блоки выше платформы
                for (int y = platformHeight + 1; y < platformHeight + 20; y++) {
                    var block = w.getBlockAt(x, y, z);
                    if (block.getType() != org.bukkit.Material.AIR) {
                        recordArenaBlock(w, x, y, z);
                        block.setType(org.bukkit.Material.AIR);
                    }
                }

                // Выравниваем платформу: если ниже пусто, заполняем камнем
                org.bukkit.block.Block platformBlock = w.getBlockAt(x, platformHeight, z);
                if (platformBlock.isEmpty() || platformBlock.getType() == org.bukkit.Material.WATER) {
                    recordArenaBlock(w, x, platformHeight, z);
                    platformBlock.setType(org.bukkit.Material.STONE);
                }
            }
        }

        // Убираем ближайшие зомби со всей арены
        double clearRadius = arenaRadius + 5.0;
        Location centerLoc = new Location(w, centerX + 0.5, platformHeight + 1.0, centerZ + 0.5);
        for (Entity ent : w.getNearbyEntities(centerLoc, clearRadius, 20, clearRadius)) {
            if (ent instanceof Zombie z) {
                if (shouldKeepDuringDuelCleanup(z))
                    continue;
                z.remove();
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!active || duelCenter == null)
            return;
        Player p = e.getPlayer();

        if (stage == Stage.FREEZE && isDuelPlayer(p.getUniqueId())) {
            if (e.getTo() != null && e.getFrom().distanceSquared(e.getTo()) > 0) {
                e.setTo(e.getFrom());
                return;
            }
        }

        if (isDuelPlayer(p.getUniqueId())) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR)
                return;
            if (e.getTo() == null)
                return;
            if (e.getTo().getWorld() != duelCenter.getWorld())
                return;

            checkMovementAntiCheat(p, e.getFrom(), e.getTo());

            double distSq = e.getTo().distanceSquared(duelCenter);
            if (distSq <= DUEL_RADIUS * DUEL_RADIUS) {
                if (duelPlayerUuid != null && duelPlayerUuid.equals(p.getUniqueId())) {
                    lastInsideLocation = e.getTo().clone();
                }
                return;
            }

            Location back = (lastInsideLocation != null) ? lastInsideLocation : duelCenter.clone();
            internalTeleport = true;
            p.teleport(back);
            Bukkit.getScheduler().runTaskLater(plugin, () -> internalTeleport = false, 2L);
            p.sendMessage("§cТы не можешь покинуть арену дуэли!");
            return;
        }

        if (e.getTo() == null || e.getTo().getWorld() != duelCenter.getWorld())
            return;
        double distSq = e.getTo().distanceSquared(duelCenter);
        if (distSq <= DUEL_RADIUS * DUEL_RADIUS) {
            // Allow up to 2 players in duel zone (main + 1 ally)
            int playersInZone = 0;
            if (duelPlayerUuid != null)
                playersInZone++;
            if (allyPlayerUuid != null)
                playersInZone++;

            // If already 2 players, block entry
            if (playersInZone >= 2) {
                Vector dir = e.getTo().toVector().subtract(duelCenter.toVector()).normalize();
                Location out = duelCenter.clone().add(dir.multiply(DUEL_RADIUS + 2));
                out.setY(e.getTo().getY());
                internalTeleport = true;
                p.teleport(out);
                Bukkit.getScheduler().runTaskLater(plugin, () -> internalTeleport = false, 2L);

                long now = System.currentTimeMillis();
                long last = warnCooldown.getOrDefault(p.getUniqueId(), 0L);
                if (now - last > 3000) {
                    p.sendMessage("§cДуэль уже идёт 1 на 2. Не вмешивайся.");
                    warnCooldown.put(p.getUniqueId(), now);
                }
                return;
            }

            // Allow entry and register as potential ally
            if (allyPlayerUuid == null && !p.getUniqueId().equals(duelPlayerUuid)) {
                allyPlayerUuid = p.getUniqueId();
                p.sendMessage("§eТы вошёл в дуэль как помощник!");
            }
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (!active)
            return;
        Player player = e.getPlayer();
        if (!isDuelPlayer(player.getUniqueId()))
            return;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR)
            return;
        if (internalTeleport)
            return;
        // Не считать побегом во время инициализации
        if (stage == Stage.INIT || stage == Stage.FREEZE)
            return;

        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            Location to = e.getTo();
            if (to == null || duelCenter == null || duelCenter.getWorld() == null || to.getWorld() == null
                    || !to.getWorld().getUID().equals(duelCenter.getWorld().getUID())) {
                e.setCancelled(true);
                Location safe = (lastInsideLocation != null) ? lastInsideLocation.clone() : duelCenter.clone();
                internalTeleport = true;
                player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                Bukkit.getScheduler().runTaskLater(plugin, () -> internalTeleport = false, 2L);
                player.sendMessage("§cЭндер-перл не может вывести тебя за пределы арены.");
                return;
            }

            if (!isInsideDuelZone(to, -1.0)) {
                e.setCancelled(true);

                Vector dir = to.toVector().subtract(duelCenter.toVector()).setY(0.0);
                if (dir.lengthSquared() < 0.0001) {
                    dir = player.getLocation().getDirection().setY(0.0);
                }
                if (dir.lengthSquared() < 0.0001) {
                    dir = new Vector(1, 0, 0);
                }

                Location safe = duelCenter.clone().add(dir.normalize().multiply(DUEL_RADIUS - 1.5));
                safe.setY(Math.max(player.getLocation().getY(), duelCenter.getY()));

                internalTeleport = true;
                player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                Bukkit.getScheduler().runTaskLater(plugin, () -> internalTeleport = false, 2L);
                player.sendMessage("§cЭндер-перл не может вывести тебя за пределы арены.");
            }
            return;
        }

        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            Location to = e.getTo();
            if (to != null
                    && duelCenter != null
                    && duelCenter.getWorld() != null
                    && to.getWorld() != null
                    && to.getWorld().getUID().equals(duelCenter.getWorld().getUID())
                    && isInsideDuelZone(to, -1.0)) {
                if (duelPlayerUuid != null && duelPlayerUuid.equals(player.getUniqueId())) {
                    lastInsideLocation = to.clone();
                }
                return;
            }

            e.setCancelled(true);
            Location safe = (lastInsideLocation != null) ? lastInsideLocation.clone() : duelCenter.clone();
            internalTeleport = true;
            player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
            Bukkit.getScheduler().runTaskLater(plugin, () -> internalTeleport = false, 2L);
            player.sendMessage("§cНавыком нельзя телепортироваться за пределы арены.");
            return;
        }

        // Removed boss message
        endDuel("teleport", false, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!active)
            return;
        if (!isDuelPlayer(e.getPlayer().getUniqueId()))
            return;

        // Removed boss message
        endDuel("quit", false, true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!active)
            return;
        if (!isDuelPlayer(e.getEntity().getUniqueId()))
            return;

        // Проверяем, остались ли живые игроки в арене
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active)
                return;

            int aliveInArena = countAlivePlayersInArena();
            if (aliveInArena == 0) {
                // Все игроки в арене мертвы - завершить дуэль и ночь
                endDuel("all_players_dead", false, true);

                // Если день >= 10, завершаем ночь
                if (duelDay >= 10 && plugin.phase() != null) {
                    plugin.phase().endNightEarly();
                }
            }
        }, 2L);
    }

    private int countAlivePlayersInArena() {
        int count = 0;
        Player main = getDuelPlayer();
        if (main != null && main.isOnline() && !main.isDead()
                && main.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            count++;
        }
        if (allyPlayerUuid != null) {
            Player ally = Bukkit.getPlayer(allyPlayerUuid);
            if (ally != null && ally.isOnline() && !ally.isDead()
                    && ally.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                count++;
            }
        }
        return count;
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Zombie z))
            return;
        if (!z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
            return;

        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            long reward = 500 + rng.nextInt(501);
            plugin.econ().give(killer, reward);
            int playerExp = Math.max(0, plugin.getConfig().getInt("player_progress.kill_exp.boss", 30));
            if (playerExp > 0) {
                plugin.progress().addPlayerExp(killer, playerExp);
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage("§dИгрок " + killer.getName() + " победил ?????");
            }
        }

        endDuel("boss_dead", true, false);

        // Если день >= 10, завершаем ночь
        if (duelDay >= 10 && plugin.phase() != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.phase().endNightEarly();
            }, 10L);
        }
    }

    @EventHandler
    public void onBossTarget(EntityTargetEvent e) {
        if (!(e.getEntity() instanceof Zombie z))
            return;
        if (!z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
            return;

        if (!active || stage != Stage.FIGHT)
            return;

        LivingEntity smartTarget = pickCombatTarget(z);
        if (smartTarget == null)
            return;

        if (!(e.getTarget() instanceof LivingEntity current)
                || !isValidBossCombatTarget(current)
                || !current.getUniqueId().equals(smartTarget.getUniqueId())) {
            e.setCancelled(true);
            z.setTarget(smartTarget);
        }
    }

    @EventHandler
    public void onBossDealsDamage(EntityDamageByEntityEvent e) {
        if (!active || stage != Stage.FIGHT)
            return;
        if (!(e.getDamager() instanceof Zombie z))
            return;
        if (!z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
            return;
        if (!(e.getEntity() instanceof LivingEntity target))
            return;
        if (!isValidBossMobTarget(target))
            return;

        double mult = plugin.getConfig().getDouble("boss.summoner_counter.mob_damage_multiplier", 0.55);
        mult = Math.max(0.05, Math.min(1.0, mult));
        e.setDamage(e.getDamage() * mult);
    }

    @EventHandler
    public void onBossDamaged(EntityDamageByEntityEvent e) {
        if (!active || stage != Stage.FIGHT)
            return;
        if (!(e.getEntity() instanceof Zombie z))
            return;
        if (!z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
            return;

        LivingEntity petDamager = resolveCombatPetDamager(e.getDamager());
        if (petDamager != null && !(petDamager instanceof Player) && isValidBossMobTarget(petDamager)) {
            equipBossWeaponIfNeeded(z);
            LivingEntity preferred = resolvePreferredPetResponseTarget(petDamager);
            if (preferred != null) {
                z.setTarget(preferred);
            }
            return;
        }

        if (!(e.getDamager() instanceof Player p))
            return;
        if (!isDuelPlayer(p.getUniqueId()))
            return;

        // Track last attack time for assist logic
        warnCooldown.put(p.getUniqueId(), System.currentTimeMillis());

        equipBossWeaponIfNeeded(z);
        Bukkit.getScheduler().runTaskLater(plugin, () -> equipBossWeaponIfNeeded(getBoss()), 1L);

        if (isGravityCrushChanneling(p.getUniqueId())) {
            Vector vel = z.getVelocity();
            z.setVelocity(new Vector(
                    vel.getX() * 0.12,
                    Math.min(vel.getY(), -0.85),
                    vel.getZ() * 0.12));
            z.setFallDistance(0.0f);
            return;
        }

        // Проверка: игрок блокирует щитом?
        if (p.isBlocking()) {
            e.setCancelled(true);
            // Телепортируемся сзади игрока и наносим урон
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!active || stage != Stage.FIGHT)
                    return;
                teleportBossBehindPlayer(p, z);
                z.attack(p);
                sendBossMessage("Щит не поможет.");
            }, 1L);
            return;
        }

        if (checkFastHitAntiCheat(p, z, e)) {
            return;
        }

        KitManager.Kit playerKit = plugin.kit().getKit(p.getUniqueId());
        if (playerKit == KitManager.Kit.FIGHTER) {
            registerSkillUsage(p, "fighter_combo");
        }

        BossAdaptationEngine.RecentSkillSnapshot recentSkill = getRecentSkillSnapshot(p.getUniqueId(), playerKit);

        double dodgeChance = rageMode ? 0.30 : (seriousMode ? 0.50 : (interestMode ? 0.30 : 0.10));
        double counterChance = rageMode ? 0.55 : (seriousMode ? 0.35 : (interestMode ? 0.25 : 0.15));

        if (recentSkill != null) {
            int tier = recentSkill.adaptationTier();
            String skill = recentSkill.skillName();

            if (tier <= 0) {
                if ("gravity_crush".equals(skill) || "levitation_strike".equals(skill)
                        || "archer_rain".equals(skill)
                        || "archer_mark".equals(skill)
                        || "archer_trap_arrow".equals(skill)
                        || "archer_ricochet".equals(skill)
                        || "berserk".equals(skill)
                        || "berserk_blood_dash".equals(skill)
                        || "berserk_execution".equals(skill)
                        || "ritual_cut".equals(skill)
                        || "circle_trance".equals(skill)
                        || "duelist_counter_stance".equals(skill)
                        || "duelist_feint".equals(skill)
                        || "summoner_focus".equals(skill)
                        || "summoner_regroup".equals(skill)
                        || "summoner_sacrifice".equals(skill)
                        || "ping_blink".equals(skill)
                        || "ping_pulse".equals(skill)
                        || "ping_jitter".equals(skill)
                        || "fighter_combo".equals(skill)) {
                    dodgeChance = 0.0;
                    counterChance = 0.0;
                }
            } else {
                double scale = switch (tier) {
                    case 1 -> 0.45;
                    case 2 -> 0.80;
                    default -> 1.10;
                };
                dodgeChance *= scale;
                counterChance *= scale;
            }
        }

        dodgeChance = Math.max(0.0, Math.min(0.85, dodgeChance));
        counterChance = Math.max(0.0, Math.min(0.90, counterChance));

        // Шанс уворота
        if (rng.nextDouble() < dodgeChance) {
            e.setCancelled(true);
            dodgeBossFromPlayer(z, p);

            // Контратака после уворота
            if (rng.nextDouble() < counterChance) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!active || stage != Stage.FIGHT)
                        return;
                    z.attack(p);
                }, 5L);
            }
            return;
        }

        // Удар прошел
        long now = System.currentTimeMillis();
        if (now - lastHitAt < 3000) {
            recentHitCount++;
        } else {
            recentHitCount = 1;
        }
        lastHitAt = now;

        if (recentHitCount >= 3) {
            // Removed boss message
            recentHitCount = 0;
        }

        // Check if player is debuffed (can't attack)
        Long debuffExpiry = playerDebuffExpiry.get(p.getUniqueId());
        if (debuffExpiry != null && System.currentTimeMillis() < debuffExpiry) {
            e.setCancelled(true);
            return;
        }

        // После успешного удара - если есть помощник, переключаемся на него
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;

            Player weakest = getWeakestPlayer();
            Zombie boss = getBoss();

            // Если есть более слабый игрок (не текущая цель) - телепортируемся к нему
            if (weakest != null && boss != null && !weakest.equals(p)) {
                teleportBossBehindPlayer(weakest, boss);
                boss.setTarget(weakest);
            }
        }, 5L);
    }

    /**
     * Возвращает игрока в арене с наименьшим количеством здоровья.
     */
    private Player getWeakestPlayer() {
        Player main = getDuelPlayer();
        Player ally = (allyPlayerUuid != null) ? Bukkit.getPlayer(allyPlayerUuid) : null;

        // Фильтруем: только живые, онлайн, в арене
        java.util.List<Player> players = new java.util.ArrayList<>();
        if (main != null && main.isOnline() && !main.isDead()
                && main.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            players.add(main);
        }
        if (ally != null && ally.isOnline() && !ally.isDead()
                && ally.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            players.add(ally);
        }

        if (players.isEmpty()) {
            return null;
        }

        // Возвращаем того с наименьшим здоровьем
        return players.stream()
                .min(java.util.Comparator.comparingDouble(Player::getHealth))
                .orElse(null);
    }

    private boolean checkFastHitAntiCheat(Player player, Zombie boss, EntityDamageByEntityEvent e) {
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long prev = antiCheatLastHitAt.put(playerId, now);
        int score = antiCheatFastHitScore.getOrDefault(playerId, 0);

        if (prev != null) {
            long delta = now - prev;

            if (delta > 0 && delta <= FAST_HIT_MIN_INTERVAL_MS) {
                score += 3;
            } else if (delta <= FAST_HIT_MID_INTERVAL_MS) {
                score += 2;
            } else if (delta <= 280L) {
                score += 1;
            } else {
                score = Math.max(0, score - 2);
            }
        }

        if (score >= FAST_HIT_TRIGGER_SCORE) {
            boolean punished = punishCheatLikeBehavior(player, boss, "Слишком быстрые удары");
            e.setCancelled(true);
            antiCheatFastHitScore.put(playerId, punished ? 0 : FAST_HIT_TRIGGER_SCORE - 2);
            return true;
        }

        antiCheatFastHitScore.put(playerId, score);
        return false;
    }

    private void checkMovementAntiCheat(Player player, Location from, Location to) {
        if (!active || stage != Stage.FIGHT)
            return;
        if (player == null || from == null || to == null)
            return;
        if (from.getWorld() == null || to.getWorld() == null)
            return;
        if (!from.getWorld().getUID().equals(to.getWorld().getUID()))
            return;

        UUID playerId = player.getUniqueId();
        MovementPatternState state = antiCheatMovement.computeIfAbsent(playerId, id -> new MovementPatternState());
        long now = System.currentTimeMillis();

        Vector horizontalDelta = to.toVector().subtract(from.toVector());
        horizontalDelta.setY(0);
        double moveSq = horizontalDelta.lengthSquared();

        if (moveSq < 0.008) {
            if (state.lastMoveAt > 0 && now - state.lastMoveAt > 700L) {
                state.zigZagScore = Math.max(0, state.zigZagScore - 1);
            }
            return;
        }

        if (moveSq > 3.5) {
            state.lastDirection = null;
            state.lastMoveAt = now;
            state.zigZagScore = Math.max(0, state.zigZagScore - 2);
            return;
        }

        Vector direction = horizontalDelta.normalize();
        long deltaTime = (state.lastMoveAt > 0) ? (now - state.lastMoveAt) : Long.MAX_VALUE;

        if (state.lastDirection != null) {
            double dot = direction.dot(state.lastDirection);

            if (dot < -0.55 && deltaTime <= 330L) {
                state.zigZagScore += 3;
            } else if (dot < -0.25 && deltaTime <= 260L) {
                state.zigZagScore += 2;
            } else {
                state.zigZagScore = Math.max(0, state.zigZagScore - 1);
            }
        } else {
            state.zigZagScore = Math.max(0, state.zigZagScore - 1);
        }

        if (deltaTime > 500L) {
            state.zigZagScore = Math.max(0, state.zigZagScore - 1);
        }

        state.lastDirection = direction.clone();
        state.lastMoveAt = now;

        if (state.zigZagScore >= ZIGZAG_TRIGGER_SCORE) {
            Zombie boss = getBoss();
            boolean punished = punishCheatLikeBehavior(player, boss, "Подозрительные рывки туда-сюда");
            state.zigZagScore = punished ? 0 : ZIGZAG_TRIGGER_SCORE - 2;
        }
    }

    private boolean punishCheatLikeBehavior(Player player, Zombie boss, String reason) {
        if (player == null || !player.isOnline())
            return false;

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastPunish = antiCheatPunishCooldown.getOrDefault(playerId, 0L);
        if (now - lastPunish < ANTI_CHEAT_PUNISH_COOLDOWN_MS) {
            return false;
        }
        antiCheatPunishCooldown.put(playerId, now);

        player.setSprinting(false);
        long debuffEnd = now + 1600L;
        playerDebuffExpiry.put(playerId, debuffEnd);
        Bukkit.getScheduler().runTaskLater(plugin, () -> playerDebuffExpiry.remove(playerId), 32L);

        player.setWalkSpeed(0.12f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && isDuelPlayer(player.getUniqueId())) {
                player.setWalkSpeed(0.2f);
            }
        }, 20L);

        Vector knockbackDirection;
        if (boss != null && !boss.isDead()) {
            knockbackDirection = player.getLocation().toVector().subtract(boss.getLocation().toVector());
        } else {
            knockbackDirection = player.getLocation().getDirection().multiply(-1);
        }

        knockbackDirection.setY(0);
        if (knockbackDirection.lengthSquared() < 0.0001) {
            knockbackDirection = new Vector(0, 0, 1);
        }
        Vector velocity = knockbackDirection.normalize().multiply(1.15).setY(0.35);
        player.setVelocity(velocity);

        if (boss != null && !boss.isDead()) {
            boss.setTarget(player);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!active || stage != Stage.FIGHT)
                    return;
                if (boss.isDead() || !player.isOnline())
                    return;

                teleportBossBehindPlayer(player, boss);
                boss.attack(player);
            }, 5L);
        }

        player.sendMessage("§c[Анти-чит босса] §7" + reason + ".");
        sendBossMessage("Хитришь? Тогда держи.");
        return true;
    }

    private void dodgeBossFromPlayer(Zombie boss, Player p) {
        double roll = rng.nextDouble();
        Vector baseDir = p.getLocation().getDirection().setY(0);
        if (baseDir.lengthSquared() < 0.0001) {
            baseDir = new Vector(0, 0, 1);
        }
        baseDir.normalize();

        Vector dir;

        if (roll < 0.33) {
            dir = baseDir.clone().rotateAroundY(Math.PI / 2).normalize();
        } else if (roll < 0.66) {
            dir = baseDir.clone().rotateAroundY(-Math.PI / 2).normalize();
        } else {
            dir = baseDir.clone().multiply(-1).normalize();
        }

        double power = dodgeSlideMin + rng.nextDouble() * (dodgeSlideMax - dodgeSlideMin);
        Vector impulse = dir.multiply(power).setY(0.0);

        // Ограничим область — если цель уводит за предел арены, ослабляем силу
        if (duelCenter != null
                && boss.getLocation().distanceSquared(duelCenter) > (DUEL_RADIUS - 3) * (DUEL_RADIUS - 3)) {
            impulse = impulse.multiply(0.5);
        }

        boss.setVelocity(impulse);
        if (boss.getLocation().getWorld() != null) {
            boss.getLocation().getWorld().spawnParticle(Particle.SMOKE, boss.getLocation().add(0, 1, 0), 12, 0.25, 0.4,
                    0.25, 0.01);
        }
    }

    private void backstepFromPlayer(Zombie boss, Player p) {
        Vector away = boss.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.0001) {
            away = new Vector(0, 0, 1);
        }
        away.normalize();

        double power = backstepSlideMin + rng.nextDouble() * (backstepSlideMax - backstepSlideMin);
        Vector impulse = away.multiply(power).setY(0.0);

        // Если отступ уведёт за арену, уменьшаем импульс
        if (duelCenter != null
                && boss.getLocation().distanceSquared(duelCenter) > (DUEL_RADIUS - 4) * (DUEL_RADIUS - 4)) {
            impulse = impulse.multiply(0.4);
        }

        boss.setVelocity(impulse);
    }

    private boolean tryStartUnarmedCombo(Zombie boss, LivingEntity target, long now) {
        if (boss == null || target == null)
            return false;
        if (dashPreparing || unarmedComboActive)
            return false;
        if (!isBossUnarmed(boss))
            return false;
        if (now < nextUnarmedComboAtMillis)
            return false;
        if (!boss.isOnGround())
            return false;

        double dist = boss.getLocation().distance(target.getLocation());
        if (dist < 2.5 || dist > 16.0)
            return false;

        unarmedComboActive = true;
        dashPreparing = true;

        UUID initialTargetId = target.getUniqueId();

        // Шаг 1: отпрыгивание назад
        Vector away = boss.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0.0);
        if (away.lengthSquared() < 0.0001) {
            away = new Vector(0, 0, 1);
        }
        away.normalize();
        boss.setVelocity(away.multiply(0.95).setY(0.28));

        Location fx = boss.getLocation().clone().add(0, 1.0, 0);
        boss.getWorld().spawnParticle(Particle.CLOUD, fx, 16, 0.35, 0.2, 0.35, 0.02);
        boss.getWorld().spawnParticle(Particle.SMOKE, fx, 14, 0.32, 0.2, 0.32, 0.01);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 0.75f);

        // Шаг 2: телеграф атаки для защиты
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            Zombie liveBoss = getBoss();
            if (liveBoss == null || liveBoss.isDead())
                return;

            LivingEntity liveTarget = resolveComboTarget(initialTargetId, liveBoss);
            if (liveTarget == null)
                return;

            Location center = liveBoss.getLocation().clone().add(0, 0.2, 0);
            for (int i = 0; i < 36; i++) {
                double angle = (Math.PI * 2.0 * i) / 36.0;
                double x = Math.cos(angle) * 1.9;
                double z = Math.sin(angle) * 1.9;
                liveBoss.getWorld().spawnParticle(Particle.CRIT,
                        center.getX() + x,
                        center.getY() + 0.3,
                        center.getZ() + z,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
            liveBoss.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 10, 0.6, 0.15, 0.6, 0.01);
            liveBoss.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.7f);
            liveBoss.getWorld().playSound(liveTarget.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.6f);
        }, 8L);

        // Шаг 3: резкий скользящий рывок в цель
        Bukkit.getScheduler().runTaskLater(plugin, () -> launchUnarmedComboSlide(initialTargetId), 16L);
        return true;
    }

    private void launchUnarmedComboSlide(UUID targetId) {
        if (!active || stage != Stage.FIGHT)
            return;

        Zombie boss = getBoss();
        if (boss == null || boss.isDead()) {
            finishUnarmedCombo(UNARMED_COMBO_FAIL_COOLDOWN_MS);
            return;
        }

        LivingEntity target = resolveComboTarget(targetId, boss);
        if (target == null) {
            finishUnarmedCombo(UNARMED_COMBO_FAIL_COOLDOWN_MS);
            return;
        }

        boss.setTarget(target);
        cancelUnarmedComboDashTask();

        Location fx = boss.getLocation().clone().add(0, 0.4, 0);
        boss.getWorld().spawnParticle(Particle.CLOUD, fx, 20, 0.45, 0.25, 0.45, 0.02);
        boss.getWorld().spawnParticle(Particle.CRIT, fx, 18, 0.35, 0.2, 0.35, 0.05);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.1f, 0.85f);

        unarmedComboDashTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int ticksLeft = 18;

            @Override
            public void run() {
                if (!active || stage != Stage.FIGHT) {
                    cancelUnarmedComboDashTask();
                    finishUnarmedCombo(UNARMED_COMBO_FAIL_COOLDOWN_MS);
                    return;
                }

                Zombie liveBoss = getBoss();
                if (liveBoss == null || liveBoss.isDead()) {
                    cancelUnarmedComboDashTask();
                    finishUnarmedCombo(UNARMED_COMBO_FAIL_COOLDOWN_MS);
                    return;
                }

                LivingEntity liveTarget = resolveComboTarget(targetId, liveBoss);
                if (liveTarget == null) {
                    cancelUnarmedComboDashTask();
                    finishUnarmedCombo(UNARMED_COMBO_FAIL_COOLDOWN_MS);
                    return;
                }

                liveBoss.setTarget(liveTarget);

                Vector toTarget = liveTarget.getLocation().toVector().subtract(liveBoss.getLocation().toVector())
                        .setY(0.0);
                double dist = liveBoss.getLocation().distance(liveTarget.getLocation());

                if (dist <= 2.15 || ticksLeft <= 0 || toTarget.lengthSquared() < 0.0001) {
                    cancelUnarmedComboDashTask();
                    doUnarmedComboFlurry(targetId, 0);
                    return;
                }

                double burstSpeed = Math.min(2.35, 1.25 + dist * 0.22);
                Vector slide = toTarget.normalize().multiply(burstSpeed).setY(0.05);

                if (duelCenter != null
                        && liveBoss.getLocation().distanceSquared(duelCenter) > (DUEL_RADIUS - 3) * (DUEL_RADIUS - 3)) {
                    slide = slide.multiply(0.72);
                }

                liveBoss.setVelocity(slide);
                if ((ticksLeft % 3) == 0) {
                    Location dashFx = liveBoss.getLocation().clone().add(0, 0.25, 0);
                    liveBoss.getWorld().spawnParticle(Particle.CLOUD, dashFx, 8, 0.2, 0.06, 0.2, 0.01);
                }

                ticksLeft--;
            }
        }, 0L, 1L);
    }

    private void doUnarmedComboFlurry(UUID targetId, int hitIndex) {
        if (!active || stage != Stage.FIGHT) {
            finishUnarmedCombo(UNARMED_COMBO_FAIL_COOLDOWN_MS);
            return;
        }

        Zombie boss = getBoss();
        if (boss == null || boss.isDead()) {
            finishUnarmedCombo(UNARMED_COMBO_FAIL_COOLDOWN_MS);
            return;
        }

        LivingEntity target = resolveComboTarget(targetId, boss);
        if (target == null) {
            finishUnarmedCombo(UNARMED_COMBO_FAIL_COOLDOWN_MS);
            return;
        }

        boss.setTarget(target);

        if (hitIndex >= UNARMED_COMBO_FLURRY_HITS) {
            executeUnarmedComboFinisher(boss, target);
            finishUnarmedCombo(nextUnarmedComboCooldown());
            return;
        }

        double dist = boss.getLocation().distance(target.getLocation());
        if (dist <= 3.6) {
            target.damage(2.2, boss);
            Location hitFx = target.getLocation().clone().add(0, 1.0, 0);
            target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, hitFx, 4, 0.25, 0.2, 0.25, 0.01);
            target.getWorld().spawnParticle(Particle.CRIT, hitFx, 8, 0.28, 0.22, 0.28, 0.04);
            target.getWorld().playSound(hitFx, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.25f);
            target.getWorld().playSound(hitFx, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.55f, 1.5f);
        } else {
            Vector chase = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0.0);
            if (chase.lengthSquared() > 0.0001) {
                boss.setVelocity(chase.normalize().multiply(0.52).setY(0.0));
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> doUnarmedComboFlurry(targetId, hitIndex + 1), 4L);
    }

    private void executeUnarmedComboFinisher(Zombie boss, LivingEntity target) {
        if (boss == null || target == null || target.isDead() || !target.isValid())
            return;

        Vector knock = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0.0);
        if (knock.lengthSquared() < 0.0001) {
            knock = new Vector(0, 0, 1);
        }
        knock.normalize();

        target.damage(6.0, boss);
        target.setVelocity(knock.multiply(1.55).setY(0.45));

        Location fx = target.getLocation().clone().add(0, 0.9, 0);
        target.getWorld().spawnParticle(Particle.EXPLOSION, fx, 2, 0.25, 0.18, 0.25, 0.02);
        target.getWorld().spawnParticle(Particle.CLOUD, fx, 20, 0.45, 0.25, 0.45, 0.03);
        target.getWorld().spawnParticle(Particle.CRIT, fx, 14, 0.35, 0.24, 0.35, 0.08);
        target.getWorld().playSound(fx, Sound.ENTITY_GENERIC_EXPLODE, 0.85f, 1.2f);
        target.getWorld().playSound(fx, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
    }

    private LivingEntity resolveComboTarget(UUID targetId, Zombie boss) {
        if (boss == null)
            return null;

        if (targetId != null) {
            Entity direct = Bukkit.getEntity(targetId);
            if (direct instanceof LivingEntity living
                    && isValidBossCombatTarget(living)
                    && living.getWorld().getUID().equals(boss.getWorld().getUID())) {
                return living;
            }
        }

        return pickCombatTarget(boss);
    }

    private boolean isBossUnarmed(Zombie boss) {
        if (boss == null)
            return false;
        EntityEquipment eq = boss.getEquipment();
        if (eq == null)
            return true;
        ItemStack hand = eq.getItemInMainHand();
        return hand == null || hand.getType().isAir();
    }

    private void finishUnarmedCombo(long cooldownMs) {
        cancelUnarmedComboDashTask();
        unarmedComboActive = false;
        dashPreparing = false;
        long cd = Math.max(UNARMED_COMBO_FAIL_COOLDOWN_MS, cooldownMs);
        nextUnarmedComboAtMillis = System.currentTimeMillis() + cd;
    }

    private long nextUnarmedComboCooldown() {
        return UNARMED_COMBO_COOLDOWN_MIN_MS + (long) rng.nextInt((int) UNARMED_COMBO_COOLDOWN_RANDOM_MS + 1);
    }

    private void cancelUnarmedComboDashTask() {
        if (unarmedComboDashTask != null) {
            unarmedComboDashTask.cancel();
            unarmedComboDashTask = null;
        }
    }

    private void equipBossWeaponIfNeeded(Zombie boss) {
        if (boss == null || weaponDrawn)
            return;

        double max = getBossMaxHealth(boss);
        if (boss.getHealth() > max * 0.5)
            return;

        EntityEquipment eq = boss.getEquipment();
        if (eq == null)
            return;

        eq.setItemInMainHand(new ItemStack(org.bukkit.Material.DIAMOND_SWORD));
        eq.setItemInMainHandDropChance(0f);
        weaponDrawn = true;
        sendBossMessage("Теперь по-настоящему.");
    }

    private double getBossMaxHealth(Zombie boss) {
        Attribute maxHealthAttr = getAttribute("generic.max_health", "max_health");
        if (maxHealthAttr == null)
            return BOSS_HP;
        AttributeInstance maxHealth = boss.getAttribute(maxHealthAttr);
        if (maxHealth == null)
            return BOSS_HP;
        return maxHealth.getValue();
    }

    private void setBossSpeed(Zombie boss, double value) {
        if (boss == null)
            return;
        Attribute speedAttr = getAttribute("generic.movement_speed", "movement_speed");
        if (speedAttr == null)
            return;
        AttributeInstance speed = boss.getAttribute(speedAttr);
        if (speed != null)
            speed.setBaseValue(value);
    }

    private void setBossDamage(Zombie boss, double value) {
        if (boss == null)
            return;
        Attribute attackAttr = getAttribute("generic.attack_damage", "attack_damage");
        if (attackAttr == null)
            return;
        AttributeInstance dmg = boss.getAttribute(attackAttr);
        if (dmg != null)
            dmg.setBaseValue(value);
    }

    private boolean isGravityCrushChanneling(UUID playerUuid) {
        if (playerUuid == null || plugin.skills() == null)
            return false;
        if (!(plugin.skills().getSkill("gravity_crush") instanceof GravityCrushSkill crush))
            return false;
        return crush.isChanneling(playerUuid);
    }

    private void cancelTask(BukkitTask task) {
        if (task != null)
            task.cancel();
    }

    private Attribute getAttribute(String... keys) {
        for (String k : keys) {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k));
            if (a != null)
                return a;
        }
        return null;
    }

    // ============================================
    // MULTI-PLAYER ASSIST MECHANICS
    // ============================================

    private void startAssistCheck() {
        assistCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            checkAllyStatus();
            handleAssistLogic();
        }, 20L, 20L); // Check every second
    }

    private void handleAssistLogic() {
        Zombie boss = getBoss();
        if (boss == null || boss.isDead())
            return;

        // Check if boss HP < 20% (skill disabled)
        double maxHp = getBossMaxHealth(boss);
        double currentHp = boss.getHealth();
        if (currentHp / maxHp < 0.20)
            return;

        // Check cooldown (5-8 seconds instead of 12-18 for faster reaction)
        long now = System.currentTimeMillis();
        if (now - lastForceSeparationTime < 5000)
            return;

        // Find players attacking near boss (within 8 blocks for better detection)
        List<Player> nearbyPlayers = new ArrayList<>();

        for (Entity entity : boss.getNearbyEntities(8, 8, 8)) {
            if (!(entity instanceof Player p))
                continue;
            if (!isDuelPlayer(p.getUniqueId()))
                continue;
            if (!p.isOnline() || p.isDead())
                continue;

            // Check if player recently attacked (within last 3 seconds)
            Long lastHit = warnCooldown.get(p.getUniqueId());
            if (lastHit != null && now - lastHit < 3000) {
                nearbyPlayers.add(p);
            }
        }

        // If 2+ players are attacking, trigger force separation
        if (nearbyPlayers.size() >= 2) {
            Player stronger = getStrongerPlayer(nearbyPlayers.get(0), nearbyPlayers.get(1));
            Player weaker = stronger.equals(nearbyPlayers.get(0)) ? nearbyPlayers.get(1) : nearbyPlayers.get(0);

            executeForceSeparation(boss, stronger, weaker);

            // Set cooldown (5-8 seconds random for faster reaction)
            lastForceSeparationTime = now + rng.nextInt(3000);
        }
    }

    private void executeForceSeparation(Zombie boss, Player stronger, Player weaker) {
        if (boss == null || stronger == null || weaker == null)
            return;

        // Removed boss message

        // === EFFECT ON STRONGER PLAYER ===

        // 1. Short stun (0.5s = 10 ticks)
        stronger.setVelocity(new Vector(0, 0, 0));
        stronger.setWalkSpeed(0.0f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (stronger.isOnline()) {
                stronger.setWalkSpeed(0.2f);
            }
        }, 10L);

        // 2. Strong knockback (12-18 blocks)
        Vector awayFromBoss = stronger.getLocation().toVector()
                .subtract(boss.getLocation().toVector())
                .normalize()
                .multiply(2.5 + rng.nextDouble() * 1.0);
        awayFromBoss.setY(0.6);
        stronger.setVelocity(awayFromBoss);

        // 3. Prevent sprint + attack for 2 seconds (40 ticks)
        stronger.setSprinting(false);
        long debuffEnd = System.currentTimeMillis() + 2000;
        playerDebuffExpiry.put(stronger.getUniqueId(), debuffEnd);

        // Remove debuff after 2 seconds
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            playerDebuffExpiry.remove(stronger.getUniqueId());
        }, 40L);

        // === EFFECT ON WEAKER PLAYER ===

        // 1. Boss switches target
        boss.setTarget(weaker);

        // 2. Boss teleports behind weaker player
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            if (boss.isDead() || !weaker.isOnline())
                return;

            teleportBossBehindPlayer(weaker, boss);

            // 3. Boss attacks weaker player
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!active || stage != Stage.FIGHT)
                    return;
                if (!boss.isDead() && weaker.isOnline()) {
                    boss.attack(weaker);
                }
            }, 5L);
        }, 15L);

        // Update ally reference
        if (allyPlayerUuid == null || !allyPlayerUuid.equals(weaker.getUniqueId())) {
            allyPlayerUuid = weaker.getUniqueId();
        }
    }

    private Player getStrongerPlayer(Player p1, Player p2) {
        if (p1 == null)
            return p2;
        if (p2 == null)
            return p1;

        // Compare by main duel player (primary target is stronger)
        if (duelPlayerUuid != null && duelPlayerUuid.equals(p1.getUniqueId())) {
            return p1;
        }
        if (duelPlayerUuid != null && duelPlayerUuid.equals(p2.getUniqueId())) {
            return p2;
        }

        // Compare by kit level
        KitManager.Kit kit1 = plugin.progress().getSavedKit(p1.getUniqueId());
        KitManager.Kit kit2 = plugin.progress().getSavedKit(p2.getUniqueId());

        int level1 = plugin.progress().getKitLevel(p1.getUniqueId(), kit1);
        int level2 = plugin.progress().getKitLevel(p2.getUniqueId(), kit2);

        if (level1 > level2)
            return p1;
        if (level2 > level1)
            return p2;

        // Compare by player level
        int playerLevel1 = plugin.progress().getPlayerLevel(p1.getUniqueId());
        int playerLevel2 = plugin.progress().getPlayerLevel(p2.getUniqueId());

        return playerLevel1 >= playerLevel2 ? p1 : p2;
    }

    // ============================================
    // ALLY SUMMON MECHANICS
    // ============================================

    public boolean canSummonAlly(Player main) {
        if (!active || stage != Stage.FIGHT)
            return false;
        if (!isDuelPlayer(main.getUniqueId()))
            return false;
        if (!main.getUniqueId().equals(duelPlayerUuid))
            return false; // Only main player
        if (allyUsed)
            return false; // Already used once per duel
        return true;
    }

    public boolean trySummonAlly(Player main, Player ally) {
        if (!canSummonAlly(main)) {
            main.sendMessage("§cНельзя вызвать помощника прямо сейчас.");
            return false;
        }

        if (ally == null || !ally.isOnline() || ally.isDead()) {
            main.sendMessage("§cИгрок офлайн или мёртв.");
            return false;
        }

        if (!ally.getWorld().getUID().equals(main.getWorld().getUID())) {
            main.sendMessage("§cИгрок в другом мире.");
            return false;
        }

        if (allyPlayerUuid != null) {
            main.sendMessage("§cУже один помощник в дуэли.");
            return false;
        }

        // Find safe teleport location for ally
        Location safeLoc = findSafeTeleportLoc(ally);
        if (safeLoc == null) {
            main.sendMessage("§cНет места для помощника рядом.");
            return false;
        }

        // Summon ally
        allyPlayerUuid = ally.getUniqueId();
        allyUsed = true;

        applyCinematicSummon(ally, safeLoc);

        // Boss reacts
        Zombie boss = getBoss();
        if (boss != null) {
            // Removed boss message
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!active || stage != Stage.FIGHT)
                    return;
                bossForceSeparationOnAllyJoin(boss, main, ally);
            }, 30L);
        }

        ally.sendMessage("§eТы вошёл в дуэль как помощник! Помоги главному ритуалисту!");
        main.sendMessage("§eПомощник прибыл!");

        return true;
    }

    private Location findSafeTeleportLoc(Player ally) {
        if (duelCenter == null)
            return null;

        // Try to find safe location 6-10 blocks from main player
        Player main = getDuelPlayer();
        if (main == null)
            return null;

        Location mainLoc = main.getLocation();
        int attempts = 0;
        while (attempts < 10) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = 6.0 + rng.nextDouble() * 4.0;
            Vector offset = new Vector(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            Location testLoc = mainLoc.clone().add(offset);
            testLoc.setY(duelCenter.getY() + 1.0);

            // Check if inside arena
            if (testLoc.distanceSquared(duelCenter) > DUEL_RADIUS * DUEL_RADIUS) {
                attempts++;
                continue;
            }

            // Check if not in solid block
            if (testLoc.getBlock().getType().isSolid()) {
                attempts++;
                continue;
            }

            // Safe location found
            return testLoc;
        }

        return null;
    }

    private void applyCinematicSummon(Player ally, Location safeLoc) {
        internalTeleport = true;
        try {
            // Teleport ally
            ally.teleport(safeLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);

            // Face boss
            if (duelCenter != null) {
                Vector towardsBoss = duelCenter.toVector().subtract(safeLoc.toVector()).normalize();
                float yaw = (float) Math.atan2(-towardsBoss.getX(), towardsBoss.getZ());
                Location lookLoc = safeLoc.clone();
                lookLoc.setYaw(yaw * 180 / (float) Math.PI);
                ally.teleport(lookLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        } finally {
            Bukkit.getScheduler().runTaskLater(plugin, () -> internalTeleport = false, 2L);
        }

        // Freeze for 1 second (cinematic entrance)
        ally.setWalkSpeed(0.0f);
        ally.setSprinting(false);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (ally.isOnline()) {
                ally.setWalkSpeed(0.2f);
            }
        }, 20L);
    }

    private void bossForceSeparationOnAllyJoin(Zombie boss, Player main, Player ally) {
        if (boss == null || boss.isDead())
            return;

        // Determine stronger player
        Player stronger = getStrongerPlayer(main, ally);
        Player weaker = stronger.equals(main) ? ally : main;

        if (!stronger.isOnline() || !weaker.isOnline())
            return;

        // === EFFECT ON STRONGER PLAYER ===

        // 1. Knockback (8-14 blocks)
        Vector awayFromBoss = stronger.getLocation().toVector()
                .subtract(boss.getLocation().toVector())
                .normalize()
                .multiply(2.0 + rng.nextDouble() * 1.5);
        awayFromBoss.setY(0.5);
        stronger.setVelocity(awayFromBoss);

        // 2. Slowness for 1.5 seconds (30 ticks)
        stronger.setWalkSpeed(0.0f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (stronger.isOnline()) {
                stronger.setWalkSpeed(0.2f);
            }
        }, 30L);

        // 3. Prevent attacks for 1.5 seconds
        long debuffEnd = System.currentTimeMillis() + 1500;
        playerDebuffExpiry.put(stronger.getUniqueId(), debuffEnd);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            playerDebuffExpiry.remove(stronger.getUniqueId());
        }, 30L);

        // === EFFECT ON WEAKER/ALLY PLAYER ===

        // Boss focuses weaker player for ~6 seconds
        boss.setTarget(weaker);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            if (boss.isDead())
                return;
            Player mainDuel = getDuelPlayer();
            if (mainDuel != null && mainDuel.isOnline()) {
                boss.setTarget(mainDuel);
            }
        }, 120L); // 6 seconds
    }

    public void checkAllyStatus() {
        if (!active || allyPlayerUuid == null)
            return;

        Entity allyEntity = Bukkit.getEntity(allyPlayerUuid);
        Player ally = null;
        if (allyEntity instanceof Player p) {
            ally = p;
        }

        // Ally is invalid (dead, offline, left world)
        if (ally == null || !ally.isOnline() || ally.isDead() || ally.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            allyPlayerUuid = null;
            return;
        }

        // Ally left arena
        if (duelCenter != null) {
            if (!ally.getWorld().getUID().equals(duelCenter.getWorld().getUID())) {
                allyPlayerUuid = null;
                return;
            }

            if (ally.getLocation().distanceSquared(duelCenter) > DUEL_RADIUS * DUEL_RADIUS) {
                allyPlayerUuid = null;
                return;
            }
        }
    }

    // ============================================
    // BOSS ADAPTATION MECHANICS
    // ============================================

    private void startAdaptationCheck() {
        adaptationEngine.start();
    }

    private void stopAdaptationCheck() {
        adaptationEngine.stop();
    }

    public void registerSkillUsage(Player player, String skillName) {
        adaptationEngine.registerSkillUsage(player, skillName);
    }

    private BossAdaptationEngine.RecentSkillSnapshot getRecentSkillSnapshot(UUID playerUuid, KitManager.Kit kit) {
        return adaptationEngine.getRecentSkillSnapshot(playerUuid, kit);
    }

    boolean isFightActive() {
        return active && stage == Stage.FIGHT;
    }

    Zombie adaptationBoss() {
        return getBoss();
    }

    void adaptationDodge(Zombie boss, Player player) {
        dodgeBossFromPlayer(boss, player);
    }

    void adaptationBackstep(Zombie boss, Player player) {
        backstepFromPlayer(boss, player);
    }

    void adaptationTeleportBehind(Player player, Zombie boss) {
        teleportBossBehindPlayer(player, boss);
    }

    void adaptationSummonerShockwave(Zombie boss, int tier) {
        triggerSummonerCounterShockwave(boss, tier);
    }

    void announceSkillAdaptation(Player player, String skillName, int tier) {
        sendSkillAdaptationMessage(player, skillName, tier);
    }
}
