package me.korgan.deadcycle.boss;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
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
    private BukkitTask bossClampTask;

    private boolean internalTeleport = false;

    private final Map<UUID, Long> warnCooldown = new HashMap<>();

    private int recentHitCount = 0;
    private long lastHitAt = 0L;

    private static final String BOSS_NAME = "§5§l[?????]";
    private static final String BOSS_PREFIX = "§5§l[?????] §d";

    private static final int DUEL_RADIUS = 50;
    private static final int MIN_DISTANCE_FROM_BASE = 120;
    private static final int MAX_DISTANCE_FROM_BASE = 220;
    private static final int FREEZE_TICKS = 20 * 5;

    private static final int BOSS_HP = 160;
    private static final double BOSS_DAMAGE = 8.0;
    private static final double BOSS_SPEED = 0.34;
    private static final double BOSS_FOLLOW_RANGE = 64.0;

    private static final String[] INTRO_LINES = new String[] {
            "Ты уверен, что хочешь этого?",
            "Я ждал достойного... но ты слаб.",
            "Это будет быстро. Для тебя."
    };

    private static final String[] TAUNT_LINES = new String[] {
            "Слабее, чем я думал.",
            "Не позорься.",
            "Смотри в глаза.",
            "Ты дрожишь?",
            "Еще шаг."
    };

    private static final String[] DAMAGE_LINES = new String[] {
            "О, уже лучше.",
            "Вот это удар.",
            "Неплохо."
    };

    private static final String[] WIN_LINES = new String[] {
            "Слишком легко.",
            "Ты не заслужил жить.",
            "Разочарование."
    };

    private static final String[] ESCAPE_LINES = new String[] {
            "Сбежал... даже не заслужил.",
            "Трусость — твой стиль.",
            "Не уходи так быстро."
    };

    public BossDuelManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "boss_duel_boss");
        this.minionKey = new NamespacedKey(plugin, "boss_duel_minion");
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
        return duelPlayerUuid != null && duelPlayerUuid.equals(uuid);
    }

    public boolean isInsideDuelZone(Location loc, double extra) {
        if (!active || duelCenter == null || loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getUID().equals(duelCenter.getWorld().getUID())) return false;
        double r = DUEL_RADIUS + Math.max(0.0, extra);
        return loc.distanceSquared(duelCenter) <= r * r;
    }

    public void trySpawnBoss(int dayCount) {
        if (active || dayCount < 10) return;
        if (duelDay == dayCount) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        if (plugin.base() == null || !plugin.base().isEnabled()) return;
        World baseWorld = plugin.base().getCenter().getWorld();
        if (baseWorld == null) return;

        Player duelPlayer = selectDuelist(baseWorld);
        if (duelPlayer == null) return;

        Location spawn = findBossSpawn(baseWorld);
        if (spawn == null) return;

        duelDay = dayCount;
        startDuel(duelPlayer, spawn);
    }

    public void forceEnd(String reason) {
        if (!active && stage == Stage.NONE) return;
        endDuel(reason, false, false);
    }

    private Player selectDuelist(World baseWorld) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline() || p.isDead()) continue;
            if (p.getWorld() != baseWorld) continue;
            if (!plugin.base().isOnBase(p.getLocation())) continue;
            candidates.add(p);
        }
        if (candidates.isEmpty()) return null;

        candidates.sort((a, b) -> {
            int lvlA = plugin.progress().getPlayerLevel(a.getUniqueId());
            int lvlB = plugin.progress().getPlayerLevel(b.getUniqueId());
            if (lvlA != lvlB) return Integer.compare(lvlB, lvlA);

            long moneyA = plugin.econ().getMoney(a.getUniqueId());
            long moneyB = plugin.econ().getMoney(b.getUniqueId());
            if (moneyA != moneyB) return Long.compare(moneyB, moneyA);

            KitManager.Kit kitA = plugin.kit().getKit(a.getUniqueId());
            KitManager.Kit kitB = plugin.kit().getKit(b.getUniqueId());
            int kitLvlA = plugin.progress().getKitLevel(a.getUniqueId(), kitA);
            int kitLvlB = plugin.progress().getKitLevel(b.getUniqueId(), kitB);
            if (kitLvlA != kitLvlB) return Integer.compare(kitLvlB, kitLvlA);

            return 0;
        });

        return candidates.get(0);
    }

    private void startDuel(Player duelPlayer, Location bossSpawn) {
        active = true;
        stage = Stage.INIT;
        duelCenter = bossSpawn.clone();
        duelPlayerUuid = duelPlayer.getUniqueId();
        minionUuids.clear();
        lastInsideLocation = duelPlayer.getLocation().clone();

        removeNearbyZombies(bossSpawn, DUEL_RADIUS + 8);

        spawnBoss(bossSpawn);
        spawnMinions(bossSpawn);

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
        internalTeleport = false;

        faceEachOther(duelPlayer, bossLoc);

        Zombie boss = getBoss();
        if (boss != null) {
            boss.teleport(bossLoc);
            faceEntity(boss, duelPlayer.getLocation());
        }
    }

    private void startFreezePhase(Player duelPlayer) {
        stage = Stage.FREEZE;

        sendBossMessage(randomLine(INTRO_LINES));

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
            if (!active || stage != Stage.FREEZE) return;
            Player p = getDuelPlayer();
            Zombie boss = getBoss();
            if (p == null || boss == null) return;
            faceEntity(p, boss.getLocation());
        }, 0L, 3L);
    }

    private void startFightPhase() {
        stage = Stage.FIGHT;

        if (lookTask != null) {
            lookTask.cancel();
            lookTask = null;
        }

        Player p = getDuelPlayer();
        Zombie boss = getBoss();
        if (p == null || boss == null) {
            endDuel("missing_entity", false, false);
            return;
        }

        teleportBossBehindPlayer(p, boss);
        boss.setTarget(p);

        startTaunts();
        startTargetScan();
        startDodge();
        startBossClamp();
    }

    private void startTaunts() {
        tauntTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT) return;
            sendBossMessage(randomLine(TAUNT_LINES));
        }, 20L * 8L, 20L * (8L + rng.nextInt(8)));
    }

    private void startTargetScan() {
        targetTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT) return;
            Player p = getDuelPlayer();
            Zombie boss = getBoss();
            if (p == null || boss == null) return;
            boss.setTarget(p);
        }, 20L, 20L * 2L);
    }

    private void startDodge() {
        dodgeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || stage != Stage.FIGHT) return;
            if (rng.nextDouble() > 0.3) return;

            Zombie boss = getBoss();
            if (boss == null) return;

            Vector dir = new Vector(rng.nextDouble() - 0.5, 0, rng.nextDouble() - 0.5).normalize();
            double dist = 2 + rng.nextDouble();
            Location target = boss.getLocation().clone().add(dir.multiply(dist));

            if (duelCenter != null && target.getWorld() != null) {
                if (target.distanceSquared(duelCenter) > (DUEL_RADIUS - 2) * (DUEL_RADIUS - 2)) {
                    return;
                }
            }

            boss.teleport(target);
        }, 20L * 2L, 20L * 2L);
    }

    private void startBossClamp() {
        bossClampTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || duelCenter == null) return;
            Zombie boss = getBoss();
            if (boss == null) return;
            if (boss.getWorld() != duelCenter.getWorld()) return;

            double distSq = boss.getLocation().distanceSquared(duelCenter);
            if (distSq <= DUEL_RADIUS * DUEL_RADIUS) return;

            Vector back = duelCenter.toVector().subtract(boss.getLocation().toVector()).normalize();
            Location newLoc = boss.getLocation().clone().add(back.multiply(2.0));
            boss.teleport(newLoc);
        }, 20L, 10L);
    }

    private void startBarrierEffects() {
        barrierTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || duelCenter == null) return;
            World w = duelCenter.getWorld();
            if (w == null) return;

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
        cancelTask(bossClampTask);

        Zombie boss = getBoss();
        if (boss != null && !boss.isDead()) boss.remove();

        for (UUID id : minionUuids) {
            Entity ent = Bukkit.getEntity(id);
            if (ent != null) ent.remove();
        }
        minionUuids.clear();

        duelCenter = null;
        duelPlayerUuid = null;
        bossUuid = null;
        lastInsideLocation = null;

        recentHitCount = 0;
        lastHitAt = 0L;
        stage = Stage.NONE;
    }

    private void sendBossMessage(String text) {
        Bukkit.broadcastMessage(BOSS_PREFIX + text);
    }

    private String randomLine(String[] lines) {
        return lines[rng.nextInt(lines.length)];
    }

    private void spawnBoss(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;

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
                    eq.setItemInMainHand(new ItemStack(org.bukkit.Material.DIAMOND_SWORD));

                    eq.setHelmetDropChance(0f);
                    eq.setChestplateDropChance(0f);
                    eq.setLeggingsDropChance(0f);
                    eq.setBootsDropChance(0f);
                    eq.setItemInMainHandDropChance(0f);
                }

                Attribute maxHealthAttr = getAttribute("generic.max_health", "max_health");
                AttributeInstance maxHealth = (maxHealthAttr != null) ? z.getAttribute(maxHealthAttr) : null;
                if (maxHealth != null) maxHealth.setBaseValue(BOSS_HP);
                z.setHealth(Math.min(BOSS_HP, z.getHealth()));

                Attribute attackAttr = getAttribute("generic.attack_damage", "attack_damage");
                AttributeInstance dmg = (attackAttr != null) ? z.getAttribute(attackAttr) : null;
                if (dmg != null) dmg.setBaseValue(BOSS_DAMAGE);

                Attribute speedAttr = getAttribute("generic.movement_speed", "movement_speed");
                AttributeInstance speed = (speedAttr != null) ? z.getAttribute(speedAttr) : null;
                if (speed != null) speed.setBaseValue(BOSS_SPEED);

                Attribute followAttr = getAttribute("generic.follow_range", "follow_range");
                AttributeInstance follow = (followAttr != null) ? z.getAttribute(followAttr) : null;
                if (follow != null) follow.setBaseValue(BOSS_FOLLOW_RANGE);

                PotionEffectType speedEffect = PotionEffectType.SPEED;
                z.addPotionEffect(new PotionEffect(speedEffect, 20 * 60 * 10, 0, false, false));
            });

            bossUuid = boss.getUniqueId();
        } finally {
            bossSpawning = false;
        }
    }

    private void spawnMinions(Location bossLoc) {
        World w = bossLoc.getWorld();
        if (w == null) return;

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
        if (duelPlayerUuid == null) return null;
        return Bukkit.getPlayer(duelPlayerUuid);
    }

    private Zombie getBoss() {
        if (bossUuid == null) return null;
        Entity ent = Bukkit.getEntity(bossUuid);
        if (ent instanceof Zombie z) return z;
        return null;
    }

    private void removeNearbyZombies(Location center, double radius) {
        if (center == null || center.getWorld() == null) return;
        double r2 = radius * radius;
        for (Entity ent : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(ent instanceof Zombie z)) continue;
            if (z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) continue;
            if (z.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE)) continue;
            if (z.getLocation().distanceSquared(center) <= r2) z.remove();
        }
    }

    private Location findBossSpawn(World w) {
        Location base = plugin.base().getCenter();
        if (base == null) return null;

        for (int i = 0; i < 30; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = MIN_DISTANCE_FROM_BASE + rng.nextDouble() * (MAX_DISTANCE_FROM_BASE - MIN_DISTANCE_FROM_BASE);

            int x = (int) Math.round(base.getX() + Math.cos(angle) * dist);
            int z = (int) Math.round(base.getZ() + Math.sin(angle) * dist);

            int y = w.getHighestBlockYAt(x, z) + 1;
            Location loc = new Location(w, x + 0.5, y, z + 0.5);
            if (loc.getBlock().isLiquid()) continue;
            return loc;
        }

        return null;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!active || duelCenter == null) return;
        Player p = e.getPlayer();

        if (stage == Stage.FREEZE && isDuelPlayer(p.getUniqueId())) {
            if (e.getTo() != null && e.getFrom().distanceSquared(e.getTo()) > 0) {
                e.setTo(e.getFrom());
                return;
            }
        }

        if (isDuelPlayer(p.getUniqueId())) {
            if (e.getTo() == null) return;
            if (e.getTo().getWorld() != duelCenter.getWorld()) return;

            double distSq = e.getTo().distanceSquared(duelCenter);
            if (distSq <= DUEL_RADIUS * DUEL_RADIUS) {
                lastInsideLocation = e.getTo().clone();
                return;
            }

            Location back = (lastInsideLocation != null) ? lastInsideLocation : duelCenter.clone();
            internalTeleport = true;
            p.teleport(back);
            internalTeleport = false;
            p.sendMessage("§cТы не можешь покинуть арену дуэли!");
            return;
        }

        if (e.getTo() == null || e.getTo().getWorld() != duelCenter.getWorld()) return;
        double distSq = e.getTo().distanceSquared(duelCenter);
        if (distSq <= DUEL_RADIUS * DUEL_RADIUS) {
            Vector dir = e.getTo().toVector().subtract(duelCenter.toVector()).normalize();
            Location out = duelCenter.clone().add(dir.multiply(DUEL_RADIUS + 2));
            out.setY(e.getTo().getY());
            internalTeleport = true;
            p.teleport(out);
            internalTeleport = false;

            long now = System.currentTimeMillis();
            long last = warnCooldown.getOrDefault(p.getUniqueId(), 0L);
            if (now - last > 3000) {
                p.sendMessage("§cЭто дуэль. Не вмешивайся.");
                warnCooldown.put(p.getUniqueId(), now);
            }
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (!active) return;
        if (!isDuelPlayer(e.getPlayer().getUniqueId())) return;
        if (internalTeleport) return;

        sendBossMessage(randomLine(ESCAPE_LINES));
        endDuel("teleport", false, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!active) return;
        if (!isDuelPlayer(e.getPlayer().getUniqueId())) return;

        sendBossMessage(randomLine(ESCAPE_LINES));
        endDuel("quit", false, true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (!active) return;
        if (!isDuelPlayer(e.getEntity().getUniqueId())) return;

        sendBossMessage(randomLine(WIN_LINES));
        endDuel("player_dead", false, true);
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Zombie z)) return;
        if (!z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) return;

        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            long reward = 500 + rng.nextInt(501);
            plugin.econ().give(killer, reward);
            plugin.progress().addPlayerExp(killer, 20);
            Bukkit.broadcastMessage("§dИгрок " + killer.getName() + " победил ?????");
        }

        endDuel("boss_dead", true, false);
    }

    @EventHandler
    public void onBossTarget(EntityTargetEvent e) {
        if (!(e.getEntity() instanceof Zombie z)) return;
        if (!z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) return;

        Player duelPlayer = getDuelPlayer();
        if (duelPlayer == null) return;

        if (e.getTarget() == null || !e.getTarget().getUniqueId().equals(duelPlayer.getUniqueId())) {
            e.setCancelled(true);
            z.setTarget(duelPlayer);
        }
    }

    @EventHandler
    public void onBossDamaged(EntityDamageByEntityEvent e) {
        if (!active || stage != Stage.FIGHT) return;
        if (!(e.getEntity() instanceof Zombie z)) return;
        if (!z.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) return;

        if (!(e.getDamager() instanceof Player p)) return;
        if (!isDuelPlayer(p.getUniqueId())) return;

        long now = System.currentTimeMillis();
        if (now - lastHitAt < 3000) {
            recentHitCount++;
        } else {
            recentHitCount = 1;
        }
        lastHitAt = now;

        if (recentHitCount >= 3) {
            sendBossMessage(randomLine(DAMAGE_LINES));
            recentHitCount = 0;
        }
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private Attribute getAttribute(String... keys) {
        for (String k : keys) {
            Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(k));
            if (a != null) return a;
        }
        return null;
    }
}
