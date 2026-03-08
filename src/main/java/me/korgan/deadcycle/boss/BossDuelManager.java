package me.korgan.deadcycle.boss;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
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
import org.bukkit.entity.Player;
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

    private final NamespacedKey bossKey;
    private final NamespacedKey minionKey;

    private boolean bossSpawning = false;

    private boolean active = false;
    private Stage stage = Stage.NONE;
    private int duelDay = -1;

    private Location duelCenter;
    private UUID duelPlayerUuid;
    private UUID bossUuid;
    private final List<UUID> minionUuids = new ArrayList<>();

    private Location lastInsideLocation;

    private BukkitTask freezeTask;
    private BukkitTask lookTask;
    private BukkitTask barrierTask;
    private BukkitTask tauntTask;
    private BukkitTask targetTask;
    private BukkitTask dodgeTask;
    private BukkitTask mobilityTask;
    private BukkitTask bossClampTask;
    private BukkitTask zombieCleanerTask;
    private BukkitTask phaseTask;
    private BukkitTask regenTask;
    private BukkitTask bossBarTask;
    private BukkitTask helpItemTask;

    private long fightStartTime = 0L;
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
    private final Map<UUID, Long> playerDebuffExpiry = new HashMap<>();
    private BukkitTask assistCheckTask;

    // Boss adaptation mechanics
    private final Map<UUID, Map<String, SkillUsageInfo>> playerSkillTracking = new HashMap<>();
    private final Map<UUID, Long> skillCommentCooldown = new HashMap<>();
    private BukkitTask adaptationCheckTask;

    private static class SkillUsageInfo {
        long lastUseTime;
        int useCount;
        boolean commentedFirstUse;

        SkillUsageInfo() {
            this.lastUseTime = 0L;
            this.useCount = 0;
            this.commentedFirstUse = false;
        }
    }

    private static final String BOSS_NAME = "§5§l[?????]";
    private static final String BOSS_PREFIX = "§5§l[?????] §d";

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

    private int dashCooldownTicks;
    private int dashWindupTicks;
    private double dashSpeed;
    private double dodgeSlideMin;
    private double dodgeSlideMax;
    private double backstepSlideMin;
    private double backstepSlideMax;

    private long nextDashAtMillis = 0L;
    private boolean dashPreparing = false;

    public BossDuelManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
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
        World baseWorld = plugin.base().getCenter().getWorld();
        if (baseWorld == null) {
            plugin.getLogger().info("[BossDuel] Мир базы = null");
            return;
        }

        plugin.getLogger().info("[BossDuel] Ночь " + dayCount + ": поиск дуэлянта...");
        Player duelPlayer = selectDuelist(baseWorld);
        if (duelPlayer == null) {
            plugin.getLogger().info("[BossDuel] Дуэлянт не найден.");
            return;
        }

        plugin.getLogger().info("[BossDuel] Дуэлянт найден: " + duelPlayer.getName() + ". Поиск локации спавна...");
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
        plugin.getLogger().info("[BossDuel] Выбран дуэлянт: " + selected.getName());
        return selected;
    }

    private void startDuel(Player duelPlayer, Location bossSpawn) {
        active = true;
        stage = Stage.INIT;
        duelCenter = bossSpawn.clone();
        duelPlayerUuid = duelPlayer.getUniqueId();
        minionUuids.clear();
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
        fightStartTime = System.currentTimeMillis();
        helpItemGiven = false;
        dashPreparing = false;
        nextDashAtMillis = System.currentTimeMillis() + (long) dashCooldownTicks * 50L;

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

        startTaunts();
        startTargetScan();
        // startDodge(); // Отключено - startMobility уже обеспечивает плавное движение
        startMobility();
        startBossClamp();
        startZombieCleaner();
        startPhaseMonitor();
        startRegen();
        startAssistCheck();
        startAdaptationCheck();
    }

    private void startTaunts() {
        // Removed taunts
    }

    private void startTargetScan() {
        targetTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            Player p = getDuelPlayer();
            Zombie boss = getBoss();
            if (p == null || boss == null)
                return;
            boss.setTarget(p);
        }, 20L, 20L * 2L);
    }

    private void startDodge() {
        // Случайные перемещения босса для подвижности (редко, раз в 5-7 секунд)
        dodgeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            if (rng.nextDouble() > 0.15)
                return; // Снизили шанс, т.к. есть реактивные уклонения

            Zombie boss = getBoss();
            Player p = getDuelPlayer();
            if (boss == null || p == null)
                return;

            double distToPlayer = boss.getLocation().distance(p.getLocation());
            if (distToPlayer < 3.2 && rng.nextDouble() < 0.4) {
                backstepFromPlayer(boss, p);
                return;
            }

            // Небольшое перемещение в сторону игрока или по кругу
            Vector toPlayer = p.getLocation().toVector().subtract(boss.getLocation().toVector()).normalize();
            Vector sideStep = toPlayer.clone().rotateAroundY(rng.nextBoolean() ? Math.PI / 3 : -Math.PI / 3);

            double dist = 2 + rng.nextDouble();
            Location target = boss.getLocation().clone().add(sideStep.multiply(dist));
            target.setY(boss.getLocation().getY());

            if (duelCenter != null && target.getWorld() != null) {
                if (target.distanceSquared(duelCenter) > (DUEL_RADIUS - 3) * (DUEL_RADIUS - 3)) {
                    return;
                }
            }

            boss.teleport(target);
        }, 20L * 5L, 20L * (5L + rng.nextInt(3))); // 5-7 секунд
    }

    private void startMobility() {
        mobilityTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;

            Zombie boss = getBoss();
            Player p = getDuelPlayer();
            if (boss == null || p == null || boss.isDead())
                return;

            // Включаем AI и даём цель — пусть сам идёт по навигации
            boss.setAI(true);
            boss.setTarget(p);

            long now = System.currentTimeMillis();
            if (dashPreparing || now < nextDashAtMillis)
                return;

            if (!boss.isOnGround())
                return;

            double dist = boss.getLocation().distance(p.getLocation());
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
                Player target = getDuelPlayer();
                if (currentBoss == null || target == null || currentBoss.isDead())
                    return;

                Vector dir = target.getLocation().toVector().subtract(currentBoss.getLocation().toVector()).setY(0);
                if (dir.lengthSquared() < 0.0001)
                    return;
                dir.normalize();

                Vector slide = dir.multiply(dashSpeed).setY(0.0);
                if (duelCenter != null
                        && currentBoss.getLocation().distanceSquared(duelCenter) > (DUEL_RADIUS - 3) * (DUEL_RADIUS - 3)) {
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
                if (z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
                    continue;
                if (z.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE))
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
        cancelTask(tauntTask);
        cancelTask(targetTask);
        cancelTask(dodgeTask);
        cancelTask(mobilityTask);
        cancelTask(bossClampTask);
        cancelTask(zombieCleanerTask);
        cancelTask(phaseTask);
        cancelTask(regenTask);
        cancelTask(bossBarTask);
        cancelTask(helpItemTask);
        cancelTask(assistCheckTask);
        cancelTask(adaptationCheckTask);

        restoreArena();

        fightStartTime = 0L;
        helpItemGiven = false;
        allyPlayerUuid = null;
        allyUsed = false;
        playerDebuffExpiry.clear();
        lastForceSeparationTime = 0L;

        Zombie boss = getBoss();
        if (boss != null && !boss.isDead()) {
            boss.setVelocity(new Vector(0, 0, 0)); // Очистим velocity босса
            boss.remove();
        }

        for (UUID id : minionUuids) {
            Entity ent = Bukkit.getEntity(id);
            if (ent != null)
                ent.remove();
        }
        minionUuids.clear();

        // Телепортируем игрока обратно на базу
        Player duelPlayer = duelPlayerUuid != null ? Bukkit.getPlayer(duelPlayerUuid) : null;
        if (duelPlayer != null && duelPlayer.isOnline()) {
            Location baseLocation = plugin.base() != null ? plugin.base().getCenter() : null;
            if (baseLocation != null) {
                duelPlayer.teleport(baseLocation);
            } else if (lastInsideLocation != null) {
                duelPlayer.teleport(lastInsideLocation);
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
        stage = Stage.NONE;
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

            double maxHealth = boss.getAttribute(Attribute.MAX_HEALTH).getValue();
            double currentHealth = boss.getHealth();
            int hpInt = (int) Math.round(currentHealth);
            int maxHpInt = (int) Math.round(maxHealth);

            String hpText = "§5§l[?????] §d" + hpInt + "/" + maxHpInt;
            boss.setCustomName(hpText);
        }, 0L, 2L);
    }

    private void sendBossMessage(String text) {
        // Removed all boss messages
    }

    private void sendSkillAdaptationMessage() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(BOSS_PREFIX + "...");
        }
    }

    private void spawnBoss(Location loc) {
        World w = loc.getWorld();
        if (w == null)
            return;

        try {
            bossSpawning = true;
            Zombie boss = w.spawn(loc, Zombie.class, z -> {
                z.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
                z.setCustomName(BOSS_NAME);
                z.setCustomNameVisible(true);
                z.setRemoveWhenFarAway(false);
                z.setPersistent(true);
                z.setBaby(false);
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

                // Без ускорения - идет пафосно
            });

            bossUuid = boss.getUniqueId();
        } finally {
            bossSpawning = false;
        }
    }

    private void spawnMinions(Location bossLoc) {
        World w = bossLoc.getWorld();
        if (w == null)
            return;

        int count = 2 + rng.nextInt(2);
        for (int i = 0; i < count; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = 3 + rng.nextDouble() * 2;
            Location loc = bossLoc.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            loc.setY(bossLoc.getY());

            try {
                bossSpawning = true;
                Zombie z = w.spawn(loc, Zombie.class, mob -> {
                    mob.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
                    mob.setBaby(false);
                    mob.setCanPickupItems(false);
                });
                minionUuids.add(z.getUniqueId());
            } finally {
                bossSpawning = false;
            }
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
            if (z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
                continue;
            if (z.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE))
                continue;
            if (z.getLocation().distanceSquared(center) <= r2)
                z.remove();
        }
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
                if (platformBlock.getType().isEmpty() || platformBlock.getType() == org.bukkit.Material.WATER) {
                    recordArenaBlock(w, x, platformHeight, z);
                    platformBlock.setType(org.bukkit.Material.STONE);
                }
            }
        }

        // Убираем ближайшие зомби со всей арены
        double clearRadius = arenaRadius + 5.0;
        Location centerLoc = new Location(w, centerX + 0.5, platformHeight + 1.0, centerZ + 0.5);
        for (Entity ent : w.getNearbyEntities(centerLoc, clearRadius, 20, clearRadius)) {
            if (ent instanceof Zombie && !ent.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) {
                ent.remove();
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
            if (e.getTo() == null)
                return;
            if (e.getTo().getWorld() != duelCenter.getWorld())
                return;

            double distSq = e.getTo().distanceSquared(duelCenter);
            if (distSq <= DUEL_RADIUS * DUEL_RADIUS) {
                lastInsideLocation = e.getTo().clone();
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
        if (!isDuelPlayer(e.getPlayer().getUniqueId()))
            return;
        if (internalTeleport)
            return;
        // Не считать побегом во время инициализации
        if (stage == Stage.INIT || stage == Stage.FREEZE)
            return;

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
        if (main != null && main.isOnline() && !main.isDead()) {
            count++;
        }
        if (allyPlayerUuid != null) {
            Player ally = Bukkit.getPlayer(allyPlayerUuid);
            if (ally != null && ally.isOnline() && !ally.isDead()) {
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
            plugin.progress().addPlayerExp(killer, 20);
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

        Player duelPlayer = getDuelPlayer();
        if (duelPlayer == null)
            return;

        if (e.getTarget() == null || !e.getTarget().getUniqueId().equals(duelPlayer.getUniqueId())) {
            e.setCancelled(true);
            z.setTarget(duelPlayer);
        }
    }

    @EventHandler
    public void onBossDamaged(EntityDamageByEntityEvent e) {
        if (!active || stage != Stage.FIGHT)
            return;
        if (!(e.getEntity() instanceof Zombie z))
            return;
        if (!z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE))
            return;

        if (!(e.getDamager() instanceof Player p))
            return;
        if (!isDuelPlayer(p.getUniqueId()))
            return;

        // Track last attack time for assist logic
        warnCooldown.put(p.getUniqueId(), System.currentTimeMillis());

        equipBossWeaponIfNeeded(z);
        Bukkit.getScheduler().runTaskLater(plugin, () -> equipBossWeaponIfNeeded(getBoss()), 1L);

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

        double dodgeChance = rageMode ? 0.30 : (seriousMode ? 0.50 : (interestMode ? 0.30 : 0.10));
        double counterChance = rageMode ? 0.55 : (seriousMode ? 0.35 : (interestMode ? 0.25 : 0.15));

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
        if (main != null && main.isOnline() && !main.isDead()) {
            players.add(main);
        }
        if (ally != null && ally.isOnline() && !ally.isDead()) {
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

        ally.sendMessage("§eТы вошёл в дуэль как помощник! Помоги главному дуэлянту!");
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
        // Teleport ally
        ally.teleport(safeLoc);

        // Face boss
        if (duelCenter != null) {
            Vector towardsBoss = duelCenter.toVector().subtract(safeLoc.toVector()).normalize();
            float yaw = (float) Math.atan2(-towardsBoss.getX(), towardsBoss.getZ());
            Location lookLoc = safeLoc.clone();
            lookLoc.setYaw(yaw * 180 / (float) Math.PI);
            ally.teleport(lookLoc);
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
        if (ally == null || !ally.isOnline() || ally.isDead()) {
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
        adaptationCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT)
                return;
            checkPlayerSkillUsage();
        }, 20L, 5L); // Check every 0.25 seconds
    }

    public void registerSkillUsage(Player player, String skillName) {
        if (!isDuelPlayer(player.getUniqueId()))
            return;

        UUID playerUuid = player.getUniqueId();
        playerSkillTracking.putIfAbsent(playerUuid, new HashMap<>());

        Map<String, SkillUsageInfo> playerSkills = playerSkillTracking.get(playerUuid);
        SkillUsageInfo info = playerSkills.getOrDefault(skillName, new SkillUsageInfo());

        long now = System.currentTimeMillis();
        boolean isFirstUse = !info.commentedFirstUse;
        boolean isSpam = info.lastUseTime > 0 && (now - info.lastUseTime) < 3000; // 3 sec cooldown

        info.lastUseTime = now;
        info.useCount++;
        playerSkills.put(skillName, info);

        // Get player kit
        KitManager.Kit playerKit = plugin.progress().getSavedKit(playerUuid);

        // Boss comments on skill usage
        if (isFirstUse) {
            info.commentedFirstUse = true;
            commentFirstSkillUse(player, skillName, playerKit);
        } else if (isSpam && info.useCount > 1) {
            commentSkillSpam(player, skillName, playerKit);
        }

        // Counter mechanics based on kit
        if (isSpam) {
            applySkillCounterMechanic(player, skillName, playerKit);
        }
    }

    private void commentFirstSkillUse(Player player, String skillName, KitManager.Kit kit) {
        sendSkillAdaptationMessage();
    }

    private void commentSkillSpam(Player player, String skillName, KitManager.Kit kit) {
        // Check cooldown for spam comments (don't spam chat)
        Long lastComment = skillCommentCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (System.currentTimeMillis() - lastComment < 5000)
            return; // 5 sec cooldown between comments

        skillCommentCooldown.put(player.getUniqueId(), System.currentTimeMillis());
        sendSkillAdaptationMessage();
    }

    private void applySkillCounterMechanic(Player player, String skillName, KitManager.Kit kit) {
        Zombie boss = getBoss();
        if (boss == null || boss.isDead())
            return;

        long now = System.currentTimeMillis();
        if (now - lastForceSeparationTime < 5000)
            return; // Don't spam counters

        switch (kit) {
            case GRAVITATOR -> {
                // Gravitator uses gravity/slowness → Boss increases dodge chance and teleports
                // away
                if (rng.nextDouble() < 0.7) {
                    dodgeBossFromPlayer(boss, player);
                }
            }

            case ARCHER -> {
                // Archer uses ranged attacks → Boss teleports to archer and attacks
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!active || stage != Stage.FIGHT)
                        return;
                    if (boss.isDead() || !player.isOnline())
                        return;

                    teleportBossBehindPlayer(player, boss);
                    boss.attack(player);
                }, 5L);
            }

            case BERSERK -> {
                // Berserk rushes in → Boss applies knockback and repositions
                Vector awayFromBoss = player.getLocation().toVector()
                        .subtract(boss.getLocation().toVector())
                        .normalize()
                        .multiply(1.8);
                awayFromBoss.setY(0.3);
                player.setVelocity(awayFromBoss);
            }

            case FIGHTER -> {
                // Fighter uses melee combos → Boss backsteps and keeps distance
                backstepFromPlayer(boss, player);
            }

            case MINER -> {
                // Miner uses mining-related skills → Boss ignores and continues
                // No specific counter, just comment
            }

            case BUILDER -> {
                // Builder uses structures → Boss destroys them or ignores
            }
        }

        lastForceSeparationTime = now;
    }

    private void checkPlayerSkillUsage() {
        // Cleanup old skill tracking (more than 30 seconds of no usage)
        long now = System.currentTimeMillis();
        for (UUID playerUuid : new ArrayList<>(playerSkillTracking.keySet())) {
            Map<String, SkillUsageInfo> skills = playerSkillTracking.get(playerUuid);
            skills.entrySet().removeIf(entry -> (now - entry.getValue().lastUseTime) > 30000);
        }
    }
}
