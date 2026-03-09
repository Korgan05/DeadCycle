package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

@SuppressWarnings("deprecation")
public class KitManager implements Listener {

    public enum Kit {
        FIGHTER,
        MINER,
        BUILDER,
        ARCHER,
        BERSERK,
        GRAVITATOR,
        DUELIST,
        CLONER,
        SUMMONER
    }

    private final DeadCyclePlugin plugin;
    private final Map<UUID, Kit> kits = new HashMap<>();

    private final NamespacedKey builderRepairKey;
    private final NamespacedKey builderUpgradeKey;
    private final NamespacedKey skillActivatorKey;

    // Отдельные ключи для каждого скилла
    private final NamespacedKey archerRainSkillKey;
    private final NamespacedKey gravityCrushSkillKey;
    private final NamespacedKey levitationStrikeSkillKey;
    private final NamespacedKey ritualCutSkillKey;
    private final NamespacedKey circleTranceSkillKey;
    private final NamespacedKey cloneSummonSkillKey;
    private final NamespacedKey cloneModeSkillKey;
    private final NamespacedKey summonerWolvesSkillKey;
    private final NamespacedKey summonerPhantomSkillKey;
    private final NamespacedKey summonerGolemSkillKey;
    private final NamespacedKey summonerVexSkillKey;
    private final NamespacedKey legacyDuelistBreachSkillKey;
    private final NamespacedKey legacyDuelistAegisSkillKey;

    public KitManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.builderRepairKey = new NamespacedKey(plugin, "builder_repair_tool");
        this.builderUpgradeKey = new NamespacedKey(plugin, "builder_wall_upgrade_tool");
        this.skillActivatorKey = new NamespacedKey(plugin, "skill_activator");

        // Ключи для защиты предметов скиллов
        this.archerRainSkillKey = new NamespacedKey(plugin, "skill_archer_rain");
        this.gravityCrushSkillKey = new NamespacedKey(plugin, "skill_gravity_crush");
        this.levitationStrikeSkillKey = new NamespacedKey(plugin, "skill_levitation_strike");
        this.ritualCutSkillKey = new NamespacedKey(plugin, "skill_ritual_cut");
        this.circleTranceSkillKey = new NamespacedKey(plugin, "skill_circle_trance");
        this.cloneSummonSkillKey = new NamespacedKey(plugin, "skill_clone_summon");
        this.cloneModeSkillKey = new NamespacedKey(plugin, "skill_clone_mode");
        this.summonerWolvesSkillKey = new NamespacedKey(plugin, "skill_summoner_wolves");
        this.summonerPhantomSkillKey = new NamespacedKey(plugin, "skill_summoner_phantom");
        this.summonerGolemSkillKey = new NamespacedKey(plugin, "skill_summoner_golem");
        this.summonerVexSkillKey = new NamespacedKey(plugin, "skill_summoner_vex");
        this.legacyDuelistBreachSkillKey = new NamespacedKey(plugin, "skill_duelist_breach");
        this.legacyDuelistAegisSkillKey = new NamespacedKey(plugin, "skill_duelist_aegis");
    }

    public Kit getKit(UUID id) {
        return kits.getOrDefault(id, Kit.FIGHTER);
    }

    public void setKit(UUID id, Kit kit) {
        kits.put(id, kit);
    }

    // ✅ удобная перегрузка чтобы GUI мог вызывать setKit(p, kit)
    public void setKit(Player p, Kit kit) {
        setKit(p.getUniqueId(), kit);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        try {
            Player p = e.getPlayer();

            // Если по правилам игрок ОБЯЗАН выбрать кит — не восстанавливаем прошлый.
            if (plugin.progress().isKitChoiceRequired(p.getUniqueId())) {
                setKit(p.getUniqueId(), Kit.FIGHTER);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!p.isOnline())
                        return;
                    if (p.getGameMode() == GameMode.SPECTATOR)
                        return;
                    plugin.kitMenu().open(p);
                }, 20L);
                return;
            }

            // Восстанавливаем выбранный кит и применяем эффекты (иначе после рестарта
            // игрок остаётся с предметами кита, но логически считается FIGHTER и без
            // бафов).
            Kit saved = plugin.progress().getSavedKit(p.getUniqueId());
            setKit(p.getUniqueId(), saved);
            plugin.progress().applyKitEffects(p);
            if (saved == Kit.SUMMONER && plugin.summonerKit() != null) {
                plugin.summonerKit().syncSkillItems(p);
            }
        } catch (Throwable ignored) {
        }
    }

    // ====== выдача китов ======

    public void giveKit(Player p, Kit kit) {
        // САХРАНЯЕМ скиллы и специальные предметы перед очисткой
        java.util.List<org.bukkit.inventory.ItemStack> savedItems = new java.util.ArrayList<>();
        for (org.bukkit.inventory.ItemStack item : p.getInventory().getContents()) {
            if (item == null)
                continue;
            // Проверяем - есть ли это скиллы? (autoRegen, autoDodge, regen)
            if (plugin.specialSkills() != null && plugin.specialSkills().isSpecialSkill(item)) {
                savedItems.add(item.clone());
            }
        }

        p.getInventory().clear();
        setKit(p.getUniqueId(), kit);

        // сохраняем выбор
        try {
            plugin.progress().saveKit(p.getUniqueId(), kit);
        } catch (Throwable ignored) {
        }

        switch (kit) {
            case FIGHTER -> giveFighter(p);
            case MINER -> giveMiner(p);
            case BUILDER -> giveBuilder(p);
            case ARCHER -> giveArcher(p);
            case BERSERK -> giveBerserk(p);
            case GRAVITATOR -> giveGravitator(p);
            case DUELIST -> giveDuelist(p);
            case CLONER -> giveCloner(p);
            case SUMMONER -> giveSummoner(p);
        }

        if (plugin.cloneKit() != null) {
            plugin.cloneKit().onKitAssigned(p, kit);
        }
        if (plugin.summonerKit() != null) {
            plugin.summonerKit().onKitAssigned(p, kit);
        }

        // ВОЗВРАЩАЕМ сохранённые скиллы
        for (org.bukkit.inventory.ItemStack skill : savedItems) {
            var leftovers = p.getInventory().addItem(skill);
            for (org.bukkit.inventory.ItemStack leftover : leftovers.values()) {
                p.getWorld().dropItem(p.getLocation(), leftover);
            }
        }

        // применяем эффекты/бафы кита сразу после выдачи
        try {
            plugin.progress().applyKitEffects(p);
        } catch (Throwable ignored) {
        }
    }

    private void giveFighter(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
    }

    private void giveBerserk(Player p) {
        // Стартовые предметы берсерка (пока минимально, дальше можно расширять)
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
    }

    private void giveMiner(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_PICKAXE));
        p.getInventory().addItem(new ItemStack(Material.TORCH, 32));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 12));
    }

    private void giveArcher(Player p) {
        p.getInventory().addItem(new ItemStack(Material.BOW));
        p.getInventory().addItem(new ItemStack(Material.ARROW, 32));
        p.getInventory().addItem(new ItemStack(Material.LEATHER_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 12));

        // ✅ Скилл активатор для лучника
        p.getInventory().addItem(createArcherRainItem());
    }

    private void giveGravitator(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.IRON_BOOTS));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 12));

        // ✅ Оба скилла гравитатора
        p.getInventory().addItem(createGravityCrushItem());
        p.getInventory().addItem(createLevitationStrikeItem());
    }

    private void giveBuilder(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_AXE));
        p.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 64));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 12));

        // ✅ наковальня -> ремонт
        p.getInventory().addItem(makeBuilderRepairItem());

        // ✅ smithing table -> апгрейд стен
        p.getInventory().addItem(makeBuilderWallUpgradeItem());
    }

    private void giveDuelist(Player p) {
        p.getInventory().addItem(new ItemStack(Material.NETHERITE_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.IRON_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));

        p.getInventory().addItem(createDuelistBreachItem());
        p.getInventory().addItem(createDuelistAegisItem());
    }

    private void giveCloner(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 14));

        p.getInventory().addItem(createCloneSummonItem());
        p.getInventory().addItem(createCloneModeItem());
    }

    private void giveSummoner(Player p) {
        p.getInventory().addItem(new ItemStack(Material.STONE_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 14));

        p.getInventory().addItem(createSummonerWolvesItem());
    }

    // ====== builder tools ======

    private ItemStack makeBuilderRepairItem() {
        ItemStack it = new ItemStack(Material.ANVIL);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(ChatColor.GREEN + "Ремонт базы");
            im.setLore(Arrays.asList(
                    ChatColor.GRAY + "ПКМ — открыть меню ремонта",
                    ChatColor.DARK_GRAY + "Только для билдера"));
            im.getPersistentDataContainer().set(builderRepairKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack makeBuilderWallUpgradeItem() {
        ItemStack it = new ItemStack(Material.SMITHING_TABLE);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(ChatColor.AQUA + "Прокачка стен");
            im.setLore(Arrays.asList(
                    ChatColor.GRAY + "ПКМ — открыть меню прокачки",
                    ChatColor.GRAY + "Потом ПКМ по блоку на базе",
                    ChatColor.DARK_GRAY + "Только для билдера"));
            im.getPersistentDataContainer().set(builderUpgradeKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public boolean isBuilderRepairTool(ItemStack it) {
        if (it == null || it.getType() != Material.ANVIL)
            return false;
        if (!it.hasItemMeta())
            return false;
        Byte v = it.getItemMeta().getPersistentDataContainer().get(builderRepairKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public boolean isBuilderWallUpgradeTool(ItemStack it) {
        if (it == null || it.getType() != Material.SMITHING_TABLE)
            return false;
        if (!it.hasItemMeta())
            return false;
        Byte v = it.getItemMeta().getPersistentDataContainer().get(builderUpgradeKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    // ====== skill activator ======

    private ItemStack makeSkillActivatorItem(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            im.setLore(lore);

            // ✅ Добавляем эффект "зачерованности" (enchantment glint)
            // без реальной чары - только визуальный эффект
            im.setEnchantmentGlintOverride(true);

            im.getPersistentDataContainer().set(skillActivatorKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public boolean isSkillActivator(ItemStack it) {
        if (it == null)
            return false;
        if (!it.hasItemMeta())
            return false;
        Byte v = it.getItemMeta().getPersistentDataContainer().get(skillActivatorKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    /**
     * Получить ID скилла по предмету.
     * Возвращает null если это не скилл-предмет.
     */
    public String getSkillIdFromItem(ItemStack it) {
        if (it == null || !it.hasItemMeta())
            return null;

        ItemMeta meta = it.getItemMeta();

        Byte archerRain = meta.getPersistentDataContainer().get(archerRainSkillKey, PersistentDataType.BYTE);
        if (archerRain != null && archerRain == (byte) 1)
            return "archer_rain";

        Byte gravityCrush = meta.getPersistentDataContainer().get(gravityCrushSkillKey, PersistentDataType.BYTE);
        if (gravityCrush != null && gravityCrush == (byte) 1)
            return "gravity_crush";

        Byte levitationStrike = meta.getPersistentDataContainer().get(levitationStrikeSkillKey,
                PersistentDataType.BYTE);
        if (levitationStrike != null && levitationStrike == (byte) 1)
            return "levitation_strike";

        Byte ritualCut = meta.getPersistentDataContainer().get(ritualCutSkillKey,
                PersistentDataType.BYTE);
        Byte legacyDuelistBreach = meta.getPersistentDataContainer().get(legacyDuelistBreachSkillKey,
                PersistentDataType.BYTE);
        if ((ritualCut != null && ritualCut == (byte) 1)
                || (legacyDuelistBreach != null && legacyDuelistBreach == (byte) 1))
            return "ritual_cut";

        Byte circleTrance = meta.getPersistentDataContainer().get(circleTranceSkillKey,
                PersistentDataType.BYTE);
        Byte legacyDuelistAegis = meta.getPersistentDataContainer().get(legacyDuelistAegisSkillKey,
                PersistentDataType.BYTE);
        if ((circleTrance != null && circleTrance == (byte) 1)
                || (legacyDuelistAegis != null && legacyDuelistAegis == (byte) 1))
            return "circle_trance";

        Byte cloneSummon = meta.getPersistentDataContainer().get(cloneSummonSkillKey,
                PersistentDataType.BYTE);
        if (cloneSummon != null && cloneSummon == (byte) 1)
            return "clone_summon";

        Byte cloneMode = meta.getPersistentDataContainer().get(cloneModeSkillKey,
                PersistentDataType.BYTE);
        if (cloneMode != null && cloneMode == (byte) 1)
            return "clone_mode";

        Byte summonerWolves = meta.getPersistentDataContainer().get(summonerWolvesSkillKey,
                PersistentDataType.BYTE);
        if (summonerWolves != null && summonerWolves == (byte) 1)
            return "summoner_wolves";

        Byte summonerPhantom = meta.getPersistentDataContainer().get(summonerPhantomSkillKey,
                PersistentDataType.BYTE);
        if (summonerPhantom != null && summonerPhantom == (byte) 1)
            return "summoner_phantom";

        Byte summonerGolem = meta.getPersistentDataContainer().get(summonerGolemSkillKey,
                PersistentDataType.BYTE);
        if (summonerGolem != null && summonerGolem == (byte) 1)
            return "summoner_golem";

        Byte summonerVex = meta.getPersistentDataContainer().get(summonerVexSkillKey,
                PersistentDataType.BYTE);
        if (summonerVex != null && summonerVex == (byte) 1)
            return "summoner_vex";

        return null;
    }

    /**
     * Создать предмет для Archer Rain скилла.
     */
    public ItemStack createArcherRainItem() {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName("§6Стрельный разряд");
            im.setLore(Arrays.asList("§7Зажми ПКМ для активации", "§7Стрелы упадут куда ты смотришь"));
            im.setEnchantmentGlintOverride(true);

            im.getPersistentDataContainer().set(archerRainSkillKey, PersistentDataType.BYTE, (byte) 1);
            im.getPersistentDataContainer().set(skillActivatorKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    /**
     * Создать предмет для Gravity Crush скилла.
     */
    public ItemStack createGravityCrushItem() {
        ItemStack it = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName("§5Гравитационный пресс");
            im.setLore(Arrays.asList("§7Зажми ПКМ для активации", "§7Прижимает зомби к земле"));
            im.setEnchantmentGlintOverride(true);

            im.getPersistentDataContainer().set(gravityCrushSkillKey, PersistentDataType.BYTE, (byte) 1);
            im.getPersistentDataContainer().set(skillActivatorKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    /**
     * Создать предмет для Levitation Strike скилла.
     */
    public ItemStack createLevitationStrikeItem() {
        ItemStack it = new ItemStack(Material.CHORUS_FRUIT);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName("§dАнтигравитация");
            im.setLore(Arrays.asList("§7Зажми ПКМ для активации", "§7Отправляет зомби вверх"));
            im.setEnchantmentGlintOverride(true);

            im.getPersistentDataContainer().set(levitationStrikeSkillKey, PersistentDataType.BYTE, (byte) 1);
            im.getPersistentDataContainer().set(skillActivatorKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    /**
     * Создать предмет для Duelist Breach скилла.
     */
    public ItemStack createDuelistBreachItem() {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName("§5Ритуальный Разрез");
            im.setLore(Arrays.asList("§7ПКМ: сильный разрез по главной цели", "§7лучше в честной 1v1 схватке"));
            im.setEnchantmentGlintOverride(true);

            im.getPersistentDataContainer().set(ritualCutSkillKey, PersistentDataType.BYTE, (byte) 1);
            im.getPersistentDataContainer().set(skillActivatorKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    /**
     * Создать предмет для Duelist Aegis скилла.
     */
    public ItemStack createDuelistAegisItem() {
        ItemStack it = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName("§dТранс Круга");
            im.setLore(Arrays.asList("§7ПКМ: ритуальная стойка", "§7усиливает тебя против фокус-цели"));
            im.setEnchantmentGlintOverride(true);

            im.getPersistentDataContainer().set(circleTranceSkillKey, PersistentDataType.BYTE, (byte) 1);
            im.getPersistentDataContainer().set(skillActivatorKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createCloneSummonItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.ENDER_EYE,
                "§bПризыв Клона",
                Arrays.asList(
                        "§7ПКМ: призвать клона-разбойника",
                        "§7Стоимость: §b50 маны",
                        "§7Лимит клонов растёт с уровнем"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(cloneSummonSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createCloneModeItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.COMPASS,
                "§dРежим Клонов",
                Arrays.asList(
                        "§7ПКМ: переключить режим ИИ",
                        "§7Защита базы → Защита меня → Атака"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(cloneModeSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createSummonerWolvesItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.BONE,
                "§aПризыв Волков",
                Arrays.asList(
                        "§7ПКМ: призвать боевых волков",
                        "§7Лимит и количество растут с уровнем"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(summonerWolvesSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createSummonerPhantomItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.PHANTOM_MEMBRANE,
                "§5Призыв Фантома",
                Arrays.asList(
                        "§7ПКМ: призвать фантома",
                        "§7Открывается с 3 уровня"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(summonerPhantomSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createSummonerGolemItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.IRON_BLOCK,
                "§fПризыв Голема",
                Arrays.asList(
                        "§7ПКМ: призвать железного голема",
                        "§7Открывается с 4 уровня"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(summonerGolemSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createSummonerVexItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.ECHO_SHARD,
                "§dПризыв Духов",
                Arrays.asList(
                        "§7ПКМ: призвать духов-вексов",
                        "§7Открывается с 7 уровня"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(summonerVexSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    /**
     * Проверить, является ли предмет защищённым предметом скилла.
     */
    public boolean isProtectedSkillItem(ItemStack it) {
        return getSkillIdFromItem(it) != null;
    }
}
