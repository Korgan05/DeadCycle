package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class CloneKitManager implements Listener {

    public enum CloneMode {
        BASE_DEFENSE("§aЗащита базы"),
        SELF_DEFENSE("§bЗащита меня"),
        ATTACK("§cАтака");

        private final String display;

        CloneMode(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public CloneMode next() {
            return switch (this) {
                case BASE_DEFENSE -> SELF_DEFENSE;
                case SELF_DEFENSE -> ATTACK;
                case ATTACK -> BASE_DEFENSE;
            };
        }
    }

    private final DeadCyclePlugin plugin;
    private final Random rng = new Random();

    private final NamespacedKey cloneKey;
    private final NamespacedKey cloneOwnerKey;

    private final Map<UUID, Set<UUID>> ownerToClones = new HashMap<>();
    private final Map<UUID, CloneMode> ownerModes = new HashMap<>();

    private final Map<UUID, UUID> lastHitOwnerByZombie = new HashMap<>();
    private final Map<UUID, Long> lastHitAtByZombie = new HashMap<>();

    private final Map<UUID, Double> cloneMana = new HashMap<>();
    private final Map<UUID, Double> cloneHealCostAccumulator = new HashMap<>();
    private final Map<UUID, Long> cloneDodgeCooldownUntil = new HashMap<>();
    private final Map<UUID, Location> attackRoamTarget = new HashMap<>();
    private final Map<UUID, Long> attackRoamRetargetAt = new HashMap<>();

    private BukkitTask aiTask;
    private boolean cloneSpawning = false;

    private boolean enabled;
    private int maxClonesBase;
    private int extraCloneEveryLevels;

    private double hpBase;
    private double hpPerLevel;
    private double damageBase;
    private double damagePerLevel;
    private double speedBase;
    private double speedPerLevel;
    private double followRange;

    private double protectRadius;
    private double attackRadius;
    private int leashOwnerDistance;
    private int leashBaseDistance;

    private int spawnRadiusMin;
    private int spawnRadiusMax;

    private long cloneHitCreditMs;

    private double manaBase;
    private double manaPerLevel;
    private double manaRegenPerSecond;

    private double selfHealTriggerHealthRatio;
    private double selfHealManaPerSecond;
    private int selfHealAmplifier;

    private double cloneDodgeChance;
    private int cloneDodgeManaCost;
    private long cloneDodgeCooldownMs;

    private int attackRoamRadiusMin;
    private int attackRoamRadiusMax;

    public CloneKitManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.cloneKey = new NamespacedKey(plugin, "kit_clone");
        this.cloneOwnerKey = new NamespacedKey(plugin, "kit_clone_owner");
        reload();
        startTask();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("cloner.enabled", true);

        this.maxClonesBase = Math.max(1, plugin.getConfig().getInt("cloner.max_clones_base", 1));
        this.extraCloneEveryLevels = Math.max(1,
                plugin.getConfig().getInt("cloner.max_clones_plus_every_levels", 2));

        this.hpBase = Math.max(10.0, plugin.getConfig().getDouble("cloner.hp_base", 22.0));
        this.hpPerLevel = Math.max(0.0, plugin.getConfig().getDouble("cloner.hp_per_level", 1.2));
        this.damageBase = Math.max(1.0, plugin.getConfig().getDouble("cloner.damage_base", 2.8));
        this.damagePerLevel = Math.max(0.0, plugin.getConfig().getDouble("cloner.damage_per_level", 0.35));
        this.speedBase = Math.max(0.18, plugin.getConfig().getDouble("cloner.speed_base", 0.26));
        this.speedPerLevel = Math.max(0.0, plugin.getConfig().getDouble("cloner.speed_per_level", 0.01));
        this.followRange = Math.max(12.0, plugin.getConfig().getDouble("cloner.follow_range", 34.0));

        this.protectRadius = Math.max(4.0, plugin.getConfig().getDouble("cloner.protect_radius", 16.0));
        this.attackRadius = Math.max(6.0, plugin.getConfig().getDouble("cloner.attack_radius", 28.0));
        this.leashOwnerDistance = Math.max(8, plugin.getConfig().getInt("cloner.leash_owner_distance", 14));
        this.leashBaseDistance = Math.max(8, plugin.getConfig().getInt("cloner.leash_base_distance", 18));

        this.spawnRadiusMin = Math.max(1, plugin.getConfig().getInt("cloner.spawn_radius_min", 1));
        this.spawnRadiusMax = Math.max(spawnRadiusMin + 1,
                plugin.getConfig().getInt("cloner.spawn_radius_max", 4));

        this.cloneHitCreditMs = Math.max(1000L, plugin.getConfig().getLong("cloner.clone_hit_credit_ms", 5000L));

        this.manaBase = Math.max(10.0, plugin.getConfig().getDouble("cloner.mana_base", 55.0));
        this.manaPerLevel = Math.max(0.0, plugin.getConfig().getDouble("cloner.mana_per_level", 4.0));
        this.manaRegenPerSecond = Math.max(0.0, plugin.getConfig().getDouble("cloner.mana_regen_per_second", 0.35));

        this.selfHealTriggerHealthRatio = Math.max(0.10,
                Math.min(0.99, plugin.getConfig().getDouble("cloner.self_heal_trigger_health_ratio", 0.92)));
        this.selfHealManaPerSecond = Math.max(0.0,
                plugin.getConfig().getDouble("cloner.self_heal_mana_per_second",
                        plugin.getConfig().getDouble("special_skills.regen_item.mana_per_second", 1.0)));
        this.selfHealAmplifier = Math.max(0,
                plugin.getConfig().getInt("cloner.self_heal_amplifier",
                        plugin.getConfig().getInt("special_skills.regen_item.potion_amplifier", 1)));

        this.cloneDodgeChance = Math.max(0.0,
                Math.min(1.0, plugin.getConfig().getDouble("cloner.dodge_chance",
                        plugin.getConfig().getDouble("special_skills.auto_dodge.dodge_chance", 0.25))));
        this.cloneDodgeManaCost = Math.max(0,
                plugin.getConfig().getInt("cloner.dodge_mana_cost",
                        plugin.getConfig().getInt("special_skills.auto_dodge.mana_cost", 5)));
        this.cloneDodgeCooldownMs = Math.max(100L,
                plugin.getConfig().getLong("cloner.dodge_cooldown_ms", 850L));

        this.attackRoamRadiusMin = Math.max(2, plugin.getConfig().getInt("cloner.attack_roam_radius_min", 6));
        this.attackRoamRadiusMax = Math.max(attackRoamRadiusMin + 1,
                plugin.getConfig().getInt("cloner.attack_roam_radius_max", 14));

        if (!enabled) {
            removeAllClones();
        }
    }

    public void shutdown() {
        if (aiTask != null) {
            aiTask.cancel();
            aiTask = null;
        }
        removeAllClones();
        ownerModes.clear();
        ownerToClones.clear();
        lastHitOwnerByZombie.clear();
        lastHitAtByZombie.clear();
        cloneMana.clear();
        cloneHealCostAccumulator.clear();
        cloneDodgeCooldownUntil.clear();
        attackRoamTarget.clear();
        attackRoamRetargetAt.clear();
    }

    public void resetAll() {
        removeAllClones();
        ownerModes.clear();
        ownerToClones.clear();
        lastHitOwnerByZombie.clear();
        lastHitAtByZombie.clear();
        cloneMana.clear();
        cloneHealCostAccumulator.clear();
        cloneDodgeCooldownUntil.clear();
        attackRoamTarget.clear();
        attackRoamRetargetAt.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public NamespacedKey cloneMarkKey() {
        return cloneKey;
    }

    public boolean isCloneSpawning() {
        return cloneSpawning;
    }

    public String getSummonError(Player owner) {
        if (!enabled)
            return "§cКлон-кит временно отключён.";
        if (owner == null || !owner.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.CLONER)
            return "§cЭтот навык доступен только киту Клонер.";
        if (!hasFreeCloneSlot(owner))
            return "§cЛимит клонов достигнут.";
        return null;
    }

    public boolean hasFreeCloneSlot(Player owner) {
        return getCloneCount(owner.getUniqueId()) < getMaxClones(owner);
    }

    public int getCloneCount(UUID ownerId) {
        cleanupOwnerClones(ownerId);
        Set<UUID> set = ownerToClones.get(ownerId);
        return set == null ? 0 : set.size();
    }

    public double getCloneMaxMana(UUID ownerId) {
        int level = plugin.progress().getClonerLevel(ownerId);
        return manaBase + (Math.max(0, level - 1) * manaPerLevel);
    }

    public int getMaxClones(Player owner) {
        int level = plugin.progress().getClonerLevel(owner.getUniqueId());
        int extra = Math.max(0, (level - 1) / extraCloneEveryLevels);
        return maxClonesBase + extra;
    }

    public CloneMode getMode(UUID ownerId) {
        return ownerModes.getOrDefault(ownerId, CloneMode.SELF_DEFENSE);
    }

    public void cycleMode(Player owner) {
        if (owner == null || !owner.isOnline())
            return;
        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.CLONER) {
            owner.sendMessage("§cЭтот навык доступен только киту Клонер.");
            return;
        }

        CloneMode next = getMode(owner.getUniqueId()).next();
        ownerModes.put(owner.getUniqueId(), next);
        owner.sendMessage("§d[Клоны] §7Режим: " + next.display());
        owner.playSound(owner.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
    }

    public boolean summonClone(Player owner) {
        String error = getSummonError(owner);
        if (error != null) {
            owner.sendMessage(error);
            return false;
        }

        UUID ownerId = owner.getUniqueId();
        cleanupOwnerClones(ownerId);

        Location spawnLoc = findSpawnNear(owner.getLocation());
        if (spawnLoc == null) {
            owner.sendMessage("§cНе удалось найти место для призыва клона.");
            return false;
        }

        Mob clone = spawnClone(owner, spawnLoc);
        if (clone == null) {
            owner.sendMessage("§cПризыв не удался.");
            return false;
        }

        ownerToClones.computeIfAbsent(ownerId, k -> new HashSet<>()).add(clone.getUniqueId());
        ownerModes.putIfAbsent(ownerId, CloneMode.SELF_DEFENSE);

        int now = getCloneCount(ownerId);
        int max = getMaxClones(owner);
        owner.sendMessage("§b[Клоны] §fПризван клон §7(" + now + "/" + max + ")");
        owner.playSound(owner.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 1.3f);

        UUID cloneId = clone.getUniqueId();
        cloneMana.put(cloneId, getCloneMaxMana(ownerId));
        cloneHealCostAccumulator.put(cloneId, 0.0);
        cloneDodgeCooldownUntil.put(cloneId, 0L);

        return true;
    }

    public void onKitAssigned(Player owner, KitManager.Kit kit) {
        if (owner == null)
            return;

        UUID ownerId = owner.getUniqueId();
        if (kit != KitManager.Kit.CLONER) {
            removeOwnerClones(ownerId);
            ownerModes.remove(ownerId);
            return;
        }

        ownerModes.putIfAbsent(ownerId, CloneMode.SELF_DEFENSE);
    }

    private void startTask() {
        if (aiTask != null)
            aiTask.cancel();

        aiTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAi, 10L, 10L);
    }

    private void tickAi() {
        if (!enabled)
            return;

        for (UUID ownerId : new ArrayList<>(ownerToClones.keySet())) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (!isActiveOwner(owner)) {
                removeOwnerClones(ownerId);
                continue;
            }

            List<Mob> clones = getActiveClones(ownerId);
            if (clones.isEmpty()) {
                ownerToClones.remove(ownerId);
                continue;
            }

            CloneMode mode = getMode(ownerId);
            for (Mob clone : clones) {
                tickCloneMana(clone, ownerId);
                tickCloneSelfHeal(clone, ownerId);
                controlClone(clone, owner, mode);
            }
        }

        cleanupHitMaps();
    }

    private void cleanupHitMaps() {
        long now = System.currentTimeMillis();
        for (UUID zombieId : new ArrayList<>(lastHitAtByZombie.keySet())) {
            long at = lastHitAtByZombie.getOrDefault(zombieId, 0L);
            if (now - at > cloneHitCreditMs) {
                lastHitAtByZombie.remove(zombieId);
                lastHitOwnerByZombie.remove(zombieId);
            }
        }
    }

    private boolean isActiveOwner(Player owner) {
        return owner != null
                && owner.isOnline()
                && !owner.isDead()
                && owner.getGameMode() != GameMode.SPECTATOR
                && plugin.kit().getKit(owner.getUniqueId()) == KitManager.Kit.CLONER;
    }

    private List<Mob> getActiveClones(UUID ownerId) {
        Set<UUID> ids = ownerToClones.get(ownerId);
        if (ids == null || ids.isEmpty())
            return Collections.emptyList();

        List<Mob> result = new ArrayList<>();

        for (UUID id : new ArrayList<>(ids)) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob clone) || !clone.isValid() || clone.isDead() || !isClone(clone)) {
                ids.remove(id);
                clearCloneRuntime(id);
                continue;
            }

            UUID cloneOwner = readOwnerId(clone);
            if (cloneOwner == null || !cloneOwner.equals(ownerId)) {
                ids.remove(id);
                clearCloneRuntime(id);
                continue;
            }

            result.add(clone);
        }

        if (ids.isEmpty()) {
            ownerToClones.remove(ownerId);
        }

        return result;
    }

    private void cleanupOwnerClones(UUID ownerId) {
        getActiveClones(ownerId);
    }

    private void removeAllClones() {
        for (UUID ownerId : new ArrayList<>(ownerToClones.keySet())) {
            removeOwnerClones(ownerId);
        }
    }

    private void removeOwnerClones(UUID ownerId) {
        Set<UUID> ids = ownerToClones.remove(ownerId);
        if (ids == null)
            return;

        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid() && !entity.isDead()) {
                entity.remove();
            }
            clearCloneRuntime(id);
        }
    }

    private void clearCloneRuntime(UUID cloneId) {
        cloneMana.remove(cloneId);
        cloneHealCostAccumulator.remove(cloneId);
        cloneDodgeCooldownUntil.remove(cloneId);
        attackRoamTarget.remove(cloneId);
        attackRoamRetargetAt.remove(cloneId);
    }

    private Mob spawnClone(Player owner, Location loc) {
        World world = loc.getWorld();
        if (world == null)
            return null;

        int level = plugin.progress().getClonerLevel(owner.getUniqueId());
        double hp = hpBase + Math.max(0, level - 1) * hpPerLevel;
        double damage = damageBase + Math.max(0, level - 1) * damagePerLevel;
        double speed = speedBase + Math.max(0, level - 1) * speedPerLevel;

        try {
            cloneSpawning = true;
            return world.spawn(loc, Vindicator.class, v -> {
                v.getPersistentDataContainer().set(cloneKey, PersistentDataType.BYTE, (byte) 1);
                v.getPersistentDataContainer().set(cloneOwnerKey, PersistentDataType.STRING,
                        owner.getUniqueId().toString());

                v.setCustomName("§bКлон-разбойник §7" + owner.getName());
                v.setCustomNameVisible(true);
                v.setCanPickupItems(false);
                v.setRemoveWhenFarAway(false);
                v.setPersistent(true);
                v.setPatrolLeader(false);
                v.setCanJoinRaid(false);

                v.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 60 * 60, 0, true, false,
                        false));

                applyAttributes(v, hp, damage, speed, followRange);
                copyEquipmentFromOwner(owner, v);
            });
        } finally {
            cloneSpawning = false;
        }
    }

    private void applyAttributes(Mob clone, double hp, double damage, double speed, double follow) {
        Attribute maxHealthAttr = getAttribute("generic.max_health", "max_health");
        if (maxHealthAttr != null) {
            AttributeInstance maxHealth = clone.getAttribute(maxHealthAttr);
            if (maxHealth != null) {
                maxHealth.setBaseValue(hp);
                if (clone instanceof LivingEntity living) {
                    living.setHealth(Math.min(hp, maxHealth.getValue()));
                }
            }
        }

        Attribute attackAttr = getAttribute("generic.attack_damage", "attack_damage");
        if (attackAttr != null) {
            AttributeInstance attack = clone.getAttribute(attackAttr);
            if (attack != null) {
                attack.setBaseValue(damage);
            }
        }

        Attribute speedAttr = getAttribute("generic.movement_speed", "movement_speed");
        if (speedAttr != null) {
            AttributeInstance movement = clone.getAttribute(speedAttr);
            if (movement != null) {
                movement.setBaseValue(speed);
            }
        }

        Attribute followAttr = getAttribute("generic.follow_range", "follow_range");
        if (followAttr != null) {
            AttributeInstance followRangeInst = clone.getAttribute(followAttr);
            if (followRangeInst != null) {
                followRangeInst.setBaseValue(follow);
            }
        }
    }

    private void copyEquipmentFromOwner(Player owner, Mob clone) {
        EntityEquipment equipment = clone.getEquipment();
        if (equipment == null)
            return;

        ItemStack ownerMain = owner.getInventory().getItemInMainHand();
        ItemStack main = null;
        if (ownerMain != null && ownerMain.getType() != Material.AIR && !plugin.kit().isSkillActivator(ownerMain)) {
            main = ownerMain.clone();
            main.setAmount(1);
        }

        equipment.setItemInMainHand(main != null ? main : new ItemStack(Material.IRON_AXE));
        equipment.setItemInOffHand(null);

        ItemStack[] armor = owner.getInventory().getArmorContents();
        if (armor != null && armor.length == 4) {
            equipment.setBoots(copySingle(armor[0]));
            equipment.setLeggings(copySingle(armor[1]));
            equipment.setChestplate(copySingle(armor[2]));
            equipment.setHelmet(copySingle(armor[3]));
        }

        equipment.setItemInMainHandDropChance(0f);
        equipment.setItemInOffHandDropChance(0f);
        equipment.setHelmetDropChance(0f);
        equipment.setChestplateDropChance(0f);
        equipment.setLeggingsDropChance(0f);
        equipment.setBootsDropChance(0f);
    }

    private ItemStack copySingle(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR)
            return null;
        ItemStack copy = stack.clone();
        copy.setAmount(1);
        return copy;
    }

    private void controlClone(Mob clone, Player owner, CloneMode mode) {
        if (!clone.getWorld().getUID().equals(owner.getWorld().getUID())) {
            Location sync = findSpawnNear(owner.getLocation());
            clone.teleport(sync != null ? sync : owner.getLocation());
        }

        clone.setFireTicks(0);

        switch (mode) {
            case BASE_DEFENSE -> controlBaseDefense(clone, owner);
            case SELF_DEFENSE -> controlSelfDefense(clone, owner);
            case ATTACK -> controlAttack(clone, owner);
        }
    }

    private void controlBaseDefense(Mob clone, Player owner) {
        if (!plugin.base().isEnabled() || plugin.base().getCenter() == null) {
            controlSelfDefense(clone, owner);
            return;
        }

        Location baseCenter = plugin.base().getCenter();
        if (!baseCenter.getWorld().getUID().equals(clone.getWorld().getUID())) {
            return;
        }

        LivingEntity enemy = findNearestZombieEnemy(baseCenter, protectRadius);
        if (enemy != null) {
            clone.setTarget(enemy);
            return;
        }

        moveTowards(clone, baseCenter, 0.28);
    }

    private void controlSelfDefense(Mob clone, Player owner) {
        LivingEntity enemy = findThreatNearOwner(owner, owner.getUniqueId(), protectRadius);
        if (enemy != null) {
            clone.setTarget(enemy);
            return;
        }

        moveTowards(clone, owner.getLocation(), 0.30);
    }

    private void controlAttack(Mob clone, Player owner) {
        LivingEntity enemy = findNearestZombieEnemy(clone.getLocation(), attackRadius);
        if (enemy != null) {
            clone.setTarget(enemy);
            attackRoamTarget.remove(clone.getUniqueId());
            attackRoamRetargetAt.remove(clone.getUniqueId());
            return;
        }

        Location roamTarget = resolveAttackRoamTarget(clone);
        if (roamTarget != null) {
            moveTowards(clone, roamTarget, 0.26);
        }
    }

    private Location resolveAttackRoamTarget(Mob clone) {
        UUID cloneId = clone.getUniqueId();
        long now = System.currentTimeMillis();

        Location target = attackRoamTarget.get(cloneId);
        long retargetAt = attackRoamRetargetAt.getOrDefault(cloneId, 0L);

        boolean invalid = target == null
                || target.getWorld() == null
                || !target.getWorld().getUID().equals(clone.getWorld().getUID())
                || now >= retargetAt
                || clone.getLocation().distanceSquared(target) <= 2.0;

        if (invalid) {
            target = findAttackRoamLocation(clone.getLocation());
            if (target == null)
                return null;

            attackRoamTarget.put(cloneId, target);
            attackRoamRetargetAt.put(cloneId, now + 4500L + rng.nextInt(3500));
        }

        return target;
    }

    private Location findAttackRoamLocation(Location center) {
        World world = center.getWorld();
        if (world == null)
            return null;

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double radius = attackRoamRadiusMin + rng.nextDouble() * (attackRoamRadiusMax - attackRoamRadiusMin);

            int x = (int) Math.round(center.getX() + Math.cos(angle) * radius);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * radius);
            int y = world.getHighestBlockYAt(x, z) + 1;

            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (loc.getBlock().isLiquid())
                continue;
            return loc;
        }

        return null;
    }

    private LivingEntity findThreatNearOwner(Player owner, UUID ownerId, double radius) {
        LivingEntity directThreat = null;
        double bestThreatDist = Double.MAX_VALUE;

        for (Entity entity : owner.getWorld().getNearbyEntities(owner.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isEnemy(living, ownerId))
                continue;

            if (living instanceof Mob mob && mob.getTarget() != null && mob.getTarget().getUniqueId().equals(ownerId)) {
                double dist = living.getLocation().distanceSquared(owner.getLocation());
                if (dist < bestThreatDist) {
                    bestThreatDist = dist;
                    directThreat = living;
                }
            }
        }

        if (directThreat != null)
            return directThreat;

        return findNearestEnemy(owner.getLocation(), radius, ownerId);
    }

    private LivingEntity findNearestZombieEnemy(Location center, double radius) {
        if (center.getWorld() == null)
            return null;

        LivingEntity best = null;
        double bestDist = radius * radius;

        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof Zombie zombie))
                continue;
            if (zombie.isDead() || !zombie.isValid())
                continue;
            if (isClone(zombie))
                continue;

            double dist = zombie.getLocation().distanceSquared(center);
            if (dist < bestDist) {
                bestDist = dist;
                best = zombie;
            }
        }

        return best;
    }

    private LivingEntity findNearestEnemy(Location center, double radius, UUID ownerId) {
        if (center.getWorld() == null)
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

        if (target instanceof Zombie zombie) {
            if (isClone(zombie))
                return false;
            return true;
        }

        return target instanceof Monster;
    }

    private void moveTowards(Mob clone, Location target, double speed) {
        if (target == null || target.getWorld() == null)
            return;

        if (!clone.getWorld().getUID().equals(target.getWorld().getUID())) {
            return;
        }

        double distSq = clone.getLocation().distanceSquared(target);
        if (distSq <= 4.0)
            return;

        Vector dir = target.toVector().subtract(clone.getLocation().toVector()).setY(0.0);
        if (dir.lengthSquared() < 0.0001)
            return;

        Vector push = dir.normalize().multiply(speed);
        Vector vel = clone.getVelocity();
        clone.setVelocity(new Vector(
                vel.getX() * 0.35 + push.getX(),
                Math.max(-0.08, Math.min(0.25, vel.getY())),
                vel.getZ() * 0.35 + push.getZ()));
    }

    private void tickCloneMana(Mob clone, UUID ownerId) {
        UUID cloneId = clone.getUniqueId();
        double max = getCloneMaxMana(ownerId);
        double current = cloneMana.getOrDefault(cloneId, max);
        current = Math.min(max, current + (manaRegenPerSecond * 0.5));
        cloneMana.put(cloneId, current);
    }

    private void tickCloneSelfHeal(Mob clone, UUID ownerId) {
        if (!(clone instanceof LivingEntity living))
            return;
        if (plugin.specialSkills() == null)
            return;

        boolean canHeal = plugin.specialSkills().isRegenUnlocked(ownerId)
                || plugin.specialSkills().isAutoRegenUnlocked(ownerId);
        if (!canHeal)
            return;

        AttributeInstance maxHealthAttr = living.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null)
            return;

        double maxHealth = Math.max(1.0, maxHealthAttr.getValue());
        if (living.getHealth() >= maxHealth * selfHealTriggerHealthRatio)
            return;

        UUID cloneId = clone.getUniqueId();
        double toConsumeAcc = cloneHealCostAccumulator.getOrDefault(cloneId, 0.0) + (selfHealManaPerSecond * 0.5);
        int toSpend = (int) Math.floor(toConsumeAcc);

        if (toSpend <= 0) {
            cloneHealCostAccumulator.put(cloneId, toConsumeAcc);
            return;
        }

        double mana = cloneMana.getOrDefault(cloneId, getCloneMaxMana(ownerId));
        if (mana < toSpend) {
            cloneHealCostAccumulator.put(cloneId, Math.min(1.0, toConsumeAcc));
            return;
        }

        mana -= toSpend;
        toConsumeAcc -= toSpend;

        cloneMana.put(cloneId, Math.max(0.0, mana));
        cloneHealCostAccumulator.put(cloneId, Math.max(0.0, toConsumeAcc));

        living.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 40, selfHealAmplifier, true, false,
                true));
    }

    private boolean tryCloneDodge(Mob clone, UUID ownerId) {
        if (plugin.specialSkills() == null)
            return false;
        if (!plugin.specialSkills().isAutoDodgeUnlocked(ownerId))
            return false;
        if (cloneDodgeChance <= 0.0 || cloneDodgeManaCost <= 0)
            return false;

        UUID cloneId = clone.getUniqueId();
        long now = System.currentTimeMillis();
        long until = cloneDodgeCooldownUntil.getOrDefault(cloneId, 0L);
        if (now < until)
            return false;

        if (Math.random() > cloneDodgeChance)
            return false;

        double mana = cloneMana.getOrDefault(cloneId, getCloneMaxMana(ownerId));
        if (mana < cloneDodgeManaCost)
            return false;

        cloneMana.put(cloneId, mana - cloneDodgeManaCost);
        cloneDodgeCooldownUntil.put(cloneId, now + cloneDodgeCooldownMs);

        Vector dir = clone.getLocation().getDirection().setY(0.0);
        if (dir.lengthSquared() < 0.0001) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();

        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        int roll = rng.nextInt(3);
        Vector dodge = (roll == 0) ? right : (roll == 1 ? right.multiply(-1) : dir.multiply(-1));
        clone.setVelocity(dodge.normalize().multiply(0.68).setY(0.14));

        Location fx = clone.getLocation().add(0, 1.0, 0);
        clone.getWorld().spawnParticle(Particle.CLOUD, fx, 10, 0.35, 0.25, 0.35, 0.01);
        clone.getWorld().spawnParticle(Particle.CRIT, fx, 7, 0.3, 0.25, 0.3, 0.02);
        return true;
    }

    private Location findSpawnNear(Location center) {
        World world = center.getWorld();
        if (world == null)
            return null;

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double radius = spawnRadiusMin + rng.nextDouble() * (spawnRadiusMax - spawnRadiusMin);

            int x = (int) Math.round(center.getX() + Math.cos(angle) * radius);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * radius);
            int y = world.getHighestBlockYAt(x, z) + 1;

            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (loc.getBlock().isLiquid())
                continue;

            return loc;
        }

        return null;
    }

    private Attribute getAttribute(String... keys) {
        for (String key : keys) {
            Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (attr != null)
                return attr;
        }
        return null;
    }

    private boolean isClone(Entity entity) {
        if (entity == null)
            return false;
        Byte mark = entity.getPersistentDataContainer().get(cloneKey, PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private UUID readOwnerId(Entity clone) {
        String raw = clone.getPersistentDataContainer().get(cloneOwnerKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank())
            return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Mob resolveCloneDamager(Entity damager) {
        if (damager instanceof Mob mob && isClone(mob)) {
            return mob;
        }

        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter
                && shooter instanceof Mob mob
                && isClone(shooter)) {
            return mob;
        }

        return null;
    }

    private UUID resolveCloneOwnerFromDeathCause(Zombie victim) {
        if (!(victim.getLastDamageCause() instanceof EntityDamageByEntityEvent hitEvent)) {
            return null;
        }

        Mob clone = resolveCloneDamager(hitEvent.getDamager());
        if (clone == null)
            return null;

        return readOwnerId(clone);
    }

    private UUID resolveCloneOwnerFromHitCache(UUID victimId) {
        long at = lastHitAtByZombie.getOrDefault(victimId, 0L);
        if (at <= 0)
            return null;

        long now = System.currentTimeMillis();
        if (now - at > cloneHitCreditMs)
            return null;

        return lastHitOwnerByZombie.get(victimId);
    }

    private void clearHitCache(UUID victimId) {
        lastHitAtByZombie.remove(victimId);
        lastHitOwnerByZombie.remove(victimId);
    }

    @EventHandler
    public void onOwnerQuit(PlayerQuitEvent e) {
        UUID ownerId = e.getPlayer().getUniqueId();
        removeOwnerClones(ownerId);
        ownerModes.remove(ownerId);
    }

    @EventHandler
    public void onOwnerDeath(PlayerDeathEvent e) {
        UUID ownerId = e.getEntity().getUniqueId();
        removeOwnerClones(ownerId);
        ownerModes.remove(ownerId);
    }

    @EventHandler
    public void onCloneTarget(EntityTargetEvent e) {
        if (!(e.getEntity() instanceof Mob clone) || !isClone(clone))
            return;

        if (!(e.getTarget() instanceof LivingEntity target)) {
            return;
        }

        UUID ownerId = readOwnerId(clone);
        if (ownerId == null || !isEnemy(target, ownerId)) {
            e.setCancelled(true);
            clone.setTarget(null);
        }
    }

    @EventHandler
    public void onCloneDamage(EntityDamageByEntityEvent e) {
        if (isClone(e.getEntity())) {
            Entity damaged = e.getEntity();
            UUID ownerId = readOwnerId(damaged);
            if (ownerId == null) {
                e.setCancelled(true);
                return;
            }

            Entity damager = e.getDamager();
            if (damager instanceof Player
                    || (damager instanceof Projectile proj && proj.getShooter() instanceof Player)) {
                e.setCancelled(true);
                return;
            }

            if (isClone(damager)
                    || (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter
                            && isClone(shooter))) {
                e.setCancelled(true);
                return;
            }

            if (damaged instanceof Mob cloneVictim && tryCloneDodge(cloneVictim, ownerId)) {
                e.setCancelled(true);
                return;
            }
        }

        Mob clone = resolveCloneDamager(e.getDamager());
        if (clone == null)
            return;

        UUID ownerId = readOwnerId(clone);
        if (ownerId == null) {
            e.setCancelled(true);
            return;
        }

        if (e.getEntity() instanceof Player) {
            e.setCancelled(true);
            return;
        }

        if (isClone(e.getEntity())) {
            e.setCancelled(true);
            return;
        }

        if (e.getEntity() instanceof Zombie targetZombie) {
            if (isClone(targetZombie)) {
                e.setCancelled(true);
                return;
            }
            lastHitOwnerByZombie.put(targetZombie.getUniqueId(), ownerId);
            lastHitAtByZombie.put(targetZombie.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        Entity dead = e.getEntity();

        if (isClone(dead)) {
            UUID ownerId = readOwnerId(dead);
            if (ownerId != null) {
                Set<UUID> clones = ownerToClones.get(ownerId);
                if (clones != null) {
                    clones.remove(dead.getUniqueId());
                    if (clones.isEmpty()) {
                        ownerToClones.remove(ownerId);
                    }
                }
            }
            clearCloneRuntime(dead.getUniqueId());
            return;
        }

        if (!(dead instanceof Zombie zombie))
            return;

        UUID victimId = zombie.getUniqueId();

        UUID ownerId = resolveCloneOwnerFromDeathCause(zombie);

        if (ownerId == null && zombie.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntityEvent) {
            Entity damager = byEntityEvent.getDamager();
            if (damager instanceof Player
                    || (damager instanceof Projectile p && p.getShooter() instanceof Player)) {
                clearHitCache(victimId);
                return;
            }
        }

        if (ownerId == null) {
            ownerId = resolveCloneOwnerFromHitCache(victimId);
        }

        clearHitCache(victimId);

        if (ownerId == null)
            return;

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline())
            return;
        if (owner.getGameMode() == GameMode.SPECTATOR)
            return;
        if (plugin.kit().getKit(ownerId) != KitManager.Kit.CLONER)
            return;

        int exp = plugin.getConfig().getInt("kit_xp.cloner.exp_per_clone_kill_zombie", 2);
        if (exp > 0) {
            plugin.progress().addClonerExp(owner, exp);
        }
    }
}
