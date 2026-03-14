package me.korgan.deadcycle.kit.summoner;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

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

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

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
    private final Map<UUID, Double> ownerUpkeepAccumulator = new HashMap<>();
    private final Map<UUID, Long> ownerUpkeepWarnUntil = new HashMap<>();
    private final Map<UUID, UUID> ownerForcedTarget = new HashMap<>();
    private final Map<UUID, Long> ownerForcedTargetUntil = new HashMap<>();
    private final Map<UUID, Long> ownerSacrificeDamageBonusUntil = new HashMap<>();
    private final Map<UUID, Double> ownerSacrificeDamageBonusMultiplier = new HashMap<>();
    private final Map<UUID, Long> summonNextRetargetAt = new HashMap<>();
    private final Map<UUID, Location> summonLastKnownLocation = new HashMap<>();
    private final Map<UUID, Long> summonLastProgressAt = new HashMap<>();

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

    private boolean upkeepEnabled;
    private double upkeepManaPerExtraSummonPerSecond;
    private int upkeepFreeSummons;
    private long upkeepWarnCooldownMs;

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
    private long aiRetargetIntervalMs;
    private long aiStuckTimeoutMs;
    private double aiStuckMinDistanceToOwner;
    private double aiTargetDropDistance;

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

        this.upkeepEnabled = plugin.getConfig().getBoolean("summoner.upkeep.enabled", false);
        this.upkeepManaPerExtraSummonPerSecond = Math.max(0.0,
                plugin.getConfig().getDouble("summoner.upkeep.mana_per_extra_summon_per_second", 0.0));
        int configuredFreeSummons = plugin.getConfig().getInt("summoner.upkeep.free_summons", -1);
        this.upkeepFreeSummons = (configuredFreeSummons < 0) ? Integer.MAX_VALUE : Math.max(0, configuredFreeSummons);
        this.upkeepWarnCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("summoner.upkeep.warn_cooldown_ms", 5000L));

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

        this.aiRetargetIntervalMs = Math.max(250L,
                plugin.getConfig().getLong("summoner.ai.retarget_interval_ms", 900L));
        this.aiStuckTimeoutMs = Math.max(1000L,
                plugin.getConfig().getLong("summoner.ai.stuck_timeout_ms", 2500L));
        this.aiStuckMinDistanceToOwner = Math.max(2.0,
                plugin.getConfig().getDouble("summoner.ai.stuck_min_distance_to_owner", 6.0));
        this.aiTargetDropDistance = Math.max(8.0,
                plugin.getConfig().getDouble("summoner.ai.target_drop_distance", 30.0));

        if (!enabled) {
            removeAllSummons();
            ownerForcedTarget.clear();
            ownerForcedTargetUntil.clear();
            ownerSacrificeDamageBonusUntil.clear();
            ownerSacrificeDamageBonusMultiplier.clear();
            summonNextRetargetAt.clear();
            summonLastKnownLocation.clear();
            summonLastProgressAt.clear();
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
        ownerUpkeepAccumulator.clear();
        ownerUpkeepWarnUntil.clear();
        ownerForcedTarget.clear();
        ownerForcedTargetUntil.clear();
        ownerSacrificeDamageBonusUntil.clear();
        ownerSacrificeDamageBonusMultiplier.clear();
        summonNextRetargetAt.clear();
        summonLastKnownLocation.clear();
        summonLastProgressAt.clear();
    }

    public void resetAll() {
        removeAllSummons();
        ownerToSummons.clear();
        summonExpireAt.clear();
        lastHitOwnerByZombie.clear();
        lastHitAtByZombie.clear();
        ownerUpkeepAccumulator.clear();
        ownerUpkeepWarnUntil.clear();
        ownerForcedTarget.clear();
        ownerForcedTargetUntil.clear();
        ownerSacrificeDamageBonusUntil.clear();
        ownerSacrificeDamageBonusMultiplier.clear();
        summonNextRetargetAt.clear();
        summonLastKnownLocation.clear();
        summonLastProgressAt.clear();
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

        ensureSkillItem(owner, SummonType.WOLF.skillId(), plugin.kit().createSummonerWolvesItem(),
                plugin.kit().isSkillUnlockedForLevel(KitManager.Kit.SUMMONER, SummonType.WOLF.skillId(), level));
        ensureSkillItem(owner, SummonType.PHANTOM.skillId(), plugin.kit().createSummonerPhantomItem(),
                plugin.kit().isSkillUnlockedForLevel(KitManager.Kit.SUMMONER, SummonType.PHANTOM.skillId(), level));
        ensureSkillItem(owner, SummonType.GOLEM.skillId(), plugin.kit().createSummonerGolemItem(),
                plugin.kit().isSkillUnlockedForLevel(KitManager.Kit.SUMMONER, SummonType.GOLEM.skillId(), level));
        ensureSkillItem(owner, SummonType.VEX.skillId(), plugin.kit().createSummonerVexItem(),
                plugin.kit().isSkillUnlockedForLevel(KitManager.Kit.SUMMONER, SummonType.VEX.skillId(), level));
        ensureSkillItem(owner, "summoner_focus", plugin.kit().createSummonerFocusItem(),
                plugin.kit().isSkillUnlockedForLevel(KitManager.Kit.SUMMONER, "summoner_focus", level));
        ensureSkillItem(owner, "summoner_regroup", plugin.kit().createSummonerRegroupItem(),
                plugin.kit().isSkillUnlockedForLevel(KitManager.Kit.SUMMONER, "summoner_regroup", level));
        ensureSkillItem(owner, "summoner_sacrifice", plugin.kit().createSummonerSacrificeItem(),
                plugin.kit().isSkillUnlockedForLevel(KitManager.Kit.SUMMONER, "summoner_sacrifice", level));
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
        removeSkillItem(owner, "summoner_focus");
        removeSkillItem(owner, "summoner_regroup");
        removeSkillItem(owner, "summoner_sacrifice");
    }

    public String getFocusError(Player owner, double range) {
        if (!enabled)
            return "§cКит Призыватель временно отключён.";
        if (owner == null || !owner.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.SUMMONER)
            return "§cЭтот навык доступен только киту Призыватель.";

        if (getActiveSummons(owner.getUniqueId()).isEmpty())
            return "§cУ тебя нет активных призывов для команды фокуса.";

        LivingEntity target = findFocusTarget(owner, range);
        if (target == null)
            return "§cНет подходящей цели перед тобой.";

        return null;
    }

    public boolean focusTarget(Player owner, double range, int focusSeconds) {
        String error = getFocusError(owner, range);
        if (error != null) {
            if (owner != null)
                owner.sendMessage(error);
            return false;
        }

        if (owner == null)
            return false;

        UUID ownerId = owner.getUniqueId();
        LivingEntity target = findFocusTarget(owner, range);
        if (target == null)
            return false;

        List<Mob> summons = getActiveSummons(ownerId);
        if (summons.isEmpty()) {
            owner.sendMessage("§cУ тебя нет активных призывов для команды фокуса.");
            return false;
        }

        int commanded = 0;
        for (Mob summon : summons) {
            if (summon == null || summon.isDead() || !summon.isValid())
                continue;
            summon.setTarget(target);
            commanded++;
        }

        if (commanded <= 0)
            return false;

        int safeSeconds = Math.max(1, focusSeconds);
        ownerForcedTarget.put(ownerId, target.getUniqueId());
        ownerForcedTargetUntil.put(ownerId, System.currentTimeMillis() + safeSeconds * 1000L);

        Location center = owner.getLocation().clone().add(0, 1.0, 0);
        owner.getWorld().spawnParticle(Particle.ENCHANT, center, 24, 0.35, 0.45, 0.35, 0.08);
        owner.getWorld().spawnParticle(Particle.END_ROD, center, 14, 0.22, 0.30, 0.22, 0.02);
        owner.getWorld().playSound(center, Sound.ENTITY_EVOKER_CAST_SPELL, 0.65f, 1.35f);
        owner.sendActionBar(net.kyori.adventure.text.Component.text("Фокус: " + commanded + " призывов"));
        return true;
    }

    public String getRegroupError(Player owner) {
        if (!enabled)
            return "§cКит Призыватель временно отключён.";
        if (owner == null || !owner.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.SUMMONER)
            return "§cЭтот навык доступен только киту Призыватель.";

        if (getActiveSummons(owner.getUniqueId()).isEmpty())
            return "§cУ тебя нет активных призывов для перегруппировки.";

        return null;
    }

    public boolean regroupSummons(Player owner, int shieldSeconds, int shieldAmplifier) {
        String error = getRegroupError(owner);
        if (error != null) {
            if (owner != null)
                owner.sendMessage(error);
            return false;
        }

        if (owner == null)
            return false;

        UUID ownerId = owner.getUniqueId();
        List<Mob> summons = getActiveSummons(ownerId);
        if (summons.isEmpty())
            return false;

        World world = owner.getWorld();
        if (world == null)
            return false;

        Location center = owner.getLocation().clone();
        int durationTicks = Math.max(20, shieldSeconds * 20);
        int amplifier = Math.max(0, shieldAmplifier);

        int moved = 0;
        int affected = 0;
        int total = Math.max(1, summons.size());

        for (int i = 0; i < summons.size(); i++) {
            Mob summon = summons.get(i);
            if (summon == null || summon.isDead() || !summon.isValid())
                continue;

            double angle = (Math.PI * 2.0 * i) / total;
            Location regroupPoint = center.clone().add(Math.cos(angle) * 1.8, 0.15, Math.sin(angle) * 1.8);

            if (!summon.getWorld().getUID().equals(world.getUID())
                    || summon.getLocation().distanceSquared(center) > (16.0 * 16.0)) {
                summon.teleport(regroupPoint);
                moved++;
            }

            summon.setTarget(null);
            summon.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, durationTicks, amplifier,
                    true, false, true));

            Location at = summon.getLocation().clone().add(0, 0.9, 0);
            world.spawnParticle(Particle.ENCHANT, at, 12, 0.22, 0.28, 0.22, 0.06);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 5, 0.14, 0.22, 0.14, 0.01);
            affected++;
        }

        if (affected <= 0)
            return false;

        Location pulse = center.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.END_ROD, pulse, 20, 0.35, 0.35, 0.35, 0.02);
        world.spawnParticle(Particle.ENCHANT, pulse, 26, 0.45, 0.50, 0.45, 0.08);
        world.playSound(pulse, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.85f, 1.25f);
        world.playSound(pulse, Sound.BLOCK_BEACON_ACTIVATE, 0.65f, 1.6f);

        owner.sendActionBar(net.kyori.adventure.text.Component.text(
                "Перегруппировка: " + affected + " призывов" + (moved > 0 ? " (подтянуто " + moved + ")" : "")));
        return true;
    }

    public String getSacrificeError(Player owner) {
        if (!enabled)
            return "§cКит Призыватель временно отключён.";
        if (owner == null || !owner.isOnline())
            return "§cИгрок не в сети.";
        if (plugin.kit().getKit(owner.getUniqueId()) != KitManager.Kit.SUMMONER)
            return "§cЭтот навык доступен только киту Призыватель.";

        List<Mob> summons = getActiveSummons(owner.getUniqueId());
        if (summons.size() < 2)
            return "§cНужно минимум 2 активных призыва для жертвенного импульса.";

        return null;
    }

    public boolean sacrificialImpulse(Player owner,
            int speedSeconds,
            int speedAmplifier,
            double damageBonusMultiplier,
            int damageBonusSeconds) {
        String error = getSacrificeError(owner);
        if (error != null) {
            if (owner != null)
                owner.sendMessage(error);
            return false;
        }

        if (owner == null)
            return false;

        UUID ownerId = owner.getUniqueId();
        List<Mob> summons = getActiveSummons(ownerId);
        if (summons.size() < 2)
            return false;

        Mob victim = pickSacrificeVictim(summons, owner.getLocation());
        if (victim == null)
            return false;

        World world = owner.getWorld();
        if (world == null)
            return false;

        Location burst = victim.getLocation().clone().add(0, 0.8, 0);
        UUID victimId = victim.getUniqueId();
        victim.remove();
        cleanupSummonRuntime(victimId, ownerId);

        List<Mob> buffed = getActiveSummons(ownerId);
        if (buffed.isEmpty())
            return false;

        int speedTicks = Math.max(20, speedSeconds * 20);
        int speedAmp = Math.max(0, speedAmplifier);
        int boosted = 0;

        for (Mob summon : buffed) {
            if (summon == null || summon.isDead() || !summon.isValid())
                continue;

            summon.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speedTicks, speedAmp,
                    true, false, true));

            Location at = summon.getLocation().clone().add(0, 0.9, 0);
            world.spawnParticle(Particle.CRIT, at, 10, 0.20, 0.22, 0.20, 0.12);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 6, 0.16, 0.24, 0.16, 0.01);
            boosted++;
        }

        if (boosted <= 0)
            return false;

        double damageBonus = Math.max(0.0, damageBonusMultiplier);
        int safeDamageBonusSeconds = Math.max(0, damageBonusSeconds);
        if (damageBonus > 0.0 && safeDamageBonusSeconds > 0) {
            ownerSacrificeDamageBonusMultiplier.put(ownerId, damageBonus);
            ownerSacrificeDamageBonusUntil.put(ownerId,
                    System.currentTimeMillis() + (safeDamageBonusSeconds * 1000L));
        } else {
            ownerSacrificeDamageBonusMultiplier.remove(ownerId);
            ownerSacrificeDamageBonusUntil.remove(ownerId);
        }

        world.spawnParticle(Particle.EXPLOSION, burst, 1, 0.0, 0.0, 0.0, 0.0);
        world.spawnParticle(Particle.CRIT, burst, 26, 0.40, 0.35, 0.40, 0.28);
        world.spawnParticle(Particle.SOUL, burst, 18, 0.32, 0.28, 0.32, 0.02);
        world.playSound(burst, Sound.ENTITY_WITHER_HURT, 0.95f, 1.35f);
        world.playSound(owner.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.7f, 0.8f);

        owner.sendActionBar(net.kyori.adventure.text.Component.text(
                "Жертвенный импульс: усилено " + boosted + " призывов"));
        return true;
    }

    private Mob pickSacrificeVictim(List<Mob> summons, Location ownerLoc) {
        Mob victim = null;
        double bestHealth = Double.MAX_VALUE;
        double bestDistance = -1.0;

        for (Mob summon : summons) {
            if (summon == null || summon.isDead() || !summon.isValid())
                continue;

            double hp = Math.max(0.0, summon.getHealth());
            double dist = ownerLoc == null ? 0.0 : summon.getLocation().distanceSquared(ownerLoc);

            if (hp < bestHealth || (Math.abs(hp - bestHealth) < 0.001 && dist > bestDistance)) {
                bestHealth = hp;
                bestDistance = dist;
                victim = summon;
            }
        }

        return victim;
    }

    private double getActiveSacrificeDamageBonus(UUID ownerId) {
        long until = ownerSacrificeDamageBonusUntil.getOrDefault(ownerId, 0L);
        if (until <= 0L)
            return 0.0;

        if (System.currentTimeMillis() >= until) {
            ownerSacrificeDamageBonusUntil.remove(ownerId);
            ownerSacrificeDamageBonusMultiplier.remove(ownerId);
            return 0.0;
        }

        return Math.max(0.0, ownerSacrificeDamageBonusMultiplier.getOrDefault(ownerId, 0.0));
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
                ownerForcedTarget.remove(ownerId);
                ownerForcedTargetUntil.remove(ownerId);
                ownerSacrificeDamageBonusUntil.remove(ownerId);
                ownerSacrificeDamageBonusMultiplier.remove(ownerId);
                continue;
            }

            LivingEntity forcedTarget = resolveForcedTarget(ownerId, now);

            tickOwnerUpkeep(owner, ownerId, summons);
            if (summons.isEmpty()) {
                ownerToSummons.remove(ownerId);
                ownerForcedTarget.remove(ownerId);
                ownerForcedTargetUntil.remove(ownerId);
                ownerSacrificeDamageBonusUntil.remove(ownerId);
                ownerSacrificeDamageBonusMultiplier.remove(ownerId);
                continue;
            }

            for (Mob summon : summons) {
                UUID summonId = summon.getUniqueId();
                long expireAt = summonExpireAt.getOrDefault(summon.getUniqueId(), 0L);
                if (expireAt > 0L && now >= expireAt) {
                    summon.remove();
                    cleanupSummonRuntime(summon.getUniqueId(), ownerId);
                    continue;
                }

                if (isSummonStuck(summon, owner, now)) {
                    unstickSummon(summon, owner);
                    summon.setTarget(null);
                    summonNextRetargetAt.put(summonId, now + 250L);
                    continue;
                }

                if (forcedTarget != null) {
                    if (summon.getTarget() == null
                            || !summon.getTarget().getUniqueId().equals(forcedTarget.getUniqueId())) {
                        summon.setTarget(forcedTarget);
                    }
                    summonNextRetargetAt.put(summonId, now + aiRetargetIntervalMs);
                    continue;
                }

                LivingEntity currentTarget = summon.getTarget();
                if (shouldDropCurrentTarget(summon, currentTarget, ownerId)) {
                    summon.setTarget(null);
                    currentTarget = null;
                }

                if (currentTarget == null) {
                    LivingEntity enemy = findPriorityEnemy(summon, owner, ownerId);
                    if (enemy != null) {
                        summon.setTarget(enemy);
                        summonNextRetargetAt.put(summonId, now + aiRetargetIntervalMs);
                    } else {
                        followOwnerWhenIdle(summon, owner);
                    }
                    continue;
                }

                long nextRetargetAt = summonNextRetargetAt.getOrDefault(summonId, 0L);
                if (now < nextRetargetAt)
                    continue;

                LivingEntity betterEnemy = findPriorityEnemy(summon, owner, ownerId);
                if (betterEnemy != null && !betterEnemy.getUniqueId().equals(currentTarget.getUniqueId())) {
                    double currentDist = safeDistanceSquared(summon.getLocation(), currentTarget.getLocation(),
                            Double.MAX_VALUE);
                    double betterDist = safeDistanceSquared(summon.getLocation(), betterEnemy.getLocation(),
                            Double.MAX_VALUE);
                    if (betterDist + 6.0 < currentDist) {
                        summon.setTarget(betterEnemy);
                    }
                }
                summonNextRetargetAt.put(summonId, now + aiRetargetIntervalMs);
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
        ownerForcedTarget.remove(ownerId);
        ownerForcedTargetUntil.remove(ownerId);
        ownerSacrificeDamageBonusUntil.remove(ownerId);
        ownerSacrificeDamageBonusMultiplier.remove(ownerId);
        if (ids == null) {
            ownerUpkeepAccumulator.remove(ownerId);
            ownerUpkeepWarnUntil.remove(ownerId);
            return;
        }

        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid() && !entity.isDead()) {
                entity.remove();
            }
            cleanupSummonRuntime(id, ownerId);
        }

        ownerUpkeepAccumulator.remove(ownerId);
        ownerUpkeepWarnUntil.remove(ownerId);
    }

    private void cleanupSummonRuntime(UUID entityId, UUID ownerId) {
        summonExpireAt.remove(entityId);
        clearSummonAiRuntime(entityId);

        Set<UUID> ownerSet = ownerToSummons.get(ownerId);
        if (ownerSet != null) {
            ownerSet.remove(entityId);
            if (ownerSet.isEmpty()) {
                ownerToSummons.remove(ownerId);
                ownerForcedTarget.remove(ownerId);
                ownerForcedTargetUntil.remove(ownerId);
                ownerUpkeepAccumulator.remove(ownerId);
                ownerUpkeepWarnUntil.remove(ownerId);
                ownerSacrificeDamageBonusUntil.remove(ownerId);
                ownerSacrificeDamageBonusMultiplier.remove(ownerId);
            }
        }
    }

    private void clearSummonAiRuntime(UUID summonId) {
        summonNextRetargetAt.remove(summonId);
        summonLastKnownLocation.remove(summonId);
        summonLastProgressAt.remove(summonId);
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
                clearSummonAiRuntime(id);
                continue;
            }

            UUID owner = readOwnerId(entity);
            if (owner == null || !owner.equals(ownerId)) {
                ids.remove(id);
                summonExpireAt.remove(id);
                clearSummonAiRuntime(id);
                continue;
            }

            result.add(summon);
        }

        if (ids.isEmpty()) {
            ownerToSummons.remove(ownerId);
        }

        return result;
    }

    private void tickOwnerUpkeep(Player owner, UUID ownerId, List<Mob> summons) {
        if (!upkeepEnabled || upkeepManaPerExtraSummonPerSecond <= 0.0)
            return;
        if (plugin.mana() == null)
            return;

        int extra = Math.max(0, summons.size() - upkeepFreeSummons);
        if (extra <= 0) {
            ownerUpkeepAccumulator.put(ownerId, 0.0);
            return;
        }

        double acc = ownerUpkeepAccumulator.getOrDefault(ownerId, 0.0);
        acc += upkeepManaPerExtraSummonPerSecond * extra * 0.5;
        int toSpend = (int) Math.floor(acc);

        if (toSpend <= 0) {
            ownerUpkeepAccumulator.put(ownerId, acc);
            return;
        }

        if (plugin.mana().consumeXp(owner, toSpend)) {
            ownerUpkeepAccumulator.put(ownerId, Math.max(0.0, acc - toSpend));
            return;
        }

        ownerUpkeepAccumulator.put(ownerId, Math.min(1.0, acc));
        removeOneExtraSummon(ownerId, owner, summons);
        maybeSendUpkeepWarning(owner, ownerId);
    }

    private void removeOneExtraSummon(UUID ownerId, Player owner, List<Mob> summons) {
        if (summons.isEmpty())
            return;

        Mob victim = null;
        double bestDist = -1.0;
        Location ownerLoc = owner.getLocation();

        for (Mob summon : summons) {
            double dist = summon.getLocation().distanceSquared(ownerLoc);
            if (dist > bestDist) {
                bestDist = dist;
                victim = summon;
            }
        }

        if (victim == null)
            return;

        UUID summonId = victim.getUniqueId();
        victim.remove();
        cleanupSummonRuntime(summonId, ownerId);
        summons.remove(victim);
    }

    private void maybeSendUpkeepWarning(Player owner, UUID ownerId) {
        long now = System.currentTimeMillis();
        long until = ownerUpkeepWarnUntil.getOrDefault(ownerId, 0L);
        if (now < until)
            return;

        ownerUpkeepWarnUntil.put(ownerId, now + upkeepWarnCooldownMs);
        owner.sendMessage("§d[Призыватель] §7Недостаточно маны для содержания лишних призывов.");
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
                wolf.customName(LEGACY.deserialize("§aВолк призывателя §7" + owner.getName()));
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
                phantom.customName(LEGACY.deserialize("§5Фантом призывателя §7" + owner.getName()));
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
                golem.customName(LEGACY.deserialize("§fЖелезный голем §7" + owner.getName()));
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
                vex.customName(LEGACY.deserialize("§dДух призывателя §7" + owner.getName()));
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
                w.customName(LEGACY.deserialize("§8Варден мести §7" + owner.getName()));
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

    private LivingEntity findFocusTarget(Player owner, double range) {
        if (owner == null)
            return null;

        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : owner.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!isEnemy(living, owner.getUniqueId()))
                continue;

            Location center = living.getLocation().clone().add(0, Math.max(0.6, living.getHeight() * 0.5), 0);
            Vector to = center.toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 0.001)
                continue;

            double dot = direction.dot(to.clone().normalize());
            if (dot < 0.20)
                continue;

            if (!owner.hasLineOfSight(living))
                continue;

            double score = dist - (dot * 2.6);
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }

        return best;
    }

    private LivingEntity resolveForcedTarget(UUID ownerId, long nowMs) {
        UUID targetId = ownerForcedTarget.get(ownerId);
        if (targetId == null)
            return null;

        long until = ownerForcedTargetUntil.getOrDefault(ownerId, 0L);
        if (nowMs >= until) {
            ownerForcedTarget.remove(ownerId);
            ownerForcedTargetUntil.remove(ownerId);
            return null;
        }

        Entity targetEntity = Bukkit.getEntity(targetId);
        if (!(targetEntity instanceof LivingEntity living)
                || !living.isValid()
                || living.isDead()
                || !isEnemy(living, ownerId)) {
            ownerForcedTarget.remove(ownerId);
            ownerForcedTargetUntil.remove(ownerId);
            return null;
        }

        return living;
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

    private LivingEntity findPriorityEnemy(Mob summon, Player owner, UUID ownerId) {
        if (summon == null)
            return null;

        LivingEntity aroundSummon = findNearestEnemy(summon.getLocation(), targetSearchRadius, ownerId);
        if (owner == null || !owner.isOnline())
            return aroundSummon;

        LivingEntity aroundOwner = findNearestEnemy(owner.getLocation(), Math.max(12.0, targetSearchRadius * 0.8),
                ownerId);
        if (aroundOwner == null)
            return aroundSummon;
        if (aroundSummon == null)
            return aroundOwner;

        double ownerDist = safeDistanceSquared(summon.getLocation(), aroundOwner.getLocation(), Double.MAX_VALUE);
        double summonDist = safeDistanceSquared(summon.getLocation(), aroundSummon.getLocation(), Double.MAX_VALUE);
        return (ownerDist + 2.0 < summonDist) ? aroundOwner : aroundSummon;
    }

    private boolean shouldDropCurrentTarget(Mob summon, LivingEntity target, UUID ownerId) {
        if (!isEnemy(target, ownerId))
            return true;

        if (summon == null || target == null)
            return true;
        if (summon.getWorld() == null || target.getWorld() == null)
            return true;
        if (!summon.getWorld().getUID().equals(target.getWorld().getUID()))
            return true;

        double maxDistanceSq = aiTargetDropDistance * aiTargetDropDistance;
        return summon.getLocation().distanceSquared(target.getLocation()) > maxDistanceSq;
    }

    private boolean isSummonStuck(Mob summon, Player owner, long nowMs) {
        if (summon == null || owner == null)
            return false;

        UUID summonId = summon.getUniqueId();
        Location current = summon.getLocation();
        Location previous = summonLastKnownLocation.put(summonId, current.clone());

        if (previous == null || previous.getWorld() == null || current.getWorld() == null
                || !previous.getWorld().getUID().equals(current.getWorld().getUID())) {
            summonLastProgressAt.put(summonId, nowMs);
            return false;
        }

        double movedSq = current.distanceSquared(previous);
        if (movedSq >= (0.22 * 0.22)) {
            summonLastProgressAt.put(summonId, nowMs);
            return false;
        }

        LivingEntity target = summon.getTarget();
        if (target != null && isEnemy(target, owner.getUniqueId())) {
            double toTargetSq = safeDistanceSquared(current, target.getLocation(), Double.MAX_VALUE);
            if (toTargetSq <= 3.5 * 3.5) {
                return false;
            }
        }

        if (owner.getWorld() != null && current.getWorld() != null
                && owner.getWorld().getUID().equals(current.getWorld().getUID())) {
            double toOwnerSq = safeDistanceSquared(current, owner.getLocation(), 0.0);
            if (toOwnerSq < aiStuckMinDistanceToOwner * aiStuckMinDistanceToOwner) {
                return false;
            }
        }

        long lastProgress = summonLastProgressAt.getOrDefault(summonId, nowMs);
        return nowMs - lastProgress >= aiStuckTimeoutMs;
    }

    private void unstickSummon(Mob summon, Player owner) {
        if (summon == null || owner == null || !owner.isOnline())
            return;

        Location ownerLoc = owner.getLocation().clone().add(0, 0.25, 0);
        if (summon.getWorld() == null || owner.getWorld() == null
                || !summon.getWorld().getUID().equals(owner.getWorld().getUID())) {
            summon.teleport(ownerLoc.clone().add((rng.nextDouble() - 0.5) * 2.2, 0.0, (rng.nextDouble() - 0.5) * 2.2));
            return;
        }

        double distSq = safeDistanceSquared(summon.getLocation(), ownerLoc, 0.0);
        if (distSq > 12.0 * 12.0) {
            summon.teleport(ownerLoc.clone().add((rng.nextDouble() - 0.5) * 2.4, 0.0, (rng.nextDouble() - 0.5) * 2.4));
        } else {
            Vector toOwner = ownerLoc.toVector().subtract(summon.getLocation().toVector());
            if (toOwner.lengthSquared() > 0.0001) {
                summon.setVelocity(toOwner.normalize().multiply(0.55).setY(0.15));
            }
        }

        Location fx = summon.getLocation().clone().add(0, 0.9, 0);
        summon.getWorld().spawnParticle(Particle.END_ROD, fx, 8, 0.16, 0.22, 0.16, 0.02);
    }

    private double safeDistanceSquared(Location a, Location b, double fallback) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null)
            return fallback;
        if (!a.getWorld().getUID().equals(b.getWorld().getUID()))
            return fallback;
        return a.distanceSquared(b);
    }

    private void followOwnerWhenIdle(Mob summon, Player owner) {
        if (summon == null || owner == null || !owner.isOnline())
            return;

        String summonType = summon.getPersistentDataContainer().get(summonTypeKey, PersistentDataType.STRING);
        if (SummonType.WOLF.configKey().equalsIgnoreCase(summonType))
            return;

        if (!summon.getWorld().getUID().equals(owner.getWorld().getUID())) {
            summon.teleport(owner.getLocation().clone().add(0, 1.0, 0));
            return;
        }

        Location summonLoc = summon.getLocation();
        Location ownerLoc = owner.getLocation().clone().add(0, 0.2, 0);
        double distSq = summonLoc.distanceSquared(ownerLoc);

        if (distSq <= 9.0)
            return;

        if (distSq > 28.0 * 28.0) {
            summon.teleport(ownerLoc.clone().add((rng.nextDouble() - 0.5) * 2.0, 0.0, (rng.nextDouble() - 0.5) * 2.0));
            return;
        }

        Vector toOwner = ownerLoc.toVector().subtract(summonLoc.toVector());
        if (toOwner.lengthSquared() < 0.0001)
            return;

        Vector dir = toOwner.normalize();
        double base = (summon instanceof IronGolem) ? 0.28 : 0.34;
        Vector push = dir.multiply(base);
        Vector vel = summon.getVelocity();

        double yPart = (summon instanceof Phantom || summon instanceof Vex)
                ? Math.max(-0.20, Math.min(0.28, dir.getY() * 0.32))
                : Math.max(-0.12, Math.min(0.20, vel.getY()));

        summon.setVelocity(new Vector(
                vel.getX() * 0.45 + push.getX(),
                yPart,
                vel.getZ() * 0.45 + push.getZ()));
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

        double bonus = getActiveSacrificeDamageBonus(ownerId);
        if (bonus > 0.0) {
            e.setDamage(e.getDamage() * (1.0 + bonus));
            Location at = damaged.getLocation().clone().add(0, Math.max(0.6, damaged.getHeight() * 0.5), 0);
            damaged.getWorld().spawnParticle(Particle.CRIT, at, 5, 0.16, 0.16, 0.16, 0.12);
        }

        if (damaged instanceof Zombie zombie) {
            lastHitOwnerByZombie.put(zombie.getUniqueId(), ownerId);
            lastHitAtByZombie.put(zombie.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        Entity dead = e.getEntity();

        UUID deadId = dead.getUniqueId();
        for (UUID ownerId : new ArrayList<>(ownerForcedTarget.keySet())) {
            UUID focused = ownerForcedTarget.get(ownerId);
            if (focused != null && focused.equals(deadId)) {
                ownerForcedTarget.remove(ownerId);
                ownerForcedTargetUntil.remove(ownerId);
            }
        }

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
