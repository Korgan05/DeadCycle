package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class SummonerKitManager implements Listener {

    public enum SummonType {
        WOLF("summoner_wolves", "§aПризыв волков", "wolf", 1),
        PHANTOM("summoner_phantom", "§5Призыв фантома", "phantom", 3),
        GOLEM("summoner_golem", "§fПризыв голема", "golem", 4),
        VEX("summoner_vex", "§dПризыв духов", "vex", 7);

        private final String skillId;
        private final String display;
        private final String configKey;
        private final int unlockLevel;

        SummonType(String skillId, String display, String configKey, int unlockLevel) {
            this.skillId = skillId;
            this.display = display;
            this.configKey = configKey;
            this.unlockLevel = unlockLevel;
        }

        public String skillId() {
            return skillId;
        }

        public String display() {
            return display;
        }

        public String configKey() {
            return configKey;
        }

        public int unlockLevel() {
            return unlockLevel;
        }
    }

    private static final String TYPE_DEATH_WARDEN = "death_warden";

    private final DeadCyclePlugin plugin;
    private final Random rng = new Random();

    private final NamespacedKey summonKey;
    private final NamespacedKey summonOwnerKey;
    private final NamespacedKey summonTypeKey;

    private final Map<UUID, Set<UUID>> ownerToSummons = new HashMap<>();
    private final Map<UUID, Long> summonExpireAt = new HashMap<>();

    private final Map<UUID, UUID> lastHitOwnerByZombie = new HashMap<>();
    private final Map<UUID, Long> lastHitAtByZombie = new HashMap<>();

    private BukkitTask aiTask;
    private boolean summonSpawning = false;

    private boolean enabled;
    private double targetSearchRadius;
    private long hitCreditMs;
    private long durationWolfSec;
    private long durationPhantomSec;
    private long durationGolemSec;
    private long durationVexSec;

    private int maxActiveWolves;
    private int maxActivePhantoms;
    private int maxActiveGolems;
    private int maxActiveVex;

    private int deathWardenUnlockLevel;
    private long deathWardenDurationSec;

    private double wolfHp;
    private double wolfDamage;
    private double wolfSpeed;

    private double phantomHp;
    private double phantomDamage;
    private double phantomSpeed;

    private double golemHp;
    private double golemDamage;
    private double golemSpeed;

    private double vexHp;
    private double vexDamage;
    private double vexSpeed;

    private double deathWardenHp;
    private double deathWardenDamage;

    public SummonerKitManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.summonKey = new NamespacedKey(plugin, "kit_summoner_summon");
        this.summonOwnerKey = new NamespacedKey(plugin, "kit_summoner_owner");
        this.summonTypeKey = new NamespacedKey(plugin, "kit_summoner_type");
        reload();
        startTask();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("summoner.enabled", true);
        this.targetSearchRadius = Math.max(10.0, plugin.getConfig().getDouble("summoner.target_search_radius", 32.0));
        this.hitCreditMs = Math.max(1000L, plugin.getConfig().getLong("summoner.summon_hit_credit_ms", 5000L));

        this.durationWolfSec = Math.max(10L, plugin.getConfig().getLong("summoner.duration_seconds.wolf", 55L));
        this.durationPhantomSec = Math.max(10L,
                plugin.getConfig().getLong("summoner.duration_seconds.phantom", 50L));
        this.durationGolemSec = Math.max(10L, plugin.getConfig().getLong("summoner.duration_seconds.golem", 65L));
        this.durationVexSec = Math.max(10L, plugin.getConfig().getLong("summoner.duration_seconds.vex", 45L));

        this.maxActiveWolves = Math.max(1, plugin.getConfig().getInt("summoner.max_active.wolf", 10));
        this.maxActivePhantoms = Math.max(1, plugin.getConfig().getInt("summoner.max_active.phantom", 4));
        this.maxActiveGolems = Math.max(1, plugin.getConfig().getInt("summoner.max_active.golem", 2));
        this.maxActiveVex = Math.max(1, plugin.getConfig().getInt("summoner.max_active.vex", 6));

        this.deathWardenUnlockLevel = 10;
        this.deathWardenDurationSec = Math.max(5L,
                plugin.getConfig().getLong("summoner.death_warden.duration_seconds", 30L));

        this.wolfHp = Math.max(4.0, plugin.getConfig().getDouble("summoner.stats.wolf.hp", 24.0));
        this.wolfDamage = Math.max(1.0, plugin.getConfig().getDouble("summoner.stats.wolf.damage", 4.5));
        this.wolfSpeed = Math.max(0.18, plugin.getConfig().getDouble("summoner.stats.wolf.speed", 0.34));

        this.phantomHp = Math.max(4.0, plugin.getConfig().getDouble("summoner.stats.phantom.hp", 26.0));
        this.phantomDamage = Math.max(1.0, plugin.getConfig().getDouble("summoner.stats.phantom.damage", 5.5));
        this.phantomSpeed = Math.max(0.18, plugin.getConfig().getDouble("summoner.stats.phantom.speed", 0.30));

        this.golemHp = Math.max(10.0, plugin.getConfig().getDouble("summoner.stats.golem.hp", 90.0));
        this.golemDamage = Math.max(1.0, plugin.getConfig().getDouble("summoner.stats.golem.damage", 11.0));
        this.golemSpeed = Math.max(0.10, plugin.getConfig().getDouble("summoner.stats.golem.speed", 0.24));

        this.vexHp = Math.max(4.0, plugin.getConfig().getDouble("summoner.stats.vex.hp", 20.0));
        this.vexDamage = Math.max(1.0, plugin.getConfig().getDouble("summoner.stats.vex.damage", 4.0));
        this.vexSpeed = Math.max(0.18, plugin.getConfig().getDouble("summoner.stats.vex.speed", 0.40));

        this.deathWardenHp = Math.max(20.0,
                plugin.getConfig().getDouble("summoner.death_warden.hp", 180.0));
        this.deathWardenDamage = Math.max(2.0,
                plugin.getConfig().getDouble("summoner.death_warden.damage", 18.0));

        if (!enabled) {
            removeAllSummons();
        }
    }

    public void shutdown() {
        if (aiTask != null) {
            aiTask.cancel();
            aiTask = null;
        }
        removeAllSummons();
        ownerToSummons.clear();
        summonExpireAt.clear();
        lastHitOwnerByZombie.clear();
        lastHitAtByZombie.clear();
    }

    public void resetAll() {
        removeAllSummons();
        ownerToSummons.clear();
        summonExpireAt.clear();
        lastHitOwnerByZombie.clear();
        lastHitAtByZombie.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public NamespacedKey summonMarkKey() {
        return summonKey;
    }

    public boolean isSummonSpawning() {
        return summonSpawning;
    }

    public void onKitAssigned(Player owner, KitManager.Kit kit) {
        if (owner == null)
            return;

        if (kit != KitManager.Kit.SUMMONER) {
            removeOwnerSummons(owner.getUniqueId());
            removeSummonerSkillItems(owner);
            return;
        }

        syncSkillItems(owner);
    }

    public void syncSkillItems(Player owner) {
        if (owner == null || !owner.isOnline())
            return;

        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.SUMMONER) {
            removeSummonerSkillItems(owner);
            return;
        }

        int level = plugin.progress().getSummonerLevel(owner.getUniqueId());

        ensureSkillItem(owner, SummonType.WOLF.skillId(), plugin.kit().createSummonerWolvesItem(), true);
        ensureSkillItem(owner, SummonType.PHANTOM.skillId(), plugin.kit().createSummonerPhantomItem(),
                level >= SummonType.PHANTOM.unlockLevel());
        ensureSkillItem(owner, SummonType.GOLEM.skillId(), plugin.kit().createSummonerGolemItem(),
                level >= SummonType.GOLEM.unlockLevel());
        ensureSkillItem(owner, SummonType.VEX.skillId(), plugin.kit().createSummonerVexItem(),
                level >= SummonType.VEX.unlockLevel());
    }

    private void ensureSkillItem(Player owner, String skillId, ItemStack item, boolean shouldHave) {
        boolean has = hasSkillItem(owner, skillId);
        if (shouldHave && !has) {
            Map<Integer, ItemStack> leftovers = owner.getInventory().addItem(item);
            for (ItemStack left : leftovers.values()) {
                owner.getWorld().dropItemNaturally(owner.getLocation(), left);
            }
            return;
        }

        if (!shouldHave && has) {
            removeSkillItem(owner, skillId);
        }
    }

    private boolean hasSkillItem(Player owner, String skillId) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (item == null)
                continue;
            String id = plugin.kit().getSkillIdFromItem(item);
            if (skillId.equals(id))
                return true;
        }

        ItemStack off = owner.getInventory().getItemInOffHand();
        if (off != null) {
            String id = plugin.kit().getSkillIdFromItem(off);
            return skillId.equals(id);
        }

        return false;
    }

    private void removeSkillItem(Player owner, String skillId) {
        for (int slot = 0; slot < owner.getInventory().getSize(); slot++) {
            ItemStack item = owner.getInventory().getItem(slot);
            if (item == null)
                continue;

            String id = plugin.kit().getSkillIdFromItem(item);
            if (skillId.equals(id)) {
                owner.getInventory().setItem(slot, null);
            }
        }

        ItemStack off = owner.getInventory().getItemInOffHand();
        if (off != null) {
            String id = plugin.kit().getSkillIdFromItem(off);
            if (skillId.equals(id)) {
                owner.getInventory().setItemInOffHand(null);
            }
        }
    }

    private void removeSummonerSkillItems(Player owner) {
        if (owner == null)
            return;

        removeSkillItem(owner, SummonType.WOLF.skillId());
        removeSkillItem(owner, SummonType.PHANTOM.skillId());
        removeSkillItem(owner, SummonType.GOLEM.skillId());
        removeSkillItem(owner, SummonType.VEX.skillId());
    }

    public String getSummonError(Player owner, SummonType type) {
        if (!enabled)
            return "§cКит Призыватель временно отключён.";
        if (owner == null || !owner.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.SUMMONER)
            return "§cЭтот навык доступен только киту Призыватель.";

        int level = plugin.progress().getSummonerLevel(owner.getUniqueId());
        if (level < type.unlockLevel()) {
            return "§cНавык откроется с уровня " + type.unlockLevel() + ".";
        }

        int active = getActiveCount(owner.getUniqueId(), type);
        int limit = getMaxActive(type, level);
        if (active >= limit) {
            return "§cЛимит призывов этого типа достигнут (" + active + "/" + limit + ").";
        }

        return null;
    }

    public boolean summon(Player owner, SummonType type) {
        String error = getSummonError(owner, type);
        if (error != null) {
            owner.sendMessage(error);
            return false;
        }

        int level = plugin.progress().getSummonerLevel(owner.getUniqueId());
        int active = getActiveCount(owner.getUniqueId(), type);
        int maxActive = getMaxActive(type, level);
        int desired = getSummonCount(type, level);
        int toSpawn = Math.max(0, Math.min(desired, maxActive - active));

        if (toSpawn <= 0) {
            owner.sendMessage("§cСейчас нельзя призвать больше существ этого типа.");
            return false;
        }

        int durationSeconds = getSummonDurationSeconds(type);
        int spawned = 0;

        for (int i = 0; i < toSpawn; i++) {
            Location spawnLoc = findSpawnNear(owner.getLocation(), type);
            if (spawnLoc == null)
                continue;

            Mob summon = spawnByType(owner, type, spawnLoc, level);
            if (summon == null)
                continue;

            registerSummon(owner.getUniqueId(), summon, type.configKey(), durationSeconds);
            playSummonSpawnEffect(summon, type);
            spawned++;
        }

        if (spawned <= 0) {
            owner.sendMessage("§cНе удалось призвать существ.");
            return false;
        }

        playSummonCastEffect(owner, type, spawned);
        owner.sendMessage("§d[Призыватель] §fПризвано: " + type.display() + " §7x" + spawned);
        return true;
    }

    private int getSummonCount(SummonType type, int level) {
        int lvl = Math.max(1, Math.min(10, level));

        return switch (type) {
            case WOLF -> {
                if (lvl <= 1)
                    yield 1;
                if (lvl == 2)
                    yield 2;
                yield 2 + ((lvl - 2) / 2);
            }
            case PHANTOM -> 1 + Math.max(0, (lvl - 3) / 3);
            case GOLEM -> (lvl >= 9) ? 2 : 1;
            case VEX -> 2 + Math.max(0, (lvl - 8) / 2);
        };
    }

    private int getMaxActive(SummonType type, int level) {
        return switch (type) {
            case WOLF -> maxActiveWolves;
            case PHANTOM -> maxActivePhantoms;
            case GOLEM -> maxActiveGolems;
            case VEX -> maxActiveVex;
        };
    }

    private int getSummonDurationSeconds(SummonType type) {
        return switch (type) {
            case WOLF -> (int) durationWolfSec;
            case PHANTOM -> (int) durationPhantomSec;
            case GOLEM -> (int) durationGolemSec;
            case VEX -> (int) durationVexSec;
        };
    }

    private int getActiveCount(UUID ownerId, SummonType type) {
        List<Mob> active = getActiveSummons(ownerId);
        if (active.isEmpty())
            return 0;

        String needType = type.configKey();
        int count = 0;
        for (Mob summon : active) {
            String summonType = summon.getPersistentDataContainer().get(summonTypeKey, PersistentDataType.STRING);
            if (needType.equalsIgnoreCase(summonType)) {
                count++;
            }
        }

        return count;
    }

    private void startTask() {
        if (aiTask != null) {
            aiTask.cancel();
        }

        aiTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAi, 10L, 10L);
    }

    private void tickAi() {
        if (!enabled)
            return;

        long now = System.currentTimeMillis();

        for (UUID ownerId : new ArrayList<>(ownerToSummons.keySet())) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline()
                    || plugin.kit().getKit(ownerId) != KitManager.Kit.SUMMONER) {
                removeOwnerSummons(ownerId);
                continue;
            }

            List<Mob> summons = getActiveSummons(ownerId);
            if (summons.isEmpty()) {
                ownerToSummons.remove(ownerId);
                continue;
            }

            for (Mob summon : summons) {
                long expireAt = summonExpireAt.getOrDefault(summon.getUniqueId(), 0L);
                if (expireAt > 0L && now >= expireAt) {
                    summon.remove();
                    cleanupSummonRuntime(summon.getUniqueId(), ownerId);
                    continue;
                }

                LivingEntity currentTarget = summon.getTarget();
                if (!isEnemy(currentTarget, ownerId)) {
                    LivingEntity enemy = findNearestEnemy(summon.getLocation(), targetSearchRadius, ownerId);
                    if (enemy != null) {
                        summon.setTarget(enemy);
                    }
                }
            }
        }

        cleanupHitCache();
    }

    private void cleanupHitCache() {
        long now = System.currentTimeMillis();
        for (UUID zombieId : new ArrayList<>(lastHitAtByZombie.keySet())) {
            long at = lastHitAtByZombie.getOrDefault(zombieId, 0L);
            if (now - at > hitCreditMs) {
                lastHitAtByZombie.remove(zombieId);
                lastHitOwnerByZombie.remove(zombieId);
            }
        }
    }

    private void removeAllSummons() {
        for (UUID ownerId : new ArrayList<>(ownerToSummons.keySet())) {
            removeOwnerSummons(ownerId);
        }
    }

    private void removeOwnerSummons(UUID ownerId) {
        Set<UUID> ids = ownerToSummons.remove(ownerId);
        if (ids == null)
            return;

        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid() && !entity.isDead()) {
                entity.remove();
            }
            cleanupSummonRuntime(id, ownerId);
        }
    }

    private void cleanupSummonRuntime(UUID entityId, UUID ownerId) {
        summonExpireAt.remove(entityId);

        Set<UUID> ownerSet = ownerToSummons.get(ownerId);
        if (ownerSet != null) {
            ownerSet.remove(entityId);
            if (ownerSet.isEmpty()) {
                ownerToSummons.remove(ownerId);
            }
        }
    }

    private List<Mob> getActiveSummons(UUID ownerId) {
        Set<UUID> ids = ownerToSummons.get(ownerId);
        if (ids == null || ids.isEmpty())
            return Collections.emptyList();

        List<Mob> result = new ArrayList<>();

        for (UUID id : new ArrayList<>(ids)) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob summon) || summon.isDead() || !summon.isValid() || !isManagedSummon(entity)) {
                ids.remove(id);
                summonExpireAt.remove(id);
                continue;
            }

            UUID owner = readOwnerId(entity);
            if (owner == null || !owner.equals(ownerId)) {
                ids.remove(id);
                summonExpireAt.remove(id);
                continue;
            }

            result.add(summon);
        }

        if (ids.isEmpty()) {
            ownerToSummons.remove(ownerId);
        }

        return result;
    }

    private void registerSummon(UUID ownerId, Mob summon, String type, int durationSeconds) {
        ownerToSummons.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(summon.getUniqueId());
        if (TYPE_DEATH_WARDEN.equalsIgnoreCase(type)) {
            summonExpireAt.put(summon.getUniqueId(), System.currentTimeMillis() + (durationSeconds * 1000L));
        } else {
            summonExpireAt.remove(summon.getUniqueId());
        }

        summon.getPersistentDataContainer().set(summonKey, PersistentDataType.BYTE, (byte) 1);
        summon.getPersistentDataContainer().set(summonOwnerKey, PersistentDataType.STRING, ownerId.toString());
        summon.getPersistentDataContainer().set(summonTypeKey, PersistentDataType.STRING,
                type.toLowerCase(Locale.ROOT));
    }

    private Mob spawnByType(Player owner, SummonType type, Location spawnLoc, int level) {
        return switch (type) {
            case WOLF -> spawnWolf(owner, spawnLoc, level);
            case PHANTOM -> spawnPhantom(owner, spawnLoc, level);
            case GOLEM -> spawnGolem(owner, spawnLoc, level);
            case VEX -> spawnVex(owner, spawnLoc, level);
        };
    }

    private Mob spawnWolf(Player owner, Location loc, int level) {
        World world = loc.getWorld();
        if (world == null)
            return null;

        try {
            summonSpawning = true;
            return world.spawn(loc, Wolf.class, wolf -> {
                wolf.setCustomName("§aВолк призывателя §7" + owner.getName());
                wolf.setCustomNameVisible(false);
                wolf.setCanPickupItems(false);
                wolf.setRemoveWhenFarAway(false);
                wolf.setPersistent(true);
                wolf.setAdult();
                wolf.setTamed(true);
                wolf.setOwner(owner);
                wolf.setCollarColor(DyeColor.LIME);

                double hp = wolfHp + (Math.max(0, level - 1) * 1.2);
                double damage = wolfDamage + (Math.max(0, level - 1) * 0.22);
                double speed = wolfSpeed + (Math.max(0, level - 1) * 0.004);
                applyAttributes(wolf, hp, damage, speed, targetSearchRadius);
            });
        } finally {
            summonSpawning = false;
        }
    }

    private Mob spawnPhantom(Player owner, Location loc, int level) {
        World world = loc.getWorld();
        if (world == null)
            return null;

        Location aerial = loc.clone().add(0, 6.0 + rng.nextDouble() * 4.0, 0);

        try {
            summonSpawning = true;
            return world.spawn(aerial, Phantom.class, phantom -> {
                phantom.setCustomName("§5Фантом призывателя §7" + owner.getName());
                phantom.setCustomNameVisible(false);
                phantom.setCanPickupItems(false);
                phantom.setRemoveWhenFarAway(false);
                phantom.setPersistent(true);
                phantom.setSize(Math.min(6, 1 + (level / 3)));

                double hp = phantomHp + (Math.max(0, level - 1) * 1.5);
                double damage = phantomDamage + (Math.max(0, level - 1) * 0.28);
                double speed = phantomSpeed + (Math.max(0, level - 1) * 0.003);
                applyAttributes(phantom, hp, damage, speed, targetSearchRadius);
            });
        } finally {
            summonSpawning = false;
        }
    }

    private Mob spawnGolem(Player owner, Location loc, int level) {
        World world = loc.getWorld();
        if (world == null)
            return null;

        try {
            summonSpawning = true;
            return world.spawn(loc, IronGolem.class, golem -> {
                golem.setCustomName("§fЖелезный голем §7" + owner.getName());
                golem.setCustomNameVisible(false);
                golem.setCanPickupItems(false);
                golem.setRemoveWhenFarAway(false);
                golem.setPersistent(true);
                golem.setPlayerCreated(true);

                double hp = golemHp + (Math.max(0, level - 1) * 3.0);
                double damage = golemDamage + (Math.max(0, level - 1) * 0.55);
                double speed = golemSpeed + (Math.max(0, level - 1) * 0.002);
                applyAttributes(golem, hp, damage, speed, targetSearchRadius);
            });
        } finally {
            summonSpawning = false;
        }
    }

    private Mob spawnVex(Player owner, Location loc, int level) {
        World world = loc.getWorld();
        if (world == null)
            return null;

        Location aerial = loc.clone().add(0, 2.0 + rng.nextDouble() * 2.0, 0);

        try {
            summonSpawning = true;
            return world.spawn(aerial, Vex.class, vex -> {
                vex.setCustomName("§dДух призывателя §7" + owner.getName());
                vex.setCustomNameVisible(false);
                vex.setCanPickupItems(false);
                vex.setRemoveWhenFarAway(false);
                vex.setPersistent(true);

                double hp = vexHp + (Math.max(0, level - 1) * 1.0);
                double damage = vexDamage + (Math.max(0, level - 1) * 0.20);
                double speed = vexSpeed + (Math.max(0, level - 1) * 0.004);
                applyAttributes(vex, hp, damage, speed, targetSearchRadius);
            });
        } finally {
            summonSpawning = false;
        }
    }

    private void spawnDeathWarden(Player owner) {
        if (owner == null || !owner.isOnline())
            return;

        World world = owner.getWorld();
        if (world == null)
            return;

        Location loc = owner.getLocation().clone();
        loc.setY(world.getHighestBlockYAt(loc) + 1);

        try {
            summonSpawning = true;
            Warden warden = world.spawn(loc, Warden.class, w -> {
                w.setCustomName("§8Варден мести §7" + owner.getName());
                w.setCustomNameVisible(true);
                w.setCanPickupItems(false);
                w.setRemoveWhenFarAway(false);
                w.setPersistent(true);
                applyAttributes(w, deathWardenHp, deathWardenDamage, 0.30, targetSearchRadius);
            });

            registerSummon(owner.getUniqueId(), warden, TYPE_DEATH_WARDEN, (int) deathWardenDurationSec);
            owner.sendMessage("§8[Призыватель] §fПеред смертью призван Варден на " + deathWardenDurationSec + "с.");
            playDeathWardenArrivalEffect(loc, owner.getWorld());
        } finally {
            summonSpawning = false;
        }
    }

    private void playSummonSpawnEffect(Mob summon, SummonType type) {
        if (summon == null)
            return;
        World world = summon.getWorld();
        if (world == null)
            return;

        Location at = summon.getLocation().clone().add(0, 0.85, 0);

        switch (type) {
            case WOLF -> {
                world.spawnParticle(Particle.CLOUD, at, 14, 0.35, 0.22, 0.35, 0.02);
                world.spawnParticle(Particle.DUST, at, 10, 0.22, 0.25, 0.22, 0.0,
                        new Particle.DustOptions(Color.fromRGB(120, 255, 140), 1.1f));
            }
            case PHANTOM -> {
                world.spawnParticle(Particle.REVERSE_PORTAL, at, 24, 0.35, 0.45, 0.35, 0.03);
                world.spawnParticle(Particle.SOUL, at, 10, 0.20, 0.25, 0.20, 0.02);
            }
            case GOLEM -> {
                world.spawnParticle(Particle.CRIT, at, 24, 0.35, 0.30, 0.35, 0.04);
                world.spawnParticle(Particle.ENCHANT, at, 16, 0.28, 0.25, 0.28, 0.06);
            }
            case VEX -> {
                world.spawnParticle(Particle.ENCHANT, at, 24, 0.32, 0.30, 0.32, 0.08);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 14, 0.25, 0.25, 0.25, 0.015);
            }
        }
    }

    private void playSummonCastEffect(Player owner, SummonType type, int spawned) {
        if (owner == null || !owner.isOnline())
            return;
        World world = owner.getWorld();
        if (world == null)
            return;

        Location center = owner.getLocation().clone().add(0, 1.0, 0);

        Color color;
        Sound primary;
        Sound secondary;

        switch (type) {
            case WOLF -> {
                color = Color.fromRGB(120, 255, 140);
                primary = Sound.ENTITY_WOLF_AMBIENT;
                secondary = Sound.BLOCK_BEACON_POWER_SELECT;
            }
            case PHANTOM -> {
                color = Color.fromRGB(170, 120, 255);
                primary = Sound.ENTITY_PHANTOM_FLAP;
                secondary = Sound.ENTITY_ENDERMAN_TELEPORT;
            }
            case GOLEM -> {
                color = Color.fromRGB(210, 210, 220);
                primary = Sound.ENTITY_IRON_GOLEM_ATTACK;
                secondary = Sound.BLOCK_ANVIL_PLACE;
            }
            case VEX -> {
                color = Color.fromRGB(230, 120, 255);
                primary = Sound.ENTITY_VEX_CHARGE;
                secondary = Sound.ENTITY_EVOKER_CAST_SPELL;
            }
            default -> {
                color = Color.fromRGB(180, 180, 255);
                primary = Sound.BLOCK_BEACON_POWER_SELECT;
                secondary = Sound.BLOCK_RESPAWN_ANCHOR_CHARGE;
            }
        }

        double radius = 1.5 + Math.min(1.8, spawned * 0.18);
        int points = 34 + (spawned * 6);
        spawnDustRing(world, center, radius, points, color, 1.25f);

        int burst = 26 + (spawned * 7);
        world.spawnParticle(Particle.ENCHANT, center, burst, 0.45, 0.55, 0.45, 0.08);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, Math.max(6, spawned * 3), 0.22, 0.30, 0.22, 0.02);

        switch (type) {
            case PHANTOM -> world.spawnParticle(Particle.REVERSE_PORTAL, center, 30, 0.35, 0.45, 0.35, 0.03);
            case GOLEM -> world.spawnParticle(Particle.CRIT, center, 24, 0.35, 0.35, 0.35, 0.05);
            case VEX -> world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 22, 0.32, 0.35, 0.32, 0.01);
            default -> {
            }
        }

        world.playSound(center, primary, 1.0f, 1.0f);
        world.playSound(center, secondary, 0.85f, 1.15f);
    }

    private void playDeathWardenArrivalEffect(Location base, World world) {
        if (base == null || world == null)
            return;

        Location center = base.clone().add(0, 0.2, 0);

        world.spawnParticle(Particle.EXPLOSION, center.clone().add(0, 1.0, 0), 10, 0.55, 0.45, 0.55, 0.02);
        world.spawnParticle(Particle.SCULK_SOUL, center.clone().add(0, 1.0, 0), 95, 1.0, 0.9, 1.0, 0.02);
        world.spawnParticle(Particle.REVERSE_PORTAL, center.clone().add(0, 0.8, 0), 90, 0.8, 0.8, 0.8, 0.02);

        world.playSound(center, Sound.ENTITY_WARDEN_EMERGE, 2.0f, 0.70f);
        world.playSound(center, Sound.ENTITY_WARDEN_ROAR, 2.0f, 0.62f);

        for (int wave = 0; wave < 5; wave++) {
            final int waveIndex = wave;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                double radius = 2.5 + (waveIndex * 1.8);
                int points = 64 + (waveIndex * 12);

                spawnDustRing(world, center.clone().add(0, 0.15, 0), radius, points,
                        Color.fromRGB(45, 120, 180), 1.7f);

                world.spawnParticle(Particle.SOUL, center.clone().add(0, 0.7, 0),
                        48 + (waveIndex * 18), radius * 0.35, 0.55, radius * 0.35, 0.02);
                world.spawnParticle(Particle.SMOKE, center.clone().add(0, 0.9, 0),
                        36 + (waveIndex * 14), radius * 0.32, 0.36, radius * 0.32, 0.015);

                if (waveIndex >= 4) {
                    world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.8f, 0.86f);
                } else {
                    world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.55f + (waveIndex * 0.12f));
                }
            }, wave * 4L);
        }
    }

    private void spawnDustRing(World world, Location center, double radius, int points, Color color, float size) {
        if (world == null || center == null)
            return;

        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        int total = Math.max(8, points);

        for (int i = 0; i < total; i++) {
            double angle = (Math.PI * 2.0 * i) / total;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location point = center.clone().add(x, 0.0, z);
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.03, 0.02, 0.0, dust);
        }
    }

    private void applyAttributes(LivingEntity entity, double hp, double damage, double speed, double follow) {
        Attribute maxHealthAttr = getAttribute("generic.max_health", "max_health");
        if (maxHealthAttr != null) {
            AttributeInstance maxHealth = entity.getAttribute(maxHealthAttr);
            if (maxHealth != null) {
                maxHealth.setBaseValue(hp);
                entity.setHealth(Math.min(hp, maxHealth.getValue()));
            }
        }

        Attribute attackAttr = getAttribute("generic.attack_damage", "attack_damage");
        if (attackAttr != null) {
            AttributeInstance attack = entity.getAttribute(attackAttr);
            if (attack != null) {
                attack.setBaseValue(damage);
            }
        }

        Attribute speedAttr = getAttribute("generic.movement_speed", "movement_speed");
        if (speedAttr != null) {
            AttributeInstance move = entity.getAttribute(speedAttr);
            if (move != null) {
                move.setBaseValue(speed);
            }
        }

        Attribute followAttr = getAttribute("generic.follow_range", "follow_range");
        if (followAttr != null && entity instanceof Mob mob) {
            AttributeInstance followRange = mob.getAttribute(followAttr);
            if (followRange != null) {
                followRange.setBaseValue(follow);
            }
        }

        if (entity instanceof Mob mob) {
            EntityEquipment eq = mob.getEquipment();
            if (eq != null) {
                eq.setHelmetDropChance(0f);
                eq.setChestplateDropChance(0f);
                eq.setLeggingsDropChance(0f);
                eq.setBootsDropChance(0f);
                eq.setItemInMainHandDropChance(0f);
                eq.setItemInOffHandDropChance(0f);
            }
        }
    }

    private Attribute getAttribute(String... keys) {
        for (String key : keys) {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (attr != null)
                return attr;
        }
        return null;
    }

    private Location findSpawnNear(Location center, SummonType type) {
        World world = center.getWorld();
        if (world == null)
            return null;

        int minR = plugin.getConfig().getInt("summoner.spawn_radius_min", 2);
        int maxR = Math.max(minR + 1, plugin.getConfig().getInt("summoner.spawn_radius_max", 8));

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double r = minR + rng.nextDouble() * (maxR - minR);

            int x = (int) Math.round(center.getX() + Math.cos(angle) * r);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * r);
            int y = world.getHighestBlockYAt(x, z) + 1;

            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (loc.getBlock().isLiquid())
                continue;

            if (type == SummonType.PHANTOM || type == SummonType.VEX) {
                return loc.clone().add(0, 2.0, 0);
            }
            return loc;
        }

        return center.clone().add(0, 1.0, 0);
    }

    private LivingEntity findNearestEnemy(Location center, double radius, UUID ownerId) {
        if (center == null || center.getWorld() == null)
            return null;

        LivingEntity best = null;
        double bestDist = radius * radius;

        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isEnemy(living, ownerId))
                continue;

            double dist = living.getLocation().distanceSquared(center);
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }

        return best;
    }

    private boolean isEnemy(LivingEntity target, UUID ownerId) {
        if (target == null || !target.isValid() || target.isDead())
            return false;
        if (target instanceof Player)
            return false;

        if (isManagedSummon(target))
            return false;
        if (isClone(target))
            return false;

        if (target instanceof Zombie)
            return true;

        return target instanceof Monster;
    }

    private boolean isClone(Entity entity) {
        if (entity == null || plugin.cloneKit() == null)
            return false;

        Byte mark = entity.getPersistentDataContainer().get(plugin.cloneKit().cloneMarkKey(), PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private boolean isManagedSummon(Entity entity) {
        if (entity == null)
            return false;
        Byte mark = entity.getPersistentDataContainer().get(summonKey, PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private UUID readOwnerId(Entity summon) {
        String raw = summon.getPersistentDataContainer().get(summonOwnerKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank())
            return null;

        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private UUID resolveOwnerFromDamager(Entity damager) {
        if (damager == null)
            return null;

        if (isManagedSummon(damager)) {
            return readOwnerId(damager);
        }

        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter
                && isManagedSummon(shooter)) {
            return readOwnerId(shooter);
        }

        return null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline())
                return;
            if (plugin.kit().getKit(p.getUniqueId()) == KitManager.Kit.SUMMONER) {
                syncSkillItems(p);
            }
        }, 10L);
    }

    @EventHandler
    public void onOwnerQuit(PlayerQuitEvent e) {
        removeOwnerSummons(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onOwnerDeath(PlayerDeathEvent e) {
        Player owner = e.getEntity();
        if (owner == null)
            return;

        UUID ownerId = owner.getUniqueId();
        removeOwnerSummons(ownerId);

        if (plugin.kit().getKit(ownerId) != KitManager.Kit.SUMMONER)
            return;

        int level = plugin.progress().getSummonerLevel(ownerId);
        if (level < deathWardenUnlockLevel)
            return;

        spawnDeathWarden(owner);
    }

    @EventHandler
    public void onSummonTarget(EntityTargetEvent e) {
        if (!isManagedSummon(e.getEntity()))
            return;
        if (!(e.getEntity() instanceof Mob summon))
            return;

        UUID ownerId = readOwnerId(summon);
        if (ownerId == null) {
            e.setCancelled(true);
            summon.setTarget(null);
            return;
        }

        if (!(e.getTarget() instanceof LivingEntity target) || !isEnemy(target, ownerId)) {
            e.setCancelled(true);
            LivingEntity enemy = findNearestEnemy(summon.getLocation(), targetSearchRadius, ownerId);
            summon.setTarget(enemy);
        }
    }

    @EventHandler
    public void onSummonDamage(EntityDamageByEntityEvent e) {
        Entity damaged = e.getEntity();
        Entity damager = e.getDamager();

        if (isManagedSummon(damaged)) {
            if (damager instanceof Player
                    || (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player)) {
                e.setCancelled(true);
                return;
            }

            if (isManagedSummon(damager)
                    || (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter
                            && isManagedSummon(shooter))) {
                e.setCancelled(true);
                return;
            }
        }

        UUID ownerId = resolveOwnerFromDamager(damager);
        if (ownerId == null)
            return;

        if (damaged instanceof Player || isManagedSummon(damaged)) {
            e.setCancelled(true);
            return;
        }

        if (damaged instanceof Zombie zombie) {
            lastHitOwnerByZombie.put(zombie.getUniqueId(), ownerId);
            lastHitAtByZombie.put(zombie.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        Entity dead = e.getEntity();

        if (isManagedSummon(dead)) {
            UUID ownerId = readOwnerId(dead);
            if (ownerId != null) {
                cleanupSummonRuntime(dead.getUniqueId(), ownerId);
            } else {
                summonExpireAt.remove(dead.getUniqueId());
            }
            return;
        }

        if (!(dead instanceof Zombie zombie))
            return;

        UUID victimId = zombie.getUniqueId();
        UUID ownerId = null;

        if (zombie.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity) {
            ownerId = resolveOwnerFromDamager(byEntity.getDamager());
            if (ownerId == null) {
                Entity damager = byEntity.getDamager();
                if (damager instanceof Player
                        || (damager instanceof Projectile p && p.getShooter() instanceof Player)) {
                    lastHitOwnerByZombie.remove(victimId);
                    lastHitAtByZombie.remove(victimId);
                    return;
                }
            }
        }

        if (ownerId == null) {
            long at = lastHitAtByZombie.getOrDefault(victimId, 0L);
            if (at > 0L && (System.currentTimeMillis() - at) <= hitCreditMs) {
                ownerId = lastHitOwnerByZombie.get(victimId);
            }
        }

        lastHitOwnerByZombie.remove(victimId);
        lastHitAtByZombie.remove(victimId);

        if (ownerId == null)
            return;

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline())
            return;
        if (plugin.kit().getKit(ownerId) != KitManager.Kit.SUMMONER)
            return;

        int exp = plugin.getConfig().getInt("kit_xp.summoner.exp_per_summon_kill_zombie", 2);
        if (exp > 0) {
            plugin.progress().addSummonerExp(owner, exp);
        }
    }
}
