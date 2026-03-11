package me.korgan.deadcycle.player;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Особые скиллы, выдаваемые по условиям (не привязаны к китам).
 */
public class SpecialSkillManager implements Listener {

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
            .legacySection();

    private final DeadCyclePlugin plugin;

    private final NamespacedKey regenKey;
    private final NamespacedKey autoRegenKey;
    private final NamespacedKey autoDodgeKey;

    private final Map<UUID, Double> damageTaken = new HashMap<>();
    private final Map<UUID, Double> damageDealt = new HashMap<>();
    private final Map<UUID, Double> healWithRegen = new HashMap<>();
    private final Map<UUID, Double> manaSpent = new HashMap<>();
    private final Map<UUID, Integer> regenProcCount = new HashMap<>();
    private final Map<UUID, Integer> autoRegenProcCount = new HashMap<>();
    private final Map<UUID, Integer> autoDodgeProcCount = new HashMap<>();

    private final Set<UUID> regenUnlocked = new HashSet<>();
    private final Set<UUID> autoRegenUnlocked = new HashSet<>();
    private final Set<UUID> autoDodgeUnlocked = new HashSet<>();

    private final Map<UUID, Long> regenSkillUntil = new HashMap<>();
    private final Map<UUID, Long> regenInteractUntil = new HashMap<>();
    private final Map<UUID, Boolean> autoRegenEnabled = new HashMap<>();
    private final Map<UUID, Boolean> autoDodgeEnabled = new HashMap<>();
    private final Map<UUID, Double> regenManaAccumulator = new HashMap<>();
    private final Map<UUID, Double> autoRegenManaAccumulator = new HashMap<>();
    private final Map<UUID, Long> outOfManaMessageUntil = new HashMap<>();
    private final Map<UUID, Long> lastSkillTickAt = new HashMap<>();

    private static final class SkillItemCache {
        boolean hasRegenItem;
        boolean hasAutoRegenItem;
        boolean hasAutoDodgeItem;
    }

    private final Map<UUID, SkillItemCache> itemCache = new HashMap<>();
    private final Set<UUID> tickCandidates = new HashSet<>();

    private BukkitTask tickTask;

    // Конфигурация
    private int regenUnlockDamage;
    private int autoRegenUnlockHeal;
    private int autoDodgeUnlockDamageTaken;
    private int autoDodgeUnlockDamageDealt;
    private double regenManaPerSecond;
    private double autoRegenManaPerSecond;
    private int regenAmplifier;
    private int autoRegenAmplifier;
    private int autoDodgeManaCost;
    private double autoDodgeChance;
    private long outOfManaMessageCooldownMs;

    public SpecialSkillManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.regenKey = new NamespacedKey(plugin, "special_regen_item");
        this.autoRegenKey = new NamespacedKey(plugin, "special_auto_regen_item");
        this.autoDodgeKey = new NamespacedKey(plugin, "special_auto_dodge_item");
        loadConfig();
        startTick();
    }

    private void loadConfig() {
        this.regenUnlockDamage = plugin.getConfig().getInt("special_skills.regen_item.damage_taken_to_unlock", 1000);
        this.autoRegenUnlockHeal = plugin.getConfig().getInt("special_skills.auto_regen.heal_to_unlock", 500);
        this.autoDodgeUnlockDamageTaken = plugin.getConfig().getInt("special_skills.auto_dodge.damage_taken", 100);
        this.autoDodgeUnlockDamageDealt = plugin.getConfig().getInt("special_skills.auto_dodge.damage_dealt", 100);
        this.regenManaPerSecond = plugin.getConfig().getDouble("special_skills.regen_item.mana_per_second", 1.0);
        this.autoRegenManaPerSecond = plugin.getConfig().getDouble("special_skills.auto_regen.mana_per_second", 0.5);
        this.regenAmplifier = Math.max(0, plugin.getConfig().getInt("special_skills.regen_item.potion_amplifier", 1));
        this.autoRegenAmplifier = Math.max(0,
                plugin.getConfig().getInt("special_skills.auto_regen.potion_amplifier", 0));
        this.autoDodgeManaCost = plugin.getConfig().getInt("special_skills.auto_dodge.mana_cost", 5);
        this.autoDodgeChance = plugin.getConfig().getDouble("special_skills.auto_dodge.dodge_chance", 0.25);
        this.outOfManaMessageCooldownMs = Math.max(500L,
                plugin.getConfig().getLong("special_skills.out_of_mana_message_cooldown_ms", 2500L));
    }

    public void reload() {
        loadConfig();
        for (Player p : Bukkit.getOnlinePlayers()) {
            rebuildItemCache(p);
        }
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        saveAll();
    }

    private void startTick() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (tickCandidates.isEmpty())
                return;

            for (UUID uuid : new HashSet<>(tickCandidates)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) {
                    tickCandidates.remove(uuid);
                    itemCache.remove(uuid);
                    continue;
                }

                SkillItemCache cache = itemCache.computeIfAbsent(uuid, ignored -> scanSkillItems(p));
                if (!shouldTick(uuid, cache)) {
                    tickCandidates.remove(uuid);
                    continue;
                }

                handleRegenItem(p);
                handleAutoRegen(p);

                if (!shouldTick(uuid, cache)) {
                    tickCandidates.remove(uuid);
                }
            }
        }, 20L, 20L); // раз в секунду
    }

    private SkillItemCache scanSkillItems(Player p) {
        SkillItemCache cache = new SkillItemCache();

        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null)
                continue;

            if (!cache.hasRegenItem && isRegenItem(it))
                cache.hasRegenItem = true;
            if (!cache.hasAutoRegenItem && isAutoRegenItem(it))
                cache.hasAutoRegenItem = true;
            if (!cache.hasAutoDodgeItem && hasKey(it, autoDodgeKey))
                cache.hasAutoDodgeItem = true;

            if (cache.hasRegenItem && cache.hasAutoRegenItem && cache.hasAutoDodgeItem)
                break;
        }

        ItemStack offHand = p.getInventory().getItemInOffHand();
        if (offHand != null) {
            cache.hasRegenItem = cache.hasRegenItem || isRegenItem(offHand);
            cache.hasAutoRegenItem = cache.hasAutoRegenItem || isAutoRegenItem(offHand);
            cache.hasAutoDodgeItem = cache.hasAutoDodgeItem || hasKey(offHand, autoDodgeKey);
        }

        return cache;
    }

    private void rebuildItemCache(Player p) {
        if (p == null)
            return;
        UUID uuid = p.getUniqueId();
        SkillItemCache cache = scanSkillItems(p);
        itemCache.put(uuid, cache);
        if (shouldTick(uuid, cache)) {
            tickCandidates.add(uuid);
        } else {
            tickCandidates.remove(uuid);
        }
    }

    private boolean shouldTick(UUID uuid, SkillItemCache cache) {
        if (cache == null)
            return autoRegenEnabled.getOrDefault(uuid, false)
                    || regenManaAccumulator.containsKey(uuid)
                    || autoRegenManaAccumulator.containsKey(uuid);

        return cache.hasRegenItem
                || cache.hasAutoRegenItem
                || autoRegenEnabled.getOrDefault(uuid, false)
                || regenManaAccumulator.containsKey(uuid)
                || autoRegenManaAccumulator.containsKey(uuid);
    }

    private void touchSkillTick(UUID uuid) {
        lastSkillTickAt.put(uuid, System.currentTimeMillis());
    }

    private void addManaSpent(UUID uuid, double amount) {
        if (amount <= 0.0)
            return;
        manaSpent.put(uuid, manaSpent.getOrDefault(uuid, 0.0) + amount);
    }

    private void incrementCounter(Map<UUID, Integer> counter, UUID uuid) {
        counter.put(uuid, counter.getOrDefault(uuid, 0) + 1);
    }

    private void sendOutOfManaMessage(Player p, String message) {
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        long until = outOfManaMessageUntil.getOrDefault(uuid, 0L);
        if (now < until)
            return;

        outOfManaMessageUntil.put(uuid, now + outOfManaMessageCooldownMs);
        p.sendMessage(message);
    }

    private void handleRegenItem(Player p) {
        UUID uuid = p.getUniqueId();
        if (!isRegenActive(p))
            return;

        if (p.getHealth() >= getMaxHealth(p))
            return;

        if (!spendManaOverTime(p, regenManaPerSecond, regenManaAccumulator)) {
            if (plugin.mana().getCurrentXp(p) <= 0) {
                sendOutOfManaMessage(p, "§cНедостаточно маны для регенерации.");
            }
            return;
        }

        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 40, regenAmplifier, true, false, true));
        regenSkillUntil.put(uuid, System.currentTimeMillis() + 2500L);
        incrementCounter(regenProcCount, uuid);
        touchSkillTick(uuid);
    }

    private void handleAutoRegen(Player p) {
        UUID uuid = p.getUniqueId();
        if (!autoRegenEnabled.getOrDefault(uuid, false))
            return;

        if (!hasAutoRegenItem(p)) {
            autoRegenEnabled.remove(uuid);
            return;
        }

        if (p.getHealth() >= getMaxHealth(p))
            return;

        if (!spendManaOverTime(p, autoRegenManaPerSecond, autoRegenManaAccumulator)) {
            if (plugin.mana().getCurrentXp(p) <= 0) {
                autoRegenEnabled.remove(uuid);
                autoRegenManaAccumulator.remove(uuid);
                sendOutOfManaMessage(p, "§cАвтореген выключен: закончилась мана.");
                rebuildItemCache(p);
            }
            return;
        }

        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 40, autoRegenAmplifier, true, false, true));
        incrementCounter(autoRegenProcCount, uuid);
        touchSkillTick(uuid);
    }

    private boolean spendManaOverTime(Player p, double manaPerSecond, Map<UUID, Double> accumulatorMap) {
        if (manaPerSecond <= 0.0)
            return true;

        UUID uuid = p.getUniqueId();
        double acc = accumulatorMap.getOrDefault(uuid, 0.0);
        acc += manaPerSecond;

        int toSpend = (int) Math.floor(acc);
        if (toSpend > 0) {
            if (!plugin.mana().consumeXp(p, toSpend)) {
                accumulatorMap.put(uuid, acc);
                return false;
            }
            acc -= toSpend;
            addManaSpent(uuid, toSpend);
            touchSkillTick(uuid);
        } else if (plugin.mana().getCurrentXp(p) <= 0) {
            accumulatorMap.put(uuid, acc);
            return false;
        }

        accumulatorMap.put(uuid, acc);
        return true;
    }

    private double getMaxHealth(Player p) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        return (attr != null) ? attr.getValue() : 20.0;
    }

    // ===== Items =====

    public ItemStack createRegenItem() {
        ItemStack it = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§aСкилл: Регенерация"));
            meta.lore(java.util.List.of(
                    LEGACY.deserialize("§7Зажми ПКМ для регена"),
                    LEGACY.deserialize("§7Дает регенерацию II"),
                    LEGACY.deserialize("§7Тратит ману (XP)")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(regenKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    public ItemStack createAutoRegenItem() {
        ItemStack it = new ItemStack(Material.GLOW_BERRIES);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§bСкилл: Автореген"));
            meta.lore(java.util.List.of(
                    LEGACY.deserialize("§7ПКМ — включить/выключить"),
                    LEGACY.deserialize("§7Пассивная регенерация I"),
                    LEGACY.deserialize("§7Тратит ману (XP)")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(autoRegenKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    public ItemStack createAutoDodgeItem() {
        ItemStack it = new ItemStack(Material.FEATHER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§eСкилл: АвтоУклонение"));
            meta.lore(java.util.List.of(
                    LEGACY.deserialize("§7ПКМ — включить/выключить"),
                    LEGACY.deserialize("§7Шанс уклонения от атак"),
                    LEGACY.deserialize("§7Тратит ману (XP)")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(autoDodgeKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    private boolean isRegenItem(ItemStack it) {
        return hasKey(it, regenKey);
    }

    private boolean isAutoRegenItem(ItemStack it) {
        return hasKey(it, autoRegenKey);
    }

    private boolean hasAutoRegenItem(Player p) {
        SkillItemCache cache = itemCache.computeIfAbsent(p.getUniqueId(), ignored -> scanSkillItems(p));
        return cache.hasAutoRegenItem;
    }

    private boolean hasAutoDodgeItem(Player p) {
        SkillItemCache cache = itemCache.computeIfAbsent(p.getUniqueId(), ignored -> scanSkillItems(p));
        return cache.hasAutoDodgeItem;
    }

    private boolean isSpecialItem(ItemStack it) {
        return hasKey(it, regenKey) || hasKey(it, autoRegenKey) || hasKey(it, autoDodgeKey);
    }

    private boolean hasKey(ItemStack it, NamespacedKey key) {
        if (it == null || !it.hasItemMeta())
            return false;
        Byte v = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    private boolean hasItemWithKey(PlayerInventory inv, NamespacedKey key) {
        for (ItemStack it : inv.getContents()) {
            if (hasKey(it, key))
                return true;
        }
        return false;
    }

    private void giveItem(Player p, ItemStack it) {
        var leftovers = p.getInventory().addItem(it);
        if (!leftovers.isEmpty()) {
            for (ItemStack left : leftovers.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
    }

    private void ensureLoaded(UUID uuid) {
        boolean noKnownData = !damageTaken.containsKey(uuid)
                && !damageDealt.containsKey(uuid)
                && !healWithRegen.containsKey(uuid)
                && !manaSpent.containsKey(uuid)
                && !regenUnlocked.contains(uuid)
                && !autoRegenUnlocked.contains(uuid)
                && !autoDodgeUnlocked.contains(uuid);

        if (noKnownData) {
            loadFromStore(uuid);
            return;
        }

        damageTaken.putIfAbsent(uuid, 0.0);
        damageDealt.putIfAbsent(uuid, 0.0);
        healWithRegen.putIfAbsent(uuid, 0.0);
        manaSpent.putIfAbsent(uuid, 0.0);
        regenProcCount.putIfAbsent(uuid, 0);
        autoRegenProcCount.putIfAbsent(uuid, 0);
        autoDodgeProcCount.putIfAbsent(uuid, 0);
    }

    private void loadFromStore(UUID uuid) {
        PlayerDataStore store = plugin.playerData();

        damageTaken.put(uuid, (double) store.getInt(uuid, "special.damage_taken", 0));
        damageDealt.put(uuid, (double) store.getInt(uuid, "special.damage_dealt", 0));
        healWithRegen.put(uuid, (double) store.getInt(uuid, "special.heal_with_regen", 0));
        manaSpent.put(uuid, (double) store.getInt(uuid, "special.mana_spent", 0));
        regenProcCount.put(uuid, store.getInt(uuid, "special.regen_procs", 0));
        autoRegenProcCount.put(uuid, store.getInt(uuid, "special.auto_regen_procs", 0));
        autoDodgeProcCount.put(uuid, store.getInt(uuid, "special.auto_dodge_procs", 0));

        if (store.getInt(uuid, "special.regen_unlocked", 0) == 1)
            regenUnlocked.add(uuid);
        else
            regenUnlocked.remove(uuid);

        if (store.getInt(uuid, "special.auto_regen_unlocked", 0) == 1)
            autoRegenUnlocked.add(uuid);
        else
            autoRegenUnlocked.remove(uuid);

        if (store.getInt(uuid, "special.auto_dodge_unlocked", 0) == 1)
            autoDodgeUnlocked.add(uuid);
        else
            autoDodgeUnlocked.remove(uuid);
    }

    private void persistStats(UUID uuid) {
        ensureLoaded(uuid);
        PlayerDataStore store = plugin.playerData();

        store.setInt(uuid, "special.damage_taken", (int) Math.floor(damageTaken.getOrDefault(uuid, 0.0)));
        store.setInt(uuid, "special.damage_dealt", (int) Math.floor(damageDealt.getOrDefault(uuid, 0.0)));
        store.setInt(uuid, "special.heal_with_regen", (int) Math.floor(healWithRegen.getOrDefault(uuid, 0.0)));
        store.setInt(uuid, "special.mana_spent", (int) Math.floor(manaSpent.getOrDefault(uuid, 0.0)));
        store.setInt(uuid, "special.regen_procs", regenProcCount.getOrDefault(uuid, 0));
        store.setInt(uuid, "special.auto_regen_procs", autoRegenProcCount.getOrDefault(uuid, 0));
        store.setInt(uuid, "special.auto_dodge_procs", autoDodgeProcCount.getOrDefault(uuid, 0));

        store.setInt(uuid, "special.regen_unlocked", regenUnlocked.contains(uuid) ? 1 : 0);
        store.setInt(uuid, "special.auto_regen_unlocked", autoRegenUnlocked.contains(uuid) ? 1 : 0);
        store.setInt(uuid, "special.auto_dodge_unlocked", autoDodgeUnlocked.contains(uuid) ? 1 : 0);
        store.save();
    }

    private void clearRuntimeState(UUID uuid) {
        regenSkillUntil.remove(uuid);
        regenInteractUntil.remove(uuid);
        autoRegenEnabled.remove(uuid);
        autoDodgeEnabled.remove(uuid);
        regenManaAccumulator.remove(uuid);
        autoRegenManaAccumulator.remove(uuid);
        outOfManaMessageUntil.remove(uuid);
        lastSkillTickAt.remove(uuid);
        itemCache.remove(uuid);
        tickCandidates.remove(uuid);
    }

    private void saveAndCleanup(UUID uuid) {
        persistStats(uuid);
        clearRuntimeState(uuid);
    }

    private Set<UUID> knownUuids() {
        Set<UUID> all = new HashSet<>();
        all.addAll(damageTaken.keySet());
        all.addAll(damageDealt.keySet());
        all.addAll(healWithRegen.keySet());
        all.addAll(manaSpent.keySet());
        all.addAll(regenProcCount.keySet());
        all.addAll(autoRegenProcCount.keySet());
        all.addAll(autoDodgeProcCount.keySet());
        all.addAll(regenUnlocked);
        all.addAll(autoRegenUnlocked);
        all.addAll(autoDodgeUnlocked);
        return all;
    }

    // ===== Tracking =====

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();

        ensureLoaded(uuid);
        rebuildItemCache(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        saveAndCleanup(e.getPlayer().getUniqueId());
    }

    public void saveAll() {
        for (UUID uuid : knownUuids()) {
            persistStats(uuid);
        }
    }

    public void resetAll() {
        damageTaken.clear();
        damageDealt.clear();
        healWithRegen.clear();
        manaSpent.clear();
        regenProcCount.clear();
        autoRegenProcCount.clear();
        autoDodgeProcCount.clear();

        regenUnlocked.clear();
        autoRegenUnlocked.clear();
        autoDodgeUnlocked.clear();

        regenSkillUntil.clear();
        regenInteractUntil.clear();
        autoRegenEnabled.clear();
        autoDodgeEnabled.clear();
        regenManaAccumulator.clear();
        autoRegenManaAccumulator.clear();
        outOfManaMessageUntil.clear();
        lastSkillTickAt.clear();
        itemCache.clear();
        tickCandidates.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline())
                continue;
            for (ItemStack it : p.getInventory().getContents()) {
                if (isSpecialItem(it))
                    p.getInventory().remove(it);
            }
        }
    }

    @EventHandler
    public void onDamageTaken(EntityDamageEvent e) {
        if (e.isCancelled())
            return;
        if (!(e.getEntity() instanceof Player p))
            return;

        double amount = e.getDamage();
        UUID uuid = p.getUniqueId();
        double total = damageTaken.getOrDefault(uuid, 0.0) + amount;
        damageTaken.put(uuid, total);

        checkRegenUnlock(p, total);
        checkAutoDodgeUnlock(p, total, damageDealt.getOrDefault(uuid, 0.0));
    }

    @EventHandler
    public void onDamageDealt(EntityDamageByEntityEvent e) {
        if (e.isCancelled())
            return;

        Player damager = null;
        Entity source = e.getDamager();
        if (source instanceof Player p) {
            damager = p;
        } else if (source instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            damager = p;
        }

        if (damager == null)
            return;

        double amount = e.getDamage();
        UUID uuid = damager.getUniqueId();
        double total = damageDealt.getOrDefault(uuid, 0.0) + amount;
        damageDealt.put(uuid, total);

        checkAutoDodgeUnlock(damager, damageTaken.getOrDefault(uuid, 0.0), total);
    }

    @EventHandler
    public void onRegenHeal(EntityRegainHealthEvent e) {
        if (e.isCancelled())
            return;
        if (!(e.getEntity() instanceof Player p))
            return;
        UUID uuid = p.getUniqueId();

        long until = regenSkillUntil.getOrDefault(uuid, 0L);
        if (System.currentTimeMillis() > until)
            return;

        double total = healWithRegen.getOrDefault(uuid, 0.0) + e.getAmount();
        healWithRegen.put(uuid, total);

        checkAutoRegenUnlock(p, total);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p == null)
            return;

        UUID uuid = p.getUniqueId();
        autoRegenEnabled.remove(uuid);
        regenManaAccumulator.remove(uuid);
        autoRegenManaAccumulator.remove(uuid);
        regenSkillUntil.remove(uuid);
        outOfManaMessageUntil.remove(uuid);
        lastSkillTickAt.remove(uuid);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                rebuildItemCache(p);
            }
        });
    }

    // ===== Check if item is special skill =====

    public boolean isSpecialSkill(org.bukkit.inventory.ItemStack item) {
        if (item == null)
            return false;
        if (!item.hasItemMeta())
            return false;

        var meta = item.getItemMeta();
        if (meta == null)
            return false;

        // Проверяем есть ли это один из трёх скиллов
        return meta.getPersistentDataContainer().has(autoRegenKey, org.bukkit.persistence.PersistentDataType.BYTE) ||
                meta.getPersistentDataContainer().has(autoDodgeKey, org.bukkit.persistence.PersistentDataType.BYTE) ||
                meta.getPersistentDataContainer().has(regenKey, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /**
     * Выдает уникальные разблокированные скиллы игроку при возрождении.
     * Нюжно на ререспауне в день в алу.
     */
    public void giveUnlockedSkillsToPlayer(org.bukkit.entity.Player p) {
        if (p == null)
            return;

        UUID uuid = p.getUniqueId();

        // Проверяем все три скилла
        if (regenUnlocked.contains(uuid)) {
            giveItem(p, createRegenItem());
        }
        if (autoRegenUnlocked.contains(uuid)) {
            giveItem(p, createAutoRegenItem());
        }
        if (autoDodgeUnlocked.contains(uuid)) {
            giveItem(p, createAutoDodgeItem());
        }
    }

    private void checkRegenUnlock(Player p, double dmgTakenTotal) {
        UUID uuid = p.getUniqueId();
        if (regenUnlocked.contains(uuid))
            return;
        if (dmgTakenTotal < regenUnlockDamage)
            return;

        regenUnlocked.add(uuid);
        giveItem(p, createRegenItem());
        p.sendMessage("§aВы получили скилл: Регенерация!");
        persistStats(uuid);
        rebuildItemCache(p);
    }

    private void checkAutoRegenUnlock(Player p, double healTotal) {
        UUID uuid = p.getUniqueId();
        if (autoRegenUnlocked.contains(uuid))
            return;
        if (healTotal < autoRegenUnlockHeal)
            return;

        autoRegenUnlocked.add(uuid);
        giveItem(p, createAutoRegenItem());
        p.sendMessage("§bВы получили скилл: Автореген!");
        persistStats(uuid);
        rebuildItemCache(p);
    }

    private void checkAutoDodgeUnlock(Player p, double dmgTakenTotal, double dmgDealtTotal) {
        UUID uuid = p.getUniqueId();
        if (autoDodgeUnlocked.contains(uuid))
            return;
        if (dmgTakenTotal < autoDodgeUnlockDamageTaken)
            return;
        if (dmgDealtTotal < autoDodgeUnlockDamageDealt)
            return;

        autoDodgeUnlocked.add(uuid);
        giveItem(p, createAutoDodgeItem());
        p.sendMessage("§eВы получили скилл: АвтоУклонение!");
        persistStats(uuid);
        rebuildItemCache(p);
    }

    // ===== Item usage =====

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getItem() == null)
            return;

        if (!e.getAction().isRightClick())
            return;

        if (e.getHand() == EquipmentSlot.OFF_HAND) {
            ItemStack main = e.getPlayer().getInventory().getItemInMainHand();
            if (isSpecialItem(main))
                return;
        }

        ItemStack it = e.getItem();

        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        rebuildItemCache(p);

        if (isRegenItem(it)) {
            e.setCancelled(true);
            regenInteractUntil.put(uuid, System.currentTimeMillis() + 2200L);
            return;
        }

        if (isAutoRegenItem(it)) {
            e.setCancelled(true);
            boolean enabled = autoRegenEnabled.getOrDefault(uuid, false);
            autoRegenEnabled.put(uuid, !enabled);

            if (enabled) {
                p.sendMessage("§cАвтореген выключен.");
            } else {
                p.sendMessage("§aАвтореген включен.");
            }
            rebuildItemCache(p);
            return;
        }

        if (hasKey(it, autoDodgeKey)) {
            e.setCancelled(true);
            boolean enabled = autoDodgeEnabled.getOrDefault(uuid, true);
            autoDodgeEnabled.put(uuid, !enabled);

            if (enabled) {
                p.sendMessage("§cАвтоуклонение выключено.");
            } else {
                p.sendMessage("§aАвтоуклонение включено.");
            }
            return;
        }
    }

    @EventHandler
    public void onAutoDodge(EntityDamageEvent e) {
        if (e.isCancelled())
            return;
        if (!(e.getEntity() instanceof Player p))
            return;
        if (!hasAutoDodgeItem(p))
            return;

        UUID uuid = p.getUniqueId();
        if (!autoDodgeEnabled.getOrDefault(uuid, true))
            return;

        if (autoDodgeChance <= 0.0)
            return;

        if (Math.random() > autoDodgeChance)
            return;

        if (!plugin.mana().consumeXp(p, autoDodgeManaCost))
            return;

        e.setCancelled(true);
        addManaSpent(uuid, autoDodgeManaCost);
        incrementCounter(autoDodgeProcCount, uuid);
        touchSkillTick(uuid);

        // Резкий рывок влево/вправо/назад при уклонении
        var dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 0.0001) {
            dir = new org.bukkit.util.Vector(0, 0, 1);
        }
        dir.normalize();
        var right = new org.bukkit.util.Vector(-dir.getZ(), 0, dir.getX());
        int roll = (int) (Math.random() * 3);
        org.bukkit.util.Vector dodge = (roll == 0) ? right : (roll == 1 ? right.multiply(-1) : dir.multiply(-1));
        p.setVelocity(dodge.multiply(0.7).setY(0.15));

        var loc = p.getLocation().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.CLOUD, loc, 12, 0.5, 0.4, 0.5, 0.01);
        p.getWorld().spawnParticle(Particle.CRIT, loc, 8, 0.4, 0.4, 0.4, 0.02);
    }

    private boolean isRegenActive(Player p) {
        UUID uuid = p.getUniqueId();
        SkillItemCache cache = itemCache.computeIfAbsent(uuid, ignored -> scanSkillItems(p));
        boolean regenInMainHand = isRegenItem(p.getInventory().getItemInMainHand());
        boolean regenInOffHand = isRegenItem(p.getInventory().getItemInOffHand());

        if (regenInMainHand || regenInOffHand) {
            cache.hasRegenItem = true;
        }

        if (!regenInMainHand && !regenInOffHand)
            return false;

        long interactUntil = regenInteractUntil.getOrDefault(uuid, 0L);
        if (System.currentTimeMillis() <= interactUntil)
            return true;

        return p.isHandRaised();
    }

    public int getDamageTaken(UUID uuid) {
        ensureLoaded(uuid);
        return (int) Math.floor(damageTaken.getOrDefault(uuid, 0.0));
    }

    public int getDamageDealt(UUID uuid) {
        ensureLoaded(uuid);
        return (int) Math.floor(damageDealt.getOrDefault(uuid, 0.0));
    }

    public int getHealWithRegen(UUID uuid) {
        ensureLoaded(uuid);
        return (int) Math.floor(healWithRegen.getOrDefault(uuid, 0.0));
    }

    public int getManaSpent(UUID uuid) {
        ensureLoaded(uuid);
        return (int) Math.floor(manaSpent.getOrDefault(uuid, 0.0));
    }

    public int getRegenProcCount(UUID uuid) {
        ensureLoaded(uuid);
        return regenProcCount.getOrDefault(uuid, 0);
    }

    public int getAutoRegenProcCount(UUID uuid) {
        ensureLoaded(uuid);
        return autoRegenProcCount.getOrDefault(uuid, 0);
    }

    public int getAutoDodgeProcCount(UUID uuid) {
        ensureLoaded(uuid);
        return autoDodgeProcCount.getOrDefault(uuid, 0);
    }

    public boolean isRegenUnlocked(UUID uuid) {
        ensureLoaded(uuid);
        return regenUnlocked.contains(uuid);
    }

    public boolean isAutoRegenUnlocked(UUID uuid) {
        ensureLoaded(uuid);
        return autoRegenUnlocked.contains(uuid);
    }

    public boolean isAutoDodgeUnlocked(UUID uuid) {
        ensureLoaded(uuid);
        return autoDodgeUnlocked.contains(uuid);
    }

    public int getRemainingToRegenUnlock(UUID uuid) {
        return Math.max(0, regenUnlockDamage - getDamageTaken(uuid));
    }

    public int getRemainingToAutoRegenUnlock(UUID uuid) {
        return Math.max(0, autoRegenUnlockHeal - getHealWithRegen(uuid));
    }

    public int getRemainingToAutoDodgeTakenUnlock(UUID uuid) {
        return Math.max(0, autoDodgeUnlockDamageTaken - getDamageTaken(uuid));
    }

    public int getRemainingToAutoDodgeDealtUnlock(UUID uuid) {
        return Math.max(0, autoDodgeUnlockDamageDealt - getDamageDealt(uuid));
    }

    public boolean isAutoRegenEnabled(UUID uuid) {
        return autoRegenEnabled.getOrDefault(uuid, false);
    }

    public double getRegenAccumulator(UUID uuid) {
        return regenManaAccumulator.getOrDefault(uuid, 0.0);
    }

    public double getAutoRegenAccumulator(UUID uuid) {
        return autoRegenManaAccumulator.getOrDefault(uuid, 0.0);
    }

    public long getLastSkillTickAt(UUID uuid) {
        return lastSkillTickAt.getOrDefault(uuid, 0L);
    }

    public String getSupportedSetStatKeys() {
        return "damage_taken, damage_dealt, heal_with_regen, mana_spent, regen_procs, auto_regen_procs, auto_dodge_procs, regen_unlocked, auto_regen_unlocked, auto_dodge_unlocked, auto_regen_enabled";
    }

    private void removeItemsWithKey(Player p, NamespacedKey key) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (hasKey(it, key)) {
                inv.setItem(i, null);
            }
        }

        ItemStack offHand = inv.getItemInOffHand();
        if (hasKey(offHand, key)) {
            inv.setItemInOffHand(null);
        }
    }

    private void syncUnlockItem(Player p, NamespacedKey key, boolean shouldHave, Supplier<ItemStack> itemSupplier) {
        if (shouldHave) {
            if (!hasItemWithKey(p.getInventory(), key) && !hasKey(p.getInventory().getItemInOffHand(), key)) {
                giveItem(p, itemSupplier.get());
            }
        } else {
            removeItemsWithKey(p, key);
        }
    }

    private void syncUnlockedItems(Player p) {
        UUID uuid = p.getUniqueId();
        syncUnlockItem(p, regenKey, regenUnlocked.contains(uuid), this::createRegenItem);
        syncUnlockItem(p, autoRegenKey, autoRegenUnlocked.contains(uuid), this::createAutoRegenItem);
        syncUnlockItem(p, autoDodgeKey, autoDodgeUnlocked.contains(uuid), this::createAutoDodgeItem);
    }

    public boolean setStat(UUID uuid, String statKey, int value) {
        if (uuid == null || statKey == null || statKey.isBlank())
            return false;

        ensureLoaded(uuid);
        int normalized = Math.max(0, value);
        String key = statKey.toLowerCase(Locale.ROOT);

        switch (key) {
            case "damage_taken" -> damageTaken.put(uuid, (double) normalized);
            case "damage_dealt" -> damageDealt.put(uuid, (double) normalized);
            case "heal_with_regen" -> healWithRegen.put(uuid, (double) normalized);
            case "mana_spent" -> manaSpent.put(uuid, (double) normalized);
            case "regen_procs" -> regenProcCount.put(uuid, normalized);
            case "auto_regen_procs" -> autoRegenProcCount.put(uuid, normalized);
            case "auto_dodge_procs" -> autoDodgeProcCount.put(uuid, normalized);
            case "regen_unlocked" -> {
                if (normalized > 0)
                    regenUnlocked.add(uuid);
                else
                    regenUnlocked.remove(uuid);
            }
            case "auto_regen_unlocked" -> {
                if (normalized > 0)
                    autoRegenUnlocked.add(uuid);
                else
                    autoRegenUnlocked.remove(uuid);
            }
            case "auto_dodge_unlocked" -> {
                if (normalized > 0)
                    autoDodgeUnlocked.add(uuid);
                else
                    autoDodgeUnlocked.remove(uuid);
            }
            case "auto_regen_enabled" -> {
                if (normalized > 0)
                    autoRegenEnabled.put(uuid, true);
                else
                    autoRegenEnabled.remove(uuid);
            }
            default -> {
                return false;
            }
        }

        persistStats(uuid);

        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            syncUnlockedItems(p);
            rebuildItemCache(p);
        }

        return true;
    }

    // ===== Protection =====

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        if (isSpecialItem(item)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cЭтот предмет нельзя выбросить!");
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getPlayer().isOnline())
                rebuildItemCache(e.getPlayer());
        });
    }

    @EventHandler
    public void onHeldSlotChange(PlayerItemHeldEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getPlayer().isOnline())
                rebuildItemCache(e.getPlayer());
        });
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getPlayer().isOnline())
                rebuildItemCache(e.getPlayer());
        });
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline())
                rebuildItemCache(p);
        });
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent e) {
        for (ItemStack ingredient : e.getInventory().getMatrix()) {
            if (isSpecialItem(ingredient)) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    p.sendMessage("§cЭтот предмет нельзя использовать в крафте!");
                }
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        ItemStack cursor = e.getCursor();
        ItemStack clicked = e.getCurrentItem();

        if (cursor != null && isSpecialItem(cursor)) {
            InventoryType type = e.getInventory().getType();
            if (type == InventoryType.FURNACE || type == InventoryType.HOPPER ||
                    type == InventoryType.DISPENSER || type == InventoryType.DROPPER ||
                    type == InventoryType.BREWING || type == InventoryType.ANVIL ||
                    type == InventoryType.BEACON) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    p.sendMessage("§cЭтот предмет нельзя использовать здесь!");
                }
            }
        }

        if (clicked != null && isSpecialItem(clicked)) {
            InventoryType type = e.getInventory().getType();
            if (type == InventoryType.FURNACE || type == InventoryType.HOPPER ||
                    type == InventoryType.DISPENSER || type == InventoryType.DROPPER) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    p.sendMessage("§cЭтот предмет нельзя использовать здесь!");
                }
            }
        }

        if (e.getWhoClicked() instanceof Player p) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline())
                    rebuildItemCache(p);
            });
        }
    }
}
