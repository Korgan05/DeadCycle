package me.korgan.deadcycle.zombies;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class ZombieWaveManager {

    private enum ZombieType {
        NORMAL("normal"),
        RUNNER("runner"),
        BRUTE("brute"),
        SCREAMER("screamer"),
        NECROMANCER("necromancer"),
        SAPPER("sapper");

        private final String id;

        ZombieType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static ZombieType byId(String raw) {
            if (raw == null || raw.isBlank())
                return NORMAL;

            for (ZombieType type : values()) {
                if (type.id.equalsIgnoreCase(raw))
                    return type;
            }
            return NORMAL;
        }
    }

    private static final class TypeProfile {
        private final boolean enabled;
        private final int minDay;
        private final int weight;

        private final double hpMult;
        private final double dmgMult;
        private final double speedMult;
        private final double followMult;

        private final double auraRadius;
        private final int auraSpeedAmp;
        private final int auraStrengthAmp;
        private final long auraCooldownTicks;
        private final int auraDurationTicks;

        private final long summonCooldownTicks;
        private final int summonCount;
        private final double summonRadius;
        private final double summonHpMult;
        private final double summonDmgMult;
        private final double summonSpeedMult;

        private final double siegeDamageMult;

        private TypeProfile(
                boolean enabled,
                int minDay,
                int weight,
                double hpMult,
                double dmgMult,
                double speedMult,
                double followMult,
                double auraRadius,
                int auraSpeedAmp,
                int auraStrengthAmp,
                long auraCooldownTicks,
                int auraDurationTicks,
                long summonCooldownTicks,
                int summonCount,
                double summonRadius,
                double summonHpMult,
                double summonDmgMult,
                double summonSpeedMult,
                double siegeDamageMult) {
            this.enabled = enabled;
            this.minDay = minDay;
            this.weight = weight;
            this.hpMult = hpMult;
            this.dmgMult = dmgMult;
            this.speedMult = speedMult;
            this.followMult = followMult;
            this.auraRadius = auraRadius;
            this.auraSpeedAmp = auraSpeedAmp;
            this.auraStrengthAmp = auraStrengthAmp;
            this.auraCooldownTicks = auraCooldownTicks;
            this.auraDurationTicks = auraDurationTicks;
            this.summonCooldownTicks = summonCooldownTicks;
            this.summonCount = summonCount;
            this.summonRadius = summonRadius;
            this.summonHpMult = summonHpMult;
            this.summonDmgMult = summonDmgMult;
            this.summonSpeedMult = summonSpeedMult;
            this.siegeDamageMult = siegeDamageMult;
        }
    }

    private final DeadCyclePlugin plugin;
    private final Random rng = new Random();

    private BukkitTask spawnTask;
    private BukkitTask targetTask;
    private BukkitTask specialTask;

    private final NamespacedKey zombieKey;
    private final NamespacedKey cloneKey;
    private final NamespacedKey zombieTypeKey;

    private boolean pluginSpawning = false;

    private int activeNightDay = 1;
    private double activeNightHp = 16.0;
    private double activeNightDamage = 2.0;
    private double activeNightFollow = 48.0;
    private double activeNightSpeed = 0.23;

    private final Map<ZombieType, TypeProfile> profiles = new EnumMap<>(ZombieType.class);
    private final Map<UUID, Long> nextAbilityAt = new HashMap<>();

    public ZombieWaveManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.zombieKey = new NamespacedKey(plugin, "deadcycle_zombie");
        this.cloneKey = new NamespacedKey(plugin, "kit_clone");
        this.zombieTypeKey = new NamespacedKey(plugin, "deadcycle_zombie_type");
        reload();
    }

    public void reload() {
        loadTypeProfiles();
    }

    public boolean isPluginSpawning() {
        return pluginSpawning;
    }

    public NamespacedKey zombieMarkKey() {
        return zombieKey;
    }

    public int getSiegeDamageFor(Zombie zombie, int baseDamage) {
        if (zombie == null)
            return Math.max(1, baseDamage);

        ZombieType type = getZombieType(zombie);
        TypeProfile profile = profiles.getOrDefault(type, defaultsFor(type));

        double mult = Math.max(0.1, profile.siegeDamageMult);
        return Math.max(1, (int) Math.round(baseDamage * mult));
    }

    public void startNight(int dayCount) {
        stopNight();
        if (!plugin.getConfig().getBoolean("zombies.enabled", true))
            return;

        activeNightDay = Math.max(1, dayCount);
        loadTypeProfiles();

        int base = plugin.getConfig().getInt("zombies.base_per_player", 2);
        int add = plugin.getConfig().getInt("zombies.per_night_add", 1);
        int perPlayer = Math.max(1, base + Math.max(0, activeNightDay - 1) * add);

        double hpBase = plugin.getConfig().getDouble("difficulty.zombie_hp_base", 16.0);
        double hpPer = plugin.getConfig().getDouble("difficulty.zombie_hp_per_day", 1.2);
        double dmgBase = plugin.getConfig().getDouble("difficulty.zombie_dmg_base", 2.0);
        double dmgPer = plugin.getConfig().getDouble("difficulty.zombie_dmg_per_day", 0.2);

        activeNightHp = hpBase + activeNightDay * hpPer;
        activeNightDamage = dmgBase + activeNightDay * dmgPer;
        activeNightFollow = plugin.getConfig().getDouble("zombies.follow_range", 48.0);
        activeNightSpeed = Math.max(0.10, plugin.getConfig().getDouble("zombies.base_speed", 0.23));

        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int online = Bukkit.getOnlinePlayers().size();
            if (online <= 0)
                return;

            if (plugin.base().isEnabled() && plugin.base().hasAnyOnBase()) {
                Location center = plugin.base().getCenter();
                int total = perPlayer * online;

                for (int i = 0; i < total; i++) {
                    Location loc = randomSpawnAroundBaseOutsideRadius(center);
                    if (loc == null)
                        continue;
                    if (plugin.base().isEnabled() && plugin.base().isOnBase(loc))
                        continue;
                    if (plugin.bossDuel().isDuelActive() && plugin.bossDuel().isInsideDuelZone(loc, 20))
                        continue;

                    spawnWaveZombie(loc, activeNightHp, activeNightDamage, activeNightFollow, activeNightSpeed,
                            activeNightDay, null);
                }
                return;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.isOnline() || player.isDead())
                    continue;
                if (plugin.bossDuel().isDuelActive() && plugin.bossDuel().isDuelPlayer(player.getUniqueId()))
                    continue;

                for (int i = 0; i < perPlayer; i++) {
                    Location loc = randomSpawnNearPlayer(player.getLocation());
                    if (loc == null)
                        continue;
                    if (plugin.base().isEnabled() && plugin.base().isOnBase(loc))
                        continue;
                    if (plugin.bossDuel().isDuelActive() && plugin.bossDuel().isInsideDuelZone(loc, 20))
                        continue;

                    spawnWaveZombie(loc, activeNightHp, activeNightDamage, activeNightFollow, activeNightSpeed,
                            activeNightDay, null);
                }
            }
        }, 40L, 20L * 20L);

        int scanSeconds = Math.max(1, plugin.getConfig().getInt("zombies.target_scan_seconds", 2));
        targetTask = Bukkit.getScheduler().runTaskTimer(plugin, this::forceTargets, 20L, scanSeconds * 20L);

        specialTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSpecialTypes, 20L, 20L);
    }

    public void stopNight() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (targetTask != null) {
            targetTask.cancel();
            targetTask = null;
        }
        if (specialTask != null) {
            specialTask.cancel();
            specialTask = null;
        }

        nextAbilityAt.clear();
        despawnZombiesAtDay();
    }

    private void despawnZombiesAtDay() {
        boolean enabled = plugin.getConfig().getBoolean("zombies.despawn_at_day", true);
        if (!enabled)
            return;

        boolean all = plugin.getConfig().getBoolean("zombies.despawn_all_at_day", true);

        World baseWorld = null;
        if (plugin.base() != null) {
            String worldName = plugin.base().getWorldName();
            if (worldName != null && !worldName.isBlank()) {
                baseWorld = Bukkit.getWorld(worldName);
            }
        }

        Iterable<World> worlds = (baseWorld != null) ? java.util.List.of(baseWorld) : Bukkit.getWorlds();

        for (World world : worlds) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Zombie zombie))
                    continue;
                if (zombie.isDead())
                    continue;
                if (isCloneZombie(zombie))
                    continue;

                if (!all && !isOurZombie(zombie))
                    continue;

                zombie.remove();
            }
        }
    }

    private void forceTargets() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Zombie zombie))
                    continue;
                if (!isOurZombie(zombie))
                    continue;
                if (isCloneZombie(zombie))
                    continue;
                if (zombie.isDead())
                    continue;

                LivingEntity currentTarget = zombie.getTarget();
                double followRange = readFollowRange(zombie, activeNightFollow);

                Player nearest = Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.isOnline() && !p.isDead() && p.getWorld() == world)
                        .filter(p -> p.getGameMode() != org.bukkit.GameMode.SPECTATOR)
                        .filter(p -> p.getLocation()
                                .distanceSquared(zombie.getLocation()) <= (followRange * followRange))
                        .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(zombie.getLocation())))
                        .orElse(null);

                if (nearest != null) {
                    if (currentTarget == null) {
                        zombie.setTarget(nearest);
                    } else if (currentTarget.isDead() || (currentTarget instanceof Player p
                            && p.getGameMode() == org.bukkit.GameMode.SPECTATOR)) {
                        zombie.setTarget(nearest);
                    } else {
                        double distToCurrent = currentTarget.getLocation().distanceSquared(zombie.getLocation());
                        double distToNearest = nearest.getLocation().distanceSquared(zombie.getLocation());
                        if (distToNearest < distToCurrent - 9.0) {
                            zombie.setTarget(nearest);
                        }
                    }
                } else if (currentTarget != null && (currentTarget.isDead() ||
                        (currentTarget instanceof Player p && p.getGameMode() == org.bukkit.GameMode.SPECTATOR))) {
                    zombie.setTarget(null);
                }
            }
        }
    }

    private void tickSpecialTypes() {
        TypeProfile screamer = profiles.getOrDefault(ZombieType.SCREAMER, defaultsFor(ZombieType.SCREAMER));
        TypeProfile necromancer = profiles.getOrDefault(ZombieType.NECROMANCER, defaultsFor(ZombieType.NECROMANCER));

        if (!screamer.enabled && !necromancer.enabled)
            return;

        long now = System.currentTimeMillis();

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Zombie zombie))
                    continue;
                if (!isOurZombie(zombie))
                    continue;
                if (isCloneZombie(zombie))
                    continue;
                if (zombie.isDead() || !zombie.isValid())
                    continue;

                ZombieType type = getZombieType(zombie);
                if (type == ZombieType.SCREAMER) {
                    tickScreamerAura(zombie, now, screamer);
                } else if (type == ZombieType.NECROMANCER) {
                    tickNecromancerSummon(zombie, now, necromancer);
                }
            }
        }

        for (UUID entityId : new ArrayList<>(nextAbilityAt.keySet())) {
            Entity entity = Bukkit.getEntity(entityId);
            if (!(entity instanceof Zombie zombie) || zombie.isDead() || !isOurZombie(zombie)) {
                nextAbilityAt.remove(entityId);
            }
        }
    }

    private void tickScreamerAura(Zombie zombie, long now, TypeProfile profile) {
        if (profile.auraRadius <= 0.0)
            return;

        UUID id = zombie.getUniqueId();
        long nextAt = nextAbilityAt.getOrDefault(id, 0L);
        if (now < nextAt)
            return;

        long cooldownMs = Math.max(10L, profile.auraCooldownTicks) * 50L;
        nextAbilityAt.put(id, now + cooldownMs);

        Location center = zombie.getLocation().clone().add(0, 1.0, 0);
        World world = zombie.getWorld();

        world.spawnParticle(Particle.SOUL, center, 28, 0.45, 0.40, 0.45, 0.03);
        world.spawnParticle(Particle.ENCHANT, center, 24, 0.35, 0.35, 0.35, 0.10);
        world.playSound(center, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.9f, 0.65f);

        int effectTicks = Math.max(20, profile.auraDurationTicks);

        for (Entity entity : world.getNearbyEntities(center, profile.auraRadius, profile.auraRadius,
                profile.auraRadius)) {
            if (!(entity instanceof Zombie other))
                continue;
            if (!isOurZombie(other))
                continue;
            if (isCloneZombie(other))
                continue;
            if (other.isDead() || !other.isValid())
                continue;

            other.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, effectTicks,
                    Math.max(0, profile.auraSpeedAmp), true, false, true));
            other.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, effectTicks,
                    Math.max(0, profile.auraStrengthAmp), true, false, true));
        }
    }

    private void tickNecromancerSummon(Zombie zombie, long now, TypeProfile profile) {
        if (profile.summonCount <= 0)
            return;

        UUID id = zombie.getUniqueId();
        long nextAt = nextAbilityAt.getOrDefault(id, 0L);
        if (now < nextAt)
            return;

        Player target = findNearestPlayer(zombie.getLocation(), 20.0);
        if (target == null)
            return;

        long cooldownMs = Math.max(20L, profile.summonCooldownTicks) * 50L;
        nextAbilityAt.put(id, now + cooldownMs);

        Location center = zombie.getLocation().clone().add(0, 1.0, 0);
        World world = zombie.getWorld();

        world.spawnParticle(Particle.REVERSE_PORTAL, center, 34, 0.45, 0.55, 0.45, 0.02);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 14, 0.25, 0.35, 0.25, 0.01);
        world.playSound(center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.85f, 0.85f);

        int count = profile.summonCount + (activeNightDay >= 10 ? 1 : 0);

        for (int i = 0; i < count; i++) {
            Location summonLoc = randomSpawnNear(zombie.getLocation(), 1.5, Math.max(2.0, profile.summonRadius), 10);
            if (summonLoc == null)
                continue;

            Zombie minion = spawnWaveZombie(
                    summonLoc,
                    activeNightHp * Math.max(0.20, profile.summonHpMult),
                    activeNightDamage * Math.max(0.20, profile.summonDmgMult),
                    activeNightFollow,
                    activeNightSpeed * Math.max(0.30, profile.summonSpeedMult),
                    activeNightDay,
                    ZombieType.NORMAL);

            if (minion != null) {
                minion.setTarget(target);
                world.spawnParticle(Particle.SMOKE, minion.getLocation().clone().add(0, 0.8, 0),
                        8, 0.20, 0.25, 0.20, 0.01);
            }
        }
    }

    private Zombie spawnWaveZombie(Location loc, double hp, double dmg, double follow, double speed, int dayCount,
            ZombieType forcedType) {
        World world = loc.getWorld();
        if (world == null)
            return null;

        ZombieType type = (forcedType == null) ? chooseType(dayCount) : forcedType;
        TypeProfile profile = profiles.getOrDefault(type, defaultsFor(type));
        if (!profile.enabled) {
            type = ZombieType.NORMAL;
            profile = profiles.getOrDefault(ZombieType.NORMAL, defaultsFor(ZombieType.NORMAL));
        }

        final ZombieType spawnType = type;
        final TypeProfile spawnProfile = profile;

        double finalHp = Math.max(4.0, hp * Math.max(0.1, spawnProfile.hpMult));
        double finalDmg = Math.max(0.5, dmg * Math.max(0.1, spawnProfile.dmgMult));
        double finalFollow = Math.max(8.0, follow * Math.max(0.1, spawnProfile.followMult));
        double finalSpeed = Math.max(0.10, speed * Math.max(0.1, spawnProfile.speedMult));

        try {
            pluginSpawning = true;

            return world.spawn(loc, Zombie.class, zombie -> {
                zombie.getPersistentDataContainer().set(zombieKey, PersistentDataType.BYTE, (byte) 1);
                zombie.getPersistentDataContainer().set(zombieTypeKey, PersistentDataType.STRING, spawnType.id());

                zombie.setCanPickupItems(false);
                zombie.setBaby(false);

                EntityEquipment eq = zombie.getEquipment();
                if (eq != null) {
                    eq.setItemInMainHand(null);
                    eq.setItemInOffHand(null);
                    eq.setHelmet(null);
                    eq.setChestplate(null);
                    eq.setLeggings(null);
                    eq.setBoots(null);

                    eq.setHelmetDropChance(0f);
                    eq.setChestplateDropChance(0f);
                    eq.setLeggingsDropChance(0f);
                    eq.setBootsDropChance(0f);
                    eq.setItemInMainHandDropChance(0f);
                    eq.setItemInOffHandDropChance(0f);

                    applyTypeVisualGear(eq, spawnType);
                }

                applyStats(zombie, finalHp, finalDmg, finalFollow, finalSpeed, spawnType);
                applySpawnFlavor(zombie, spawnType);
            });
        } finally {
            pluginSpawning = false;
        }
    }

    private void applyTypeVisualGear(EntityEquipment eq, ZombieType type) {
        switch (type) {
            case RUNNER -> eq.setBoots(new ItemStack(Material.CHAINMAIL_BOOTS));
            case BRUTE -> {
                eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            }
            case SCREAMER -> eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            case NECROMANCER -> eq.setHelmet(new ItemStack(Material.WITHER_SKELETON_SKULL));
            case SAPPER -> eq.setHelmet(new ItemStack(Material.TNT));
            default -> {
            }
        }
    }

    private void applyStats(Zombie zombie, double hp, double dmg, double follow, double speed, ZombieType type) {
        Attribute maxHealth = getAttribute("generic.max_health", "max_health");
        if (maxHealth != null) {
            AttributeInstance a = zombie.getAttribute(maxHealth);
            if (a != null) {
                a.setBaseValue(hp);
                zombie.setHealth(Math.min(hp, a.getValue()));
            }
        }

        Attribute attack = getAttribute("generic.attack_damage", "attack_damage");
        if (attack != null) {
            AttributeInstance a = zombie.getAttribute(attack);
            if (a != null)
                a.setBaseValue(dmg);
        }

        Attribute followRange = getAttribute("generic.follow_range", "follow_range");
        if (followRange != null) {
            AttributeInstance a = zombie.getAttribute(followRange);
            if (a != null)
                a.setBaseValue(follow);
        }

        Attribute moveSpeed = getAttribute("generic.movement_speed", "movement_speed");
        if (moveSpeed != null) {
            AttributeInstance a = zombie.getAttribute(moveSpeed);
            if (a != null)
                a.setBaseValue(speed);
        }

        if (type == ZombieType.RUNNER) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60 * 60, 0, true, false, false));
        }

        if (type == ZombieType.BRUTE) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 60 * 60, 0, true, false, false));
            Attribute kbRes = getAttribute("generic.knockback_resistance", "knockback_resistance");
            if (kbRes != null) {
                AttributeInstance a = zombie.getAttribute(kbRes);
                if (a != null)
                    a.setBaseValue(0.75);
            }
        }
    }

    private void applySpawnFlavor(Zombie zombie, ZombieType type) {
        Location center = zombie.getLocation().clone().add(0, 1.0, 0);
        World world = zombie.getWorld();

        switch (type) {
            case RUNNER -> {
                world.spawnParticle(Particle.CLOUD, center, 10, 0.28, 0.20, 0.28, 0.02);
                world.playSound(center, Sound.ENTITY_ZOMBIE_AMBIENT, 0.55f, 1.55f);
            }
            case BRUTE -> {
                world.spawnParticle(Particle.SMOKE, center, 14, 0.28, 0.25, 0.28, 0.01);
                world.playSound(center, Sound.BLOCK_ANVIL_LAND, 0.50f, 0.85f);
            }
            case SCREAMER -> {
                world.spawnParticle(Particle.SOUL, center, 16, 0.30, 0.30, 0.30, 0.02);
                world.playSound(center, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.60f, 1.20f);
            }
            case NECROMANCER -> {
                world.spawnParticle(Particle.ENCHANT, center, 18, 0.30, 0.35, 0.30, 0.08);
                world.playSound(center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.60f, 0.95f);
            }
            case SAPPER -> {
                world.spawnParticle(Particle.FLAME, center, 10, 0.22, 0.22, 0.22, 0.01);
                world.playSound(center, Sound.ENTITY_CREEPER_PRIMED, 0.70f, 0.90f);
            }
            default -> {
            }
        }
    }

    private ZombieType chooseType(int dayCount) {
        TypeProfile normal = profiles.getOrDefault(ZombieType.NORMAL, defaultsFor(ZombieType.NORMAL));
        int normalWeight = Math.max(0, normal.weight);
        int total = normalWeight;

        List<ZombieType> eligible = new ArrayList<>();
        for (ZombieType type : ZombieType.values()) {
            if (type == ZombieType.NORMAL)
                continue;

            TypeProfile profile = profiles.getOrDefault(type, defaultsFor(type));
            if (!profile.enabled)
                continue;
            if (dayCount < profile.minDay)
                continue;
            if (profile.weight <= 0)
                continue;

            eligible.add(type);
            total += profile.weight;
        }

        if (total <= 0)
            return ZombieType.NORMAL;

        int roll = rng.nextInt(total);
        if (roll < normalWeight)
            return ZombieType.NORMAL;

        int cursor = normalWeight;
        for (ZombieType type : eligible) {
            TypeProfile profile = profiles.getOrDefault(type, defaultsFor(type));
            cursor += profile.weight;
            if (roll < cursor)
                return type;
        }

        return ZombieType.NORMAL;
    }

    private Player findNearestPlayer(Location center, double radius) {
        if (center == null || center.getWorld() == null)
            return null;

        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.isOnline() && !p.isDead() && p.getWorld() == center.getWorld())
                .filter(p -> p.getGameMode() != org.bukkit.GameMode.SPECTATOR)
                .filter(p -> p.getLocation().distanceSquared(center) <= radius * radius)
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(center)))
                .orElse(null);
    }

    private boolean isOurZombie(Entity entity) {
        Byte mark = entity.getPersistentDataContainer().get(zombieKey, PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private boolean isCloneZombie(Entity entity) {
        Byte mark = entity.getPersistentDataContainer().get(cloneKey, PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private ZombieType getZombieType(Zombie zombie) {
        String raw = zombie.getPersistentDataContainer().get(zombieTypeKey, PersistentDataType.STRING);
        return ZombieType.byId(raw);
    }

    private double readFollowRange(Zombie zombie, double fallback) {
        Attribute follow = getAttribute("generic.follow_range", "follow_range");
        if (follow == null)
            return fallback;

        AttributeInstance a = zombie.getAttribute(follow);
        if (a == null)
            return fallback;

        return Math.max(8.0, a.getValue());
    }

    private Location randomSpawnAroundBaseOutsideRadius(Location baseCenter) {
        if (baseCenter == null)
            return null;

        World world = baseCenter.getWorld();
        if (world == null)
            return null;

        int baseRadius = plugin.base().getRadius();

        int minOutside = plugin.getConfig().getInt("zombies.base_spawn_ring.min_outside", 10);
        int maxOutside = plugin.getConfig().getInt("zombies.base_spawn_ring.max_outside", 28);

        int minR = Math.max(1, baseRadius + minOutside);
        int maxR = Math.max(minR + 1, baseRadius + maxOutside);

        int yOff = plugin.getConfig().getInt("zombies.spawn_y_offset", 2);

        double angle = rng.nextDouble() * Math.PI * 2;
        double radius = minR + rng.nextDouble() * (maxR - minR);

        int x = (int) Math.round(baseCenter.getX() + Math.cos(angle) * radius);
        int z = (int) Math.round(baseCenter.getZ() + Math.sin(angle) * radius);
        int y = world.getHighestBlockYAt(x, z) + yOff;

        Location loc = new Location(world, x + 0.5, y, z + 0.5);
        if (loc.getBlock().isLiquid())
            return null;
        if (plugin.base().isEnabled() && plugin.base().isOnBase(loc))
            return null;

        return loc;
    }

    private Location randomSpawnNearPlayer(Location center) {
        int minR = plugin.getConfig().getInt("zombies.spawn_radius_min", 12);
        int maxR = plugin.getConfig().getInt("zombies.spawn_radius_max", 28);

        return randomSpawnNear(center, Math.max(1.0, minR), Math.max(minR + 1.0, maxR), 8);
    }

    private Location randomSpawnNear(Location center, double minRadius, double maxRadius, int attempts) {
        World world = center.getWorld();
        if (world == null)
            return null;

        int yOff = plugin.getConfig().getInt("zombies.spawn_y_offset", 2);
        double minR = Math.max(0.5, minRadius);
        double maxR = Math.max(minR + 0.1, maxRadius);

        for (int attempt = 0; attempt < attempts; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double radius = minR + rng.nextDouble() * (maxR - minR);

            int x = (int) Math.round(center.getX() + Math.cos(angle) * radius);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * radius);
            int y = world.getHighestBlockYAt(x, z) + yOff;

            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (loc.getBlock().isLiquid())
                continue;
            if (plugin.base().isEnabled() && plugin.base().isOnBase(loc))
                continue;
            return loc;
        }

        return null;
    }

    private void loadTypeProfiles() {
        profiles.clear();
        for (ZombieType type : ZombieType.values()) {
            profiles.put(type, readProfile(type));
        }
    }

    private TypeProfile readProfile(ZombieType type) {
        String path = "zombies.types." + type.id();
        TypeProfile d = defaultsFor(type);

        return new TypeProfile(
                plugin.getConfig().getBoolean(path + ".enabled", d.enabled),
                Math.max(1, plugin.getConfig().getInt(path + ".min_day", d.minDay)),
                Math.max(0, plugin.getConfig().getInt(path + ".weight", d.weight)),
                Math.max(0.1, plugin.getConfig().getDouble(path + ".hp_mult", d.hpMult)),
                Math.max(0.1, plugin.getConfig().getDouble(path + ".damage_mult", d.dmgMult)),
                Math.max(0.1, plugin.getConfig().getDouble(path + ".speed_mult", d.speedMult)),
                Math.max(0.1, plugin.getConfig().getDouble(path + ".follow_mult", d.followMult)),
                Math.max(0.0, plugin.getConfig().getDouble(path + ".aura.radius", d.auraRadius)),
                Math.max(0, plugin.getConfig().getInt(path + ".aura.speed_amp", d.auraSpeedAmp)),
                Math.max(0, plugin.getConfig().getInt(path + ".aura.strength_amp", d.auraStrengthAmp)),
                Math.max(10L, plugin.getConfig().getLong(path + ".aura.cooldown_ticks", d.auraCooldownTicks)),
                Math.max(20, plugin.getConfig().getInt(path + ".aura.duration_ticks", d.auraDurationTicks)),
                Math.max(20L, plugin.getConfig().getLong(path + ".summon.cooldown_ticks", d.summonCooldownTicks)),
                Math.max(0, plugin.getConfig().getInt(path + ".summon.count", d.summonCount)),
                Math.max(1.0, plugin.getConfig().getDouble(path + ".summon.radius", d.summonRadius)),
                Math.max(0.1, plugin.getConfig().getDouble(path + ".summon.hp_mult", d.summonHpMult)),
                Math.max(0.1, plugin.getConfig().getDouble(path + ".summon.damage_mult", d.summonDmgMult)),
                Math.max(0.1, plugin.getConfig().getDouble(path + ".summon.speed_mult", d.summonSpeedMult)),
                Math.max(0.1, plugin.getConfig().getDouble(path + ".siege_damage_mult", d.siegeDamageMult)));
    }

    private TypeProfile defaultsFor(ZombieType type) {
        return switch (type) {
            case NORMAL -> new TypeProfile(
                    true, 1, 70,
                    1.00, 1.00, 1.00, 1.00,
                    0.0, 0, 0, 60, 40,
                    120, 0, 4.0, 0.7, 0.7, 1.0,
                    1.0);
            case RUNNER -> new TypeProfile(
                    true, 2, 16,
                    0.72, 0.85, 1.34, 1.08,
                    0.0, 0, 0, 60, 40,
                    120, 0, 4.0, 0.7, 0.7, 1.0,
                    1.0);
            case BRUTE -> new TypeProfile(
                    true, 4, 10,
                    2.15, 1.45, 0.82, 0.95,
                    0.0, 0, 0, 60, 40,
                    120, 0, 4.0, 0.7, 0.7, 1.0,
                    1.0);
            case SCREAMER -> new TypeProfile(
                    true, 5, 7,
                    0.95, 1.00, 1.00, 1.15,
                    7.5, 0, 0, 80, 60,
                    120, 0, 4.0, 0.7, 0.7, 1.0,
                    1.0);
            case NECROMANCER -> new TypeProfile(
                    true, 6, 5,
                    1.10, 0.92, 0.96, 1.20,
                    0.0, 0, 0, 60, 40,
                    140, 2, 5.5, 0.62, 0.76, 1.02,
                    1.0);
            case SAPPER -> new TypeProfile(
                    true, 7, 8,
                    1.35, 0.95, 0.92, 1.00,
                    0.0, 0, 0, 60, 40,
                    120, 0, 4.0, 0.7, 0.7, 1.0,
                    2.4);
        };
    }

    private Attribute getAttribute(String... keys) {
        for (String key : keys) {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (attr != null)
                return attr;
        }
        return null;
    }
}
