package me.korgan.deadcycle.boss;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class MiniBossManager implements Listener {

    private final DeadCyclePlugin plugin;
    private final Random rng = new Random();

    private final NamespacedKey miniBossKey;
    private final NamespacedKey miniBossOwnerKey;

    private final Map<UUID, UUID> ownerToMiniBoss = new HashMap<>();
    private final Map<UUID, Long> ownerCopyCooldown = new HashMap<>();
    private final Set<UUID> defeatedOwners = new HashSet<>();

    private BukkitTask ensureTask;
    private boolean active = false;
    private boolean miniBossSpawning = false;

    private int spawnDay;
    private double hp;
    private double damage;
    private double speed;
    private double followRange;
    private int copyCooldownMs;
    private int spawnRadiusMin;
    private int spawnRadiusMax;

    public MiniBossManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.miniBossKey = new NamespacedKey(plugin, "mini_boss");
        this.miniBossOwnerKey = new NamespacedKey(plugin, "mini_boss_owner");
        reload();
    }

    public void reload() {
        this.spawnDay = Math.max(1, plugin.getConfig().getInt("mini_boss.spawn_day", 5));
        this.hp = Math.max(20.0, plugin.getConfig().getDouble("mini_boss.hp", 72.0));
        this.damage = Math.max(1.0, plugin.getConfig().getDouble("mini_boss.damage", 3.8));
        this.speed = Math.max(0.18, plugin.getConfig().getDouble("mini_boss.speed", 0.26));
        this.followRange = Math.max(16.0, plugin.getConfig().getDouble("mini_boss.follow_range", 38.0));
        this.copyCooldownMs = Math.max(500, plugin.getConfig().getInt("mini_boss.copy_cooldown_ms", 2200));
        this.spawnRadiusMin = Math.max(2, plugin.getConfig().getInt("mini_boss.spawn_radius_min", 5));
        this.spawnRadiusMax = Math.max(spawnRadiusMin + 1,
                plugin.getConfig().getInt("mini_boss.spawn_radius_max", 10));
    }

    public NamespacedKey miniBossMarkKey() {
        return miniBossKey;
    }

    public boolean isMiniBossSpawning() {
        return miniBossSpawning;
    }

    public void startNight(int dayCount) {
        stopNight();

        if (!plugin.getConfig().getBoolean("mini_boss.enabled", true))
            return;
        if (dayCount < spawnDay)
            return;

        active = true;
        spawnForAllEligiblePlayers();

        ensureTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active)
                return;
            cleanupInvalidMiniBosses();
            spawnForAllEligiblePlayers();
        }, 60L, 20L * 10L);
    }

    public void stopNight() {
        active = false;

        if (ensureTask != null) {
            ensureTask.cancel();
            ensureTask = null;
        }

        removeAllMiniBosses();
        ownerToMiniBoss.clear();
        ownerCopyCooldown.clear();
    }

    public void resetProgress() {
        stopNight();
        defeatedOwners.clear();
    }

    private void removeAllMiniBosses() {
        for (UUID miniUuid : new ArrayList<>(ownerToMiniBoss.values())) {
            Entity e = Bukkit.getEntity(miniUuid);
            if (e != null && e.isValid() && !e.isDead()) {
                e.remove();
            }
        }
    }

    private void spawnForAllEligiblePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isEligiblePlayer(p))
                continue;
            ensureMiniBossForPlayer(p);
        }
    }

    private boolean isEligiblePlayer(Player p) {
        return p != null
                && p.isOnline()
                && !p.isDead()
                && p.getGameMode() != GameMode.SPECTATOR;
    }

    private void ensureMiniBossForPlayer(Player owner) {
        UUID ownerId = owner.getUniqueId();
        if (defeatedOwners.contains(ownerId))
            return;

        Zombie current = getMiniBoss(ownerId);
        if (current != null) {
            if (current.getTarget() == null || !(current.getTarget() instanceof Player p)
                    || !p.getUniqueId().equals(ownerId)) {
                current.setTarget(owner);
            }
            return;
        }

        Location spawnLoc = findSpawnNear(owner.getLocation());
        if (spawnLoc == null)
            return;

        Zombie spawned = spawnMiniBoss(owner, spawnLoc);
        if (spawned != null) {
            ownerToMiniBoss.put(ownerId, spawned.getUniqueId());
            ownerCopyCooldown.put(ownerId, 0L);
        }
    }

    private Zombie spawnMiniBoss(Player owner, Location loc) {
        World world = loc.getWorld();
        if (world == null)
            return null;

        try {
            miniBossSpawning = true;
            return world.spawn(loc, Zombie.class, z -> {
                z.getPersistentDataContainer().set(miniBossKey, PersistentDataType.BYTE, (byte) 1);
                z.getPersistentDataContainer().set(miniBossOwnerKey, PersistentDataType.STRING,
                        owner.getUniqueId().toString());

                z.setCustomName("§8§l[МИНИ] §7Копия " + owner.getName());
                z.setCustomNameVisible(true);
                z.setCanPickupItems(false);
                z.setRemoveWhenFarAway(false);
                z.setPersistent(true);
                z.setBaby(false);

                EntityEquipment eq = z.getEquipment();
                if (eq != null) {
                    eq.setItemInMainHand(null);
                    eq.setHelmet(null);
                    eq.setChestplate(null);
                    eq.setLeggings(null);
                    eq.setBoots(null);

                    eq.setItemInMainHandDropChance(0f);
                    eq.setHelmetDropChance(0f);
                    eq.setChestplateDropChance(0f);
                    eq.setLeggingsDropChance(0f);
                    eq.setBootsDropChance(0f);
                }

                Attribute maxHealthAttr = getAttribute("generic.max_health", "max_health");
                if (maxHealthAttr != null) {
                    AttributeInstance maxHealth = z.getAttribute(maxHealthAttr);
                    if (maxHealth != null) {
                        maxHealth.setBaseValue(hp);
                        z.setHealth(Math.min(hp, maxHealth.getValue()));
                    }
                }

                Attribute attackAttr = getAttribute("generic.attack_damage", "attack_damage");
                if (attackAttr != null) {
                    AttributeInstance attack = z.getAttribute(attackAttr);
                    if (attack != null)
                        attack.setBaseValue(damage);
                }

                Attribute speedAttr = getAttribute("generic.movement_speed", "movement_speed");
                if (speedAttr != null) {
                    AttributeInstance movement = z.getAttribute(speedAttr);
                    if (movement != null)
                        movement.setBaseValue(speed);
                }

                Attribute followAttr = getAttribute("generic.follow_range", "follow_range");
                if (followAttr != null) {
                    AttributeInstance follow = z.getAttribute(followAttr);
                    if (follow != null)
                        follow.setBaseValue(followRange);
                }

                z.setTarget(owner);
            });
        } finally {
            miniBossSpawning = false;
        }
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

    private Zombie getMiniBoss(UUID ownerId) {
        UUID miniId = ownerToMiniBoss.get(ownerId);
        if (miniId == null)
            return null;

        Entity e = Bukkit.getEntity(miniId);
        if (!(e instanceof Zombie z) || !z.isValid() || z.isDead()) {
            ownerToMiniBoss.remove(ownerId);
            return null;
        }

        Byte mark = z.getPersistentDataContainer().get(miniBossKey, PersistentDataType.BYTE);
        if (mark == null || mark != (byte) 1) {
            ownerToMiniBoss.remove(ownerId);
            return null;
        }

        return z;
    }

    private void cleanupInvalidMiniBosses() {
        for (UUID ownerId : new ArrayList<>(ownerToMiniBoss.keySet())) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (!isEligiblePlayer(owner)) {
                removeMiniBossForOwner(ownerId);
                continue;
            }

            Zombie mini = getMiniBoss(ownerId);
            if (mini == null) {
                removeMiniBossForOwner(ownerId);
                continue;
            }

            if (!mini.getWorld().getUID().equals(owner.getWorld().getUID())) {
                Location syncLoc = findSpawnNear(owner.getLocation());
                mini.teleport(syncLoc != null ? syncLoc : owner.getLocation());
            }

            if (mini.getTarget() == null || !(mini.getTarget() instanceof Player p)
                    || !p.getUniqueId().equals(ownerId)) {
                mini.setTarget(owner);
            }
        }
    }

    private void removeMiniBossForOwner(UUID ownerId) {
        UUID miniId = ownerToMiniBoss.remove(ownerId);
        ownerCopyCooldown.remove(ownerId);
        if (miniId == null)
            return;

        Entity e = Bukkit.getEntity(miniId);
        if (e != null && e.isValid() && !e.isDead()) {
            e.remove();
        }
    }

    public void onPlayerSkillUsed(Player player, String skillId) {
        if (!active)
            return;
        if (player == null || skillId == null || skillId.isBlank())
            return;
        if (!isEligiblePlayer(player))
            return;

        UUID ownerId = player.getUniqueId();
        Zombie mini = getMiniBoss(ownerId);
        if (mini == null)
            return;

        long now = System.currentTimeMillis();
        long last = ownerCopyCooldown.getOrDefault(ownerId, 0L);
        if (now - last < copyCooldownMs)
            return;

        ownerCopyCooldown.put(ownerId, now);
        String normalized = skillId.toLowerCase(Locale.ROOT);

        Bukkit.getScheduler().runTaskLater(plugin, () -> mimicSkill(ownerId, normalized), 8L);
    }

    private void mimicSkill(UUID ownerId, String skillId) {
        if (!active)
            return;

        Player owner = Bukkit.getPlayer(ownerId);
        Zombie mini = getMiniBoss(ownerId);
        if (!isEligiblePlayer(owner) || mini == null)
            return;

        if (!mini.getWorld().getUID().equals(owner.getWorld().getUID())) {
            mini.teleport(owner.getLocation());
        }

        if (mini.getLocation().distanceSquared(owner.getLocation()) > 24 * 24) {
            Location newLoc = findSpawnNear(owner.getLocation());
            mini.teleport(newLoc != null ? newLoc : owner.getLocation());
        }

        switch (skillId) {
            case "gravity_crush" -> mimicGravityCrush(mini, owner);
            case "levitation_strike" -> mimicLevitation(mini, owner);
            case "archer_rain" -> mimicArcherRain(mini, owner);
            case "ritual_cut" -> mimicRitualCut(mini, owner);
            case "circle_trance" -> mimicCircleTrance(mini, owner);
            case "berserk" -> mimicBerserk(mini, owner);
            case "fighter_combo" -> mimicFighterCombo(mini, owner);
            default -> mimicGenericPressure(mini, owner);
        }
    }

    private void mimicGravityCrush(Zombie mini, Player owner) {
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 1, true, false, true));
        owner.setVelocity(owner.getVelocity().setY(-0.75));
        owner.damage(1.5, mini);
        owner.getWorld().spawnParticle(Particle.SMOKE, owner.getLocation().add(0, 1, 0), 14, 0.35, 0.3, 0.35, 0.01);
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 0.6f);
        mini.setTarget(owner);
    }

    private void mimicLevitation(Zombie mini, Player owner) {
        owner.setVelocity(owner.getVelocity().setY(1.0));
        owner.damage(2.0, mini);
        owner.getWorld().spawnParticle(Particle.END_ROD, owner.getLocation().add(0, 1, 0), 12, 0.25, 0.4, 0.25, 0.01);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_SHULKER_SHOOT, 0.9f, 1.2f);
        mini.setTarget(owner);
    }

    private void mimicArcherRain(Zombie mini, Player owner) {
        Location center = owner.getLocation();
        World world = owner.getWorld();

        for (int i = 0; i < 4; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = 1.5 + rng.nextDouble() * 2.2;
            Location from = center.clone().add(Math.cos(angle) * dist, 9.0 + rng.nextDouble() * 2.0,
                    Math.sin(angle) * dist);
            Arrow arrow = world.spawnArrow(from, new Vector(0, -1, 0), 2.2f, 0.8f);
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            arrow.setShooter(mini);
        }

        world.playSound(center, Sound.ENTITY_ARROW_SHOOT, 0.8f, 0.9f);
        mini.setTarget(owner);
    }

    private void mimicRitualCut(Zombie mini, Player owner) {
        teleportBehind(owner, mini, 1.7);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, true, false, true));
        mini.attack(owner);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.75f);
    }

    private void mimicCircleTrance(Zombie mini, Player owner) {
        mini.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 90, 0, true, false, true));
        mini.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 90, 0, true, false, true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 70, 0, true, false, true));
        owner.getWorld().spawnParticle(Particle.WITCH, mini.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.01);
        mini.setTarget(owner);
    }

    private void mimicBerserk(Zombie mini, Player owner) {
        mini.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 0, true, false, true));
        mini.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 1, true, false, true));
        mini.setTarget(owner);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!active)
                return;
            Zombie current = getMiniBoss(owner.getUniqueId());
            if (current == null || !owner.isOnline() || owner.isDead())
                return;
            current.attack(owner);
        }, 4L);
    }

    private void mimicFighterCombo(Zombie mini, Player owner) {
        teleportBehind(owner, mini, 1.9);
        mini.attack(owner);

        Vector away = owner.getLocation().toVector().subtract(mini.getLocation().toVector()).setY(0.0);
        if (away.lengthSquared() < 0.001) {
            away = owner.getLocation().getDirection().setY(0.0);
        }
        if (away.lengthSquared() < 0.001) {
            away = new Vector(0, 0, 1);
        }
        owner.setVelocity(away.normalize().multiply(0.75).setY(0.2));
    }

    private void mimicGenericPressure(Zombie mini, Player owner) {
        if (mini.getLocation().distanceSquared(owner.getLocation()) > 9.0) {
            teleportBehind(owner, mini, 2.2);
        }
        mini.setTarget(owner);
        mini.attack(owner);
    }

    private void teleportBehind(Player owner, Zombie mini, double dist) {
        Vector behind = owner.getLocation().getDirection().normalize().multiply(-dist);
        Location loc = owner.getLocation().clone().add(behind);
        loc.setY(owner.getLocation().getY());
        mini.teleport(loc);
    }

    @EventHandler
    public void onOwnerQuit(PlayerQuitEvent e) {
        removeMiniBossForOwner(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onOwnerDeath(PlayerDeathEvent e) {
        removeMiniBossForOwner(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onMiniBossDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Zombie z))
            return;

        Byte mark = z.getPersistentDataContainer().get(miniBossKey, PersistentDataType.BYTE);
        if (mark == null || mark != (byte) 1)
            return;

        UUID ownerId = readOwnerId(z);
        if (ownerId != null) {
            ownerToMiniBoss.remove(ownerId);
            ownerCopyCooldown.remove(ownerId);
            defeatedOwners.add(ownerId);

            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null && owner.isOnline()) {
                owner.sendMessage("§8[МИНИ-БОСС] §cТвой клон повержен. В следующие ночи он больше не появится.");
            }
        }
    }

    @EventHandler
    public void onMiniBossTarget(EntityTargetEvent e) {
        if (!(e.getEntity() instanceof Zombie z))
            return;

        Byte mark = z.getPersistentDataContainer().get(miniBossKey, PersistentDataType.BYTE);
        if (mark == null || mark != (byte) 1)
            return;

        UUID ownerId = readOwnerId(z);
        if (ownerId == null) {
            e.setCancelled(true);
            z.setTarget(null);
            return;
        }

        Player owner = Bukkit.getPlayer(ownerId);
        if (!isEligiblePlayer(owner)) {
            removeMiniBossForOwner(ownerId);
            e.setCancelled(true);
            return;
        }

        if (!(e.getTarget() instanceof Player target) || !target.getUniqueId().equals(ownerId)) {
            e.setCancelled(true);
            z.setTarget(owner);
        }
    }

    @EventHandler
    public void onMiniBossDamageOtherPlayers(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Zombie z))
            return;

        Byte mark = z.getPersistentDataContainer().get(miniBossKey, PersistentDataType.BYTE);
        if (mark == null || mark != (byte) 1)
            return;

        if (!(e.getEntity() instanceof Player victim))
            return;

        UUID ownerId = readOwnerId(z);
        if (ownerId == null || !victim.getUniqueId().equals(ownerId)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFighterComboProxy(EntityDamageByEntityEvent e) {
        if (!active)
            return;
        if (!(e.getDamager() instanceof Player player))
            return;
        if (!(e.getEntity() instanceof Zombie))
            return;
        if (!isEligiblePlayer(player))
            return;
        if (plugin.kit().getKit(player.getUniqueId()) != KitManager.Kit.FIGHTER)
            return;

        onPlayerSkillUsed(player, "fighter_combo");
    }

    private UUID readOwnerId(Zombie z) {
        String raw = z.getPersistentDataContainer().get(miniBossOwnerKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank())
            return null;

        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
