package me.korgan.deadcycle.adminpower;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AnimePowerManager implements Listener {

    public enum AnimeClass {
        GOJO("gojo", "§b§lГоджо Сатору"),
        SUKUNA("sukuna", "§c§lРёмен Сукуна");

        private final String id;
        private final String displayName;

        AnimeClass(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    private static final class AbilityDef {
        private final AnimeClass owner;
        private final String id;
        private final Material material;
        private final String displayName;
        private final List<String> lore;
        private final int baseManaCost;
        private final long baseCooldownMs;
        private final int xpGain;
        private final boolean domain;

        private AbilityDef(AnimeClass owner, String id, Material material, String displayName,
                List<String> lore, int baseManaCost, long baseCooldownMs, int xpGain, boolean domain) {
            this.owner = owner;
            this.id = id;
            this.material = material;
            this.displayName = displayName;
            this.lore = lore;
            this.baseManaCost = baseManaCost;
            this.baseCooldownMs = baseCooldownMs;
            this.xpGain = xpGain;
            this.domain = domain;
        }
    }

    private static final String GOJO_BLUE = "gojo_blue";
    private static final String GOJO_RED = "gojo_red";
    private static final String GOJO_PURPLE = "gojo_purple";
    private static final String GOJO_INFINITY = "gojo_infinity";
    private static final String GOJO_DOMAIN = "gojo_domain";

    private static final String SUKUNA_DISMANTLE = "sukuna_dismantle";
    private static final String SUKUNA_CLEAVE = "sukuna_cleave";
    private static final String SUKUNA_FUGA = "sukuna_fuga";
    private static final String SUKUNA_REVERSE = "sukuna_reverse";
    private static final String SUKUNA_DOMAIN = "sukuna_domain";

    private final DeadCyclePlugin plugin;
    private final NamespacedKey animePowerKey;

    private final Map<String, AbilityDef> abilities = new LinkedHashMap<>();

    private final Set<UUID> loaded = new HashSet<>();
    private final Map<UUID, AnimeClass> classByPlayer = new HashMap<>();
    private final Map<UUID, Integer> levelByPlayer = new HashMap<>();
    private final Map<UUID, Integer> xpByPlayer = new HashMap<>();

    private final Map<UUID, Map<String, Long>> cooldownByPlayer = new HashMap<>();
    private final Map<UUID, BukkitTask> activeDomainTask = new HashMap<>();
    private final Map<UUID, Boolean> gojoInfinityEnabled = new HashMap<>();
    private final Map<UUID, Long> infinityNoManaWarn = new HashMap<>();

    private int maxLevel;
    private int levelXpBase;
    private int levelXpAdd;
    private boolean adminOnly;
    private double infinityManaPerDamage;
    private int infinityBaseMana;

    public AnimePowerManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.animePowerKey = new NamespacedKey(plugin, "anime_power_ability");

        registerAbilities();
        loadConfig();
    }

    private void registerAbilities() {
        abilities.clear();

        putAbility(new AbilityDef(
                AnimeClass.GOJO,
                GOJO_BLUE,
                Material.LAPIS_LAZULI,
                "§9Синий: Притяжение",
                List.of("§7Стягивает врагов в точку", "§7и наносит урон в зоне"),
                14,
                7000L,
                4,
                false));

        putAbility(new AbilityDef(
                AnimeClass.GOJO,
                GOJO_RED,
                Material.REDSTONE,
                "§cКрасный: Отталкивание",
                List.of("§7Выбрасывает врагов взрывной", "§7волной и наносит урон"),
                16,
                8500L,
                4,
                false));

        putAbility(new AbilityDef(
                AnimeClass.GOJO,
                GOJO_PURPLE,
                Material.AMETHYST_SHARD,
                "§5Пустотный Пурпур",
                List.of("§7Пробивающий луч с большим", "§7уроном по линии"),
                30,
                17000L,
                8,
                false));

        putAbility(new AbilityDef(
                AnimeClass.GOJO,
                GOJO_INFINITY,
                Material.HEART_OF_THE_SEA,
                "§bБесконечность",
                List.of("§7ПКМ: включить/выключить", "§7Пассивно блокирует входящий урон", "§7за расход маны"),
                0,
                1200L,
                2,
                false));

        putAbility(new AbilityDef(
                AnimeClass.GOJO,
                GOJO_DOMAIN,
                Material.END_CRYSTAL,
                "§dРасширение Территории: Безграничная Пустота",
                List.of("§7Купол контроля: замедляет и", "§7подавляет врагов вокруг"),
                46,
                70000L,
                14,
                true));

        putAbility(new AbilityDef(
                AnimeClass.SUKUNA,
                SUKUNA_DISMANTLE,
                Material.IRON_SWORD,
                "§cDismantle",
                List.of("§7Фронтальная режущая", "§7волна по конусу"),
                12,
                5200L,
                4,
                false));

        putAbility(new AbilityDef(
                AnimeClass.SUKUNA,
                SUKUNA_CLEAVE,
                Material.NETHERITE_SWORD,
                "§4Cleave",
                List.of("§7Точный разрез по цели", "§7с уроном от max HP"),
                18,
                9500L,
                6,
                false));

        putAbility(new AbilityDef(
                AnimeClass.SUKUNA,
                SUKUNA_FUGA,
                Material.BLAZE_ROD,
                "§6Fuga",
                List.of("§7Огненный выстрел и", "§7взрыв в точке попадания"),
                26,
                16000L,
                8,
                false));

        putAbility(new AbilityDef(
                AnimeClass.SUKUNA,
                SUKUNA_REVERSE,
                Material.GHAST_TEAR,
                "§aОбратная Проклятая Техника",
                List.of("§7Мгновенно восстанавливает", "§7HP и снимает часть дебаффов"),
                20,
                15000L,
                5,
                false));

        putAbility(new AbilityDef(
                AnimeClass.SUKUNA,
                SUKUNA_DOMAIN,
                Material.WITHER_SKELETON_SKULL,
                "§4Расширение Территории: Malevolent Shrine",
                List.of("§7Открытая территория разрезов", "§7с непрерывным уроном"),
                44,
                68000L,
                14,
                true));
    }

    private void putAbility(AbilityDef def) {
        abilities.put(def.id, def);
    }

    private void loadConfig() {
        this.maxLevel = Math.max(1, plugin.getConfig().getInt("anime_powers.max_level", 10));
        this.levelXpBase = Math.max(1, plugin.getConfig().getInt("anime_powers.level_xp_base", 35));
        this.levelXpAdd = Math.max(0, plugin.getConfig().getInt("anime_powers.level_xp_add_per_level", 25));
        this.adminOnly = plugin.getConfig().getBoolean("anime_powers.admin_only", true);
        this.infinityManaPerDamage = Math.max(0.3,
                plugin.getConfig().getDouble("anime_powers.gojo.infinity_mana_per_damage", 1.35));
        this.infinityBaseMana = Math.max(0,
                plugin.getConfig().getInt("anime_powers.gojo.infinity_base_mana", 2));
    }

    public void reload() {
        loadConfig();
    }

    public void shutdown() {
        for (BukkitTask task : activeDomainTask.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        activeDomainTask.clear();

        for (UUID uuid : new HashSet<>(loaded)) {
            saveProfile(uuid);
        }

        plugin.playerData().save();
    }

    public boolean grantPower(UUID uuid, String classAlias, int level) {
        AnimeClass animeClass = parseClassAlias(classAlias);
        if (animeClass == null) {
            return false;
        }

        ensureLoaded(uuid);
        classByPlayer.put(uuid, animeClass);
        levelByPlayer.put(uuid, clampLevel(level));
        xpByPlayer.put(uuid, 0);
        gojoInfinityEnabled.put(uuid, false);
        cooldownByPlayer.remove(uuid);

        saveProfile(uuid);
        return true;
    }

    public void clearPower(UUID uuid) {
        ensureLoaded(uuid);
        classByPlayer.remove(uuid);
        levelByPlayer.remove(uuid);
        xpByPlayer.remove(uuid);
        cooldownByPlayer.remove(uuid);
        gojoInfinityEnabled.remove(uuid);
        infinityNoManaWarn.remove(uuid);

        stopDomain(uuid);
        saveProfile(uuid);
    }

    public void setPowerLevel(UUID uuid, int level) {
        ensureLoaded(uuid);
        if (!classByPlayer.containsKey(uuid)) {
            return;
        }
        levelByPlayer.put(uuid, clampLevel(level));
        saveProfile(uuid);
    }

    public AnimeClass getPowerClass(UUID uuid) {
        ensureLoaded(uuid);
        return classByPlayer.get(uuid);
    }

    public String getPowerClassDisplay(UUID uuid) {
        AnimeClass animeClass = getPowerClass(uuid);
        return animeClass != null ? animeClass.displayName() : "§7нет";
    }

    public int getPowerLevel(UUID uuid) {
        ensureLoaded(uuid);
        return levelByPlayer.getOrDefault(uuid, 1);
    }

    public int getPowerExp(UUID uuid) {
        ensureLoaded(uuid);
        return xpByPlayer.getOrDefault(uuid, 0);
    }

    public int getNeedExp(UUID uuid) {
        int level = getPowerLevel(uuid);
        return calcNeed(level);
    }

    public void syncItems(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        ensureLoaded(player.getUniqueId());
        removePowerItems(player);

        AnimeClass animeClass = classByPlayer.get(player.getUniqueId());
        if (animeClass == null) {
            return;
        }

        int level = getPowerLevel(player.getUniqueId());
        List<AbilityDef> classAbilities = getAbilitiesForClass(animeClass);
        for (AbilityDef def : classAbilities) {
            ItemStack item = createAbilityItem(def, level);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    public boolean hasPowerItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        String id = meta.getPersistentDataContainer().get(animePowerKey, PersistentDataType.STRING);
        return id != null && !id.isBlank();
    }

    public AnimeClass parseClassAlias(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "gojo", "satoru", "infinity", "limitless" -> AnimeClass.GOJO;
            case "sukuna", "king", "shrine", "malevolent" -> AnimeClass.SUKUNA;
            default -> null;
        };
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        ensureLoaded(player.getUniqueId());

        if (classByPlayer.containsKey(player.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    syncItems(player);
                }
            }, 10L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        saveProfile(uuid);
        cooldownByPlayer.remove(uuid);
        infinityNoManaWarn.remove(uuid);
        stopDomain(uuid);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (!action.isRightClick()) {
            return;
        }

        if (e.getHand() == EquipmentSlot.OFF_HAND) {
            ItemStack main = e.getPlayer().getInventory().getItemInMainHand();
            if (hasPowerItem(main)) {
                return;
            }
        }

        ItemStack item = e.getItem();
        if (!hasPowerItem(item)) {
            return;
        }

        e.setCancelled(true);

        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();

        ensureLoaded(uuid);

        if (adminOnly && !isAdminUser(player)) {
            player.sendMessage("§cЭти силы доступны только админам.");
            return;
        }

        AnimeClass animeClass = classByPlayer.get(uuid);
        if (animeClass == null) {
            player.sendMessage("§cТебе не выдана аниме-сила.");
            return;
        }

        String abilityId = getAbilityId(item);
        AbilityDef def = abilities.get(abilityId);
        if (def == null) {
            return;
        }

        if (def.owner != animeClass) {
            player.sendMessage("§cТы не владеешь этой техникой.");
            return;
        }

        if (GOJO_INFINITY.equals(abilityId)) {
            toggleInfinity(player);
            gainExperience(player, def.xpGain);
            return;
        }

        long remaining = getRemainingCooldown(uuid, abilityId);
        if (remaining > 0L) {
            long seconds = (remaining + 999L) / 1000L;
            player.sendMessage("§cТехника перезаряжается: §e" + seconds + "§cс.");
            return;
        }

        int level = getPowerLevel(uuid);
        int manaCost = getManaCost(def, level);
        if (!plugin.mana().consumeXp(player, manaCost)) {
            int cur = plugin.mana().getCurrentXp(player);
            int max = plugin.mana().getMaxXp(uuid);
            player.sendMessage("§cНедостаточно маны: §e" + cur + "§7/§3" + max + " §c(нужно §e" + manaCost + "§c)");
            return;
        }

        boolean activated = activateAbility(player, level, def);
        if (!activated) {
            plugin.mana().addXp(player, manaCost);
            return;
        }

        long cooldownMs = getCooldownMs(def, level);
        setCooldown(uuid, abilityId, System.currentTimeMillis() + cooldownMs);
        gainExperience(player, def.xpGain + (def.domain ? 4 : 0));
    }

    @EventHandler
    public void onInfinityBlock(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        ensureLoaded(uuid);

        if (classByPlayer.get(uuid) != AnimeClass.GOJO) {
            return;
        }

        if (!gojoInfinityEnabled.getOrDefault(uuid, false)) {
            return;
        }

        if (adminOnly && !isAdminUser(player)) {
            return;
        }

        if (e.getCause() == EntityDamageEvent.DamageCause.VOID
                || e.getCause() == EntityDamageEvent.DamageCause.SUICIDE
                || e.getCause() == EntityDamageEvent.DamageCause.KILL) {
            return;
        }

        int manaCost = infinityBaseMana + (int) Math.ceil(Math.max(0.0, e.getFinalDamage()) * infinityManaPerDamage);
        if (!plugin.mana().consumeXp(player, manaCost)) {
            gojoInfinityEnabled.put(uuid, false);

            long now = System.currentTimeMillis();
            long lastWarn = infinityNoManaWarn.getOrDefault(uuid, 0L);
            if (now - lastWarn > 1400L) {
                player.sendMessage("§cБесконечность отключена: не хватает маны.");
                infinityNoManaWarn.put(uuid, now);
            }
            return;
        }

        e.setCancelled(true);

        Location at = player.getLocation().add(0, 1.1, 0);
        World world = player.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(180, 230, 255), 1.1f);
        world.spawnParticle(Particle.DUST, at, 18, 0.35, 0.45, 0.35, 0.0, dust);
        world.spawnParticle(Particle.END_ROD, at, 8, 0.2, 0.35, 0.2, 0.02);
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.9f);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        if (!hasPowerItem(item)) {
            return;
        }

        e.setCancelled(true);
        e.getPlayer().sendMessage("§cЭтот предмет нельзя выбросить.");
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        for (ItemStack ingredient : e.getInventory().getMatrix()) {
            if (hasPowerItem(ingredient)) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player player) {
                    player.sendMessage("§cЭтот предмет нельзя использовать в крафте.");
                }
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        ItemStack cursor = e.getCursor();
        ItemStack clicked = e.getCurrentItem();

        boolean carriesPowerItem = hasPowerItem(cursor) || hasPowerItem(clicked);
        if (!carriesPowerItem) {
            return;
        }

        InventoryType type = e.getInventory().getType();
        if (type == InventoryType.FURNACE
                || type == InventoryType.BLAST_FURNACE
                || type == InventoryType.SMOKER
                || type == InventoryType.HOPPER
                || type == InventoryType.DROPPER
                || type == InventoryType.DISPENSER
                || type == InventoryType.BREWING
                || type == InventoryType.ANVIL
                || type == InventoryType.SMITHING
                || type == InventoryType.BEACON
                || type == InventoryType.GRINDSTONE) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player player) {
                player.sendMessage("§cЭтот предмет нельзя использовать в этом инвентаре.");
            }
        }
    }

    private boolean activateAbility(Player player, int level, AbilityDef def) {
        return switch (def.id) {
            case GOJO_BLUE -> castGojoBlue(player, level);
            case GOJO_RED -> castGojoRed(player, level);
            case GOJO_PURPLE -> castGojoPurple(player, level);
            case GOJO_DOMAIN -> castGojoDomain(player, level);
            case SUKUNA_DISMANTLE -> castSukunaDismantle(player, level);
            case SUKUNA_CLEAVE -> castSukunaCleave(player, level);
            case SUKUNA_FUGA -> castSukunaFuga(player, level);
            case SUKUNA_REVERSE -> castSukunaReverse(player, level);
            case SUKUNA_DOMAIN -> castSukunaDomain(player, level);
            default -> false;
        };
    }

    private boolean castGojoBlue(Player player, int level) {
        Location center = resolveTargetPoint(player, 22, 8.0);
        double radius = 4.8 + (level * 0.26);
        double pullPower = 0.65 + (level * 0.04);
        double damage = 2.4 + (level * 0.35);

        List<LivingEntity> targets = collectTargets(center, radius, player);
        for (LivingEntity target : targets) {
            Vector pull = center.toVector().subtract(target.getLocation().toVector());
            if (pull.lengthSquared() < 0.0001) {
                pull = new Vector(0, 0, 0);
            } else {
                pull.normalize().multiply(pullPower);
            }
            pull.setY(0.25 + pull.getY() * 0.2);
            target.setVelocity(target.getVelocity().multiply(0.35).add(pull));
            target.damage(damage, player);
        }

        World world = player.getWorld();
        Particle.DustOptions blue = new Particle.DustOptions(Color.fromRGB(90, 180, 255), 1.2f);
        spawnRing(world, center.clone().add(0, 0.2, 0), radius, 56, blue);
        world.spawnParticle(Particle.PORTAL, center, 60, radius * 0.24, 0.7, radius * 0.24, 0.08);
        world.spawnParticle(Particle.END_ROD, center, 20, 0.35, 0.6, 0.35, 0.02);
        world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 0.55f);
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.7f);

        player.sendActionBar(net.kyori.adventure.text.Component.text("§9Blue активирован"));
        return true;
    }

    private boolean castGojoRed(Player player, int level) {
        Location center = resolveTargetPoint(player, 18, 5.0);
        double radius = 5.3 + (level * 0.25);
        double knockback = 1.15 + (level * 0.05);
        double damage = 3.1 + (level * 0.38);

        List<LivingEntity> targets = collectTargets(center, radius, player);
        for (LivingEntity target : targets) {
            Vector away = target.getLocation().toVector().subtract(center.toVector());
            if (away.lengthSquared() < 0.0001) {
                away = target.getLocation().getDirection().clone();
            }
            if (away.lengthSquared() < 0.0001) {
                away = new Vector(0, 0, 1);
            }
            away.normalize().multiply(knockback).setY(0.36);
            target.setVelocity(away);
            target.damage(damage, player);
        }

        World world = player.getWorld();
        Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(255, 70, 70), 1.35f);
        spawnRing(world, center.clone().add(0, 0.15, 0), radius, 64, red);
        world.spawnParticle(Particle.EXPLOSION, center, 5, 0.15, 0.15, 0.15, 0.0);
        world.spawnParticle(Particle.CRIT, center, 45, radius * 0.2, 0.6, radius * 0.2, 0.18);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.75f);
        world.playSound(center, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.7f, 1.3f);
        return true;
    }

    private boolean castGojoPurple(Player player, int level) {
        World world = player.getWorld();
        Location start = player.getEyeLocation();
        Vector direction = start.getDirection().normalize();

        double length = 22.0 + (level * 0.8);
        double step = 0.7;
        double beamRadius = 1.25 + (level * 0.025);
        double damage = 8.2 + (level * 0.75);

        Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(178, 82, 255), 1.45f);
        Set<UUID> hit = new HashSet<>();

        Location end = start.clone();
        for (double d = 0.0; d <= length; d += step) {
            Location point = start.clone().add(direction.clone().multiply(d));
            end = point;

            world.spawnParticle(Particle.DUST, point, 2, 0.04, 0.04, 0.04, 0.0, purple);
            world.spawnParticle(Particle.END_ROD, point, 1, 0.01, 0.01, 0.01, 0.0);

            for (Entity entity : world.getNearbyEntities(point, beamRadius, beamRadius, beamRadius)) {
                if (!(entity instanceof LivingEntity target)) {
                    continue;
                }
                if (target.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                if (target instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                if (!hit.add(target.getUniqueId())) {
                    continue;
                }

                target.damage(damage, player);
                Vector kb = direction.clone().multiply(0.85).setY(0.18);
                target.setVelocity(target.getVelocity().multiply(0.2).add(kb));
            }
        }

        world.spawnParticle(Particle.EXPLOSION, end, 8, 0.3, 0.3, 0.3, 0.0);
        world.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.7f);
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.9f, 0.5f);
        return true;
    }

    private boolean castGojoDomain(Player player, int level) {
        UUID uuid = player.getUniqueId();
        if (activeDomainTask.containsKey(uuid)) {
            player.sendMessage("§cТерритория уже активна.");
            return false;
        }

        int durationTicks = 120 + level * 6;
        double radius = 8.2 + level * 0.35;

        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.35f);
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.7f);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int lived = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    stopDomain(uuid);
                    return;
                }

                if (lived >= durationTicks) {
                    stopDomain(uuid);
                    player.sendMessage("§dТерритория Годжо рассеялась.");
                    world.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.6f);
                    return;
                }

                Location center = player.getLocation().clone().add(0, 0.2, 0);
                Particle.DustOptions ring = new Particle.DustOptions(Color.fromRGB(210, 170, 255), 1.2f);
                spawnRing(world, center, radius, 72, ring);
                world.spawnParticle(Particle.END_ROD, center, 18, radius * 0.24, 0.6, radius * 0.24, 0.02);

                double tickDamage = 1.7 + level * 0.18;
                for (LivingEntity target : collectTargets(center, radius, player)) {
                    target.damage(tickDamage, player);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 24, 3, true, false, true));
                    target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 24, 1, true, false, true));
                    target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 24, 0, true, false, true));
                    Vector drag = center.toVector().subtract(target.getLocation().toVector()).multiply(0.05)
                            .setY(-0.02);
                    target.setVelocity(target.getVelocity().multiply(0.4).add(drag));
                }

                lived += 8;
            }
        }, 0L, 8L);

        activeDomainTask.put(uuid, task);
        return true;
    }

    private boolean castSukunaDismantle(Player player, int level) {
        double range = 7.4 + level * 0.28;
        double minDot = 0.42;
        double damage = 5.2 + level * 0.55;

        List<LivingEntity> targets = collectFrontTargets(player, range, minDot);
        if (targets.isEmpty()) {
            player.sendMessage("§7Целей перед тобой нет.");
            return false;
        }

        World world = player.getWorld();
        Particle.DustOptions slash = new Particle.DustOptions(Color.fromRGB(215, 40, 40), 1.15f);

        for (LivingEntity target : targets) {
            target.damage(damage, player);
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0, true, false, true));
            Location mid = target.getLocation().clone().add(0, target.getHeight() * 0.5, 0);
            world.spawnParticle(Particle.DUST, mid, 10, 0.22, 0.28, 0.22, 0.0, slash);
            world.spawnParticle(Particle.SWEEP_ATTACK, mid, 3, 0.1, 0.1, 0.1, 0.0);
        }

        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);
        return true;
    }

    private boolean castSukunaCleave(Player player, int level) {
        LivingEntity target = findPrimaryFrontTarget(player, 14.0 + level * 0.35, 0.5);
        if (target == null) {
            player.sendMessage("§7Нет цели для Cleave.");
            return false;
        }

        double maxHealth = getMaxHealth(target);
        double ratio = Math.min(0.26, 0.11 + level * 0.006);
        double damage = Math.max(5.0, maxHealth * ratio);

        target.damage(damage, player);

        Vector kb = target.getLocation().toVector().subtract(player.getLocation().toVector());
        if (kb.lengthSquared() < 0.0001) {
            kb = player.getLocation().getDirection().clone();
        }
        kb.normalize().multiply(0.85).setY(0.18);
        target.setVelocity(target.getVelocity().multiply(0.4).add(kb));

        World world = player.getWorld();
        Particle.DustOptions blood = new Particle.DustOptions(Color.fromRGB(170, 25, 25), 1.25f);
        Location hit = target.getLocation().clone().add(0, target.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.DUST, hit, 18, 0.25, 0.35, 0.25, 0.0, blood);
        world.spawnParticle(Particle.SWEEP_ATTACK, hit, 5, 0.15, 0.12, 0.15, 0.0);
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.85f, 0.65f);
        return true;
    }

    private boolean castSukunaFuga(Player player, int level) {
        Location impact = resolveTargetPoint(player, 26, 13.0);
        Location start = player.getEyeLocation();
        Vector direction = impact.toVector().subtract(start.toVector());
        if (direction.lengthSquared() < 0.0001) {
            direction = start.getDirection().clone();
        }
        direction.normalize();

        World world = player.getWorld();
        double length = Math.min(28.0, start.distance(impact));
        for (double d = 0.0; d <= length; d += 0.65) {
            Location point = start.clone().add(direction.clone().multiply(d));
            world.spawnParticle(Particle.FLAME, point, 3, 0.05, 0.05, 0.05, 0.01);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, point, 1, 0.02, 0.02, 0.02, 0.0);
        }

        double radius = 3.6 + level * 0.16;
        double damage = 7.0 + level * 0.65;

        for (LivingEntity target : collectTargets(impact, radius, player)) {
            target.damage(damage, player);
            target.setFireTicks(Math.max(target.getFireTicks(), 80 + level * 8));

            Vector away = target.getLocation().toVector().subtract(impact.toVector());
            if (away.lengthSquared() < 0.0001) {
                away = direction.clone();
            }
            away.normalize().multiply(0.8).setY(0.28);
            target.setVelocity(target.getVelocity().multiply(0.35).add(away));
        }

        world.spawnParticle(Particle.EXPLOSION, impact, 9, 0.35, 0.35, 0.35, 0.0);
        world.spawnParticle(Particle.FLAME, impact, 90, radius * 0.22, 0.5, radius * 0.22, 0.03);
        world.playSound(impact, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.7f);
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.85f);
        return true;
    }

    private boolean castSukunaReverse(Player player, int level) {
        double heal = 5.5 + (level * 0.85);
        double max = getMaxHealth(player);
        if (player.getHealth() >= max - 0.01) {
            player.sendMessage("§7HP уже полное.");
            return false;
        }

        player.setHealth(Math.min(max, player.getHealth() + heal));

        player.removePotionEffect(PotionEffectType.WITHER);
        player.removePotionEffect(PotionEffectType.POISON);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);

        World world = player.getWorld();
        Location at = player.getLocation().clone().add(0, 1.0, 0);
        Particle.DustOptions green = new Particle.DustOptions(Color.fromRGB(120, 255, 140), 1.15f);
        world.spawnParticle(Particle.HEART, at, 8, 0.35, 0.4, 0.35, 0.04);
        world.spawnParticle(Particle.DUST, at, 18, 0.22, 0.3, 0.22, 0.0, green);
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        return true;
    }

    private boolean castSukunaDomain(Player player, int level) {
        UUID uuid = player.getUniqueId();
        if (activeDomainTask.containsKey(uuid)) {
            player.sendMessage("§cТерритория уже активна.");
            return false;
        }

        int durationTicks = 136 + level * 8;
        double radius = 10.2 + level * 0.45;

        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.65f);
        world.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.95f, 0.55f);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int lived = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    stopDomain(uuid);
                    return;
                }

                if (lived >= durationTicks) {
                    stopDomain(uuid);
                    player.sendMessage("§4Malevolent Shrine рассеялась.");
                    world.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 0.65f);
                    return;
                }

                Location center = player.getLocation().clone().add(0, 0.15, 0);
                Particle.DustOptions crimson = new Particle.DustOptions(Color.fromRGB(180, 30, 30), 1.2f);
                spawnRing(world, center, radius, 84, crimson);
                world.spawnParticle(Particle.ASH, center, 26, radius * 0.22, 0.5, radius * 0.22, 0.03);

                double tickDamage = 2.2 + level * 0.24;
                for (LivingEntity target : collectTargets(center, radius, player)) {
                    target.damage(tickDamage, player);
                    target.setVelocity(target.getVelocity().multiply(0.55));
                    if (Math.random() < 0.30) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 44, 0, true, false, true));
                    }
                }

                lived += 8;
            }
        }, 0L, 8L);

        activeDomainTask.put(uuid, task);
        return true;
    }

    private int getManaCost(AbilityDef def, int level) {
        if (def.baseManaCost <= 0) {
            return 0;
        }

        double scale = def.domain
                ? Math.max(0.72, 1.0 - (level - 1) * 0.018)
                : Math.max(0.58, 1.0 - (level - 1) * 0.023);

        return Math.max(1, (int) Math.round(def.baseManaCost * scale));
    }

    private long getCooldownMs(AbilityDef def, int level) {
        double scale = def.domain
                ? Math.max(0.70, 1.0 - (level - 1) * 0.013)
                : Math.max(0.50, 1.0 - (level - 1) * 0.024);

        return Math.max(600L, (long) Math.round(def.baseCooldownMs * scale));
    }

    private long getRemainingCooldown(UUID uuid, String abilityId) {
        Map<String, Long> map = cooldownByPlayer.get(uuid);
        if (map == null) {
            return 0L;
        }

        Long until = map.get(abilityId);
        if (until == null) {
            return 0L;
        }

        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            map.remove(abilityId);
            return 0L;
        }

        return left;
    }

    private void setCooldown(UUID uuid, String abilityId, long until) {
        cooldownByPlayer.computeIfAbsent(uuid, ignored -> new HashMap<>())
                .put(abilityId, until);
    }

    private void gainExperience(Player player, int add) {
        if (player == null || add <= 0) {
            return;
        }

        UUID uuid = player.getUniqueId();
        ensureLoaded(uuid);

        if (!classByPlayer.containsKey(uuid)) {
            return;
        }

        int level = getPowerLevel(uuid);
        if (level >= maxLevel) {
            return;
        }

        int xp = xpByPlayer.getOrDefault(uuid, 0) + add;
        int need = calcNeed(level);

        boolean leveled = false;
        while (xp >= need && level < maxLevel) {
            xp -= need;
            level++;
            leveled = true;
            need = calcNeed(level);
        }

        if (level >= maxLevel) {
            xp = 0;
        }

        levelByPlayer.put(uuid, level);
        xpByPlayer.put(uuid, xp);

        if (leveled) {
            player.sendMessage("§6[AnimePower] §aНовый уровень силы: §e" + level);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
            syncItems(player);
        }

        saveProfile(uuid);
    }

    private int calcNeed(int level) {
        int lvl = Math.max(1, level);
        return levelXpBase + ((lvl - 1) * levelXpAdd);
    }

    private int clampLevel(int value) {
        return Math.max(1, Math.min(maxLevel, value));
    }

    private void toggleInfinity(Player player) {
        UUID uuid = player.getUniqueId();
        boolean enabled = gojoInfinityEnabled.getOrDefault(uuid, false);
        gojoInfinityEnabled.put(uuid, !enabled);

        if (!enabled) {
            player.sendMessage("§bБесконечность включена.");
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.7f);
        } else {
            player.sendMessage("§7Бесконечность выключена.");
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.1f);
        }
    }

    private void ensureLoaded(UUID uuid) {
        if (!loaded.add(uuid)) {
            return;
        }
        loadProfile(uuid);
    }

    private void loadProfile(UUID uuid) {
        String cls = plugin.playerData().getString(uuid, "anime.class", "");
        AnimeClass animeClass = parseClassAlias(cls);
        if (animeClass == null) {
            return;
        }

        int level = clampLevel(plugin.playerData().getInt(uuid, "anime.level", 1));
        int xp = Math.max(0, plugin.playerData().getInt(uuid, "anime.exp", 0));

        classByPlayer.put(uuid, animeClass);
        levelByPlayer.put(uuid, level);
        xpByPlayer.put(uuid, xp);
        gojoInfinityEnabled.put(uuid, false);
    }

    private void saveProfile(UUID uuid) {
        AnimeClass animeClass = classByPlayer.get(uuid);

        if (animeClass == null) {
            plugin.playerData().setString(uuid, "anime.class", null);
            plugin.playerData().setInt(uuid, "anime.level", 0);
            plugin.playerData().setInt(uuid, "anime.exp", 0);
        } else {
            plugin.playerData().setString(uuid, "anime.class", animeClass.id());
            plugin.playerData().setInt(uuid, "anime.level", getPowerLevel(uuid));
            plugin.playerData().setInt(uuid, "anime.exp", getPowerExp(uuid));
        }

        plugin.playerData().save();
    }

    private boolean isAdminUser(Player player) {
        return player != null && (player.isOp() || player.hasPermission("deadcycle.admin"));
    }

    private void stopDomain(UUID uuid) {
        BukkitTask task = activeDomainTask.remove(uuid);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private String getAbilityId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().get(animePowerKey, PersistentDataType.STRING);
    }

    private List<AbilityDef> getAbilitiesForClass(AnimeClass animeClass) {
        if (animeClass == null) {
            return Collections.emptyList();
        }

        List<AbilityDef> list = new ArrayList<>();
        for (AbilityDef def : abilities.values()) {
            if (def.owner == animeClass) {
                list.add(def);
            }
        }
        return list;
    }

    private ItemStack createAbilityItem(AbilityDef def, int level) {
        ItemStack item = new ItemStack(def.material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(def.displayName);

            List<String> lore = new ArrayList<>(def.lore);
            int mana = getManaCost(def, level);
            long cdSec = Math.max(1L, getCooldownMs(def, level) / 1000L);
            lore.add("§8");
            lore.add("§7Мана: §b" + mana);
            lore.add("§7КД: §e" + cdSec + "с");
            lore.add("§7Уровень силы: §d" + level);

            meta.setLore(lore);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(animePowerKey, PersistentDataType.STRING, def.id);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void removePowerItems(Player player) {
        if (player == null) {
            return;
        }

        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (hasPowerItem(item)) {
                player.getInventory().setItem(slot, null);
            }
        }

        ItemStack off = player.getInventory().getItemInOffHand();
        if (hasPowerItem(off)) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    private Location resolveTargetPoint(Player player, int maxDistance, double fallbackAhead) {
        Location eye = player.getEyeLocation();
        World world = player.getWorld();

        try {
            org.bukkit.block.Block block = player.getTargetBlockExact(maxDistance);
            if (block != null) {
                return block.getLocation().add(0.5, 1.0, 0.5);
            }
        } catch (Throwable ignored) {
        }

        Vector direction = eye.getDirection().normalize();
        return eye.clone().add(direction.multiply(fallbackAhead));
    }

    private List<LivingEntity> collectTargets(Location center, double radius, Player caster) {
        if (center == null || center.getWorld() == null) {
            return Collections.emptyList();
        }

        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target)) {
                continue;
            }
            if (target.getUniqueId().equals(caster.getUniqueId())) {
                continue;
            }
            if (target instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    private LivingEntity findPrimaryFrontTarget(Player player, double range, double minDot) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity target)) {
                continue;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            if (target instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            Vector toTarget = target.getLocation().add(0, target.getHeight() * 0.5, 0).toVector()
                    .subtract(eye.toVector());
            double distance = toTarget.length();
            if (distance > range || distance < 0.001) {
                continue;
            }

            Vector normalized = toTarget.clone().normalize();
            double dot = direction.dot(normalized);
            if (dot < minDot) {
                continue;
            }

            double score = distance - dot * 1.8;
            if (score < bestScore) {
                bestScore = score;
                best = target;
            }
        }

        return best;
    }

    private List<LivingEntity> collectFrontTargets(Player player, double range, double minDot) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity target)) {
                continue;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            if (target instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            Vector toTarget = target.getLocation().add(0, target.getHeight() * 0.45, 0).toVector()
                    .subtract(eye.toVector());
            double distance = toTarget.length();
            if (distance > range || distance < 0.001) {
                continue;
            }

            double dot = direction.dot(toTarget.normalize());
            if (dot < minDot) {
                continue;
            }

            targets.add(target);
        }

        return targets;
    }

    private void spawnRing(World world, Location center, double radius, int points, Particle.DustOptions dust) {
        if (world == null || center == null) {
            return;
        }

        int n = Math.max(12, points);
        for (int i = 0; i < n; i++) {
            double angle = (Math.PI * 2 * i) / n;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location point = center.clone().add(x, 0.0, z);
            world.spawnParticle(Particle.DUST, point, 1, 0.01, 0.01, 0.01, 0.0, dust);
        }
    }

    private double getMaxHealth(LivingEntity entity) {
        if (entity == null) {
            return 20.0;
        }

        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        if (max == null) {
            return 20.0;
        }
        return Math.max(1.0, max.getValue());
    }
}
