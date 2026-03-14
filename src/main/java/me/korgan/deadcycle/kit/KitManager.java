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
        SUMMONER,
        PING,
        HARPOONER,
        CYBORG,
        MEDIC,
        EXORCIST
    }

    private final DeadCyclePlugin plugin;
    private final Map<UUID, Kit> kits = new HashMap<>();

    private final NamespacedKey builderRepairKey;
    private final NamespacedKey builderUpgradeKey;
    private final NamespacedKey skillActivatorKey;

    // Отдельные ключи для каждого скилла
    private final NamespacedKey archerRainSkillKey;
    private final NamespacedKey archerMarkSkillKey;
    private final NamespacedKey archerTrapArrowSkillKey;
    private final NamespacedKey archerRicochetSkillKey;
    private final NamespacedKey berserkBloodDashSkillKey;
    private final NamespacedKey berserkExecutionSkillKey;
    private final NamespacedKey gravityCrushSkillKey;
    private final NamespacedKey levitationStrikeSkillKey;
    private final NamespacedKey ritualCutSkillKey;
    private final NamespacedKey circleTranceSkillKey;
    private final NamespacedKey duelistCounterStanceSkillKey;
    private final NamespacedKey duelistFeintSkillKey;
    private final NamespacedKey cloneSummonSkillKey;
    private final NamespacedKey cloneModeSkillKey;
    private final NamespacedKey summonerWolvesSkillKey;
    private final NamespacedKey summonerPhantomSkillKey;
    private final NamespacedKey summonerGolemSkillKey;
    private final NamespacedKey summonerVexSkillKey;
    private final NamespacedKey summonerFocusSkillKey;
    private final NamespacedKey summonerRegroupSkillKey;
    private final NamespacedKey summonerSacrificeSkillKey;
    private final NamespacedKey pingBlinkSkillKey;
    private final NamespacedKey pingPulseSkillKey;
    private final NamespacedKey pingJitterSkillKey;
    private final NamespacedKey harpoonAnchorSkillKey;
    private final NamespacedKey harpoonPullSkillKey;
    private final NamespacedKey cyborgSlamSkillKey;
    private final NamespacedKey medicWaveSkillKey;
    private final NamespacedKey exorcistPurgeSkillKey;
    private final NamespacedKey legacyDuelistBreachSkillKey;
    private final NamespacedKey legacyDuelistAegisSkillKey;

    public KitManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.builderRepairKey = new NamespacedKey(plugin, "builder_repair_tool");
        this.builderUpgradeKey = new NamespacedKey(plugin, "builder_wall_upgrade_tool");
        this.skillActivatorKey = new NamespacedKey(plugin, "skill_activator");

        // Ключи для защиты предметов скиллов
        this.archerRainSkillKey = new NamespacedKey(plugin, "skill_archer_rain");
        this.archerMarkSkillKey = new NamespacedKey(plugin, "skill_archer_mark");
        this.archerTrapArrowSkillKey = new NamespacedKey(plugin, "skill_archer_trap_arrow");
        this.archerRicochetSkillKey = new NamespacedKey(plugin, "skill_archer_ricochet");
        this.berserkBloodDashSkillKey = new NamespacedKey(plugin, "skill_berserk_blood_dash");
        this.berserkExecutionSkillKey = new NamespacedKey(plugin, "skill_berserk_execution");
        this.gravityCrushSkillKey = new NamespacedKey(plugin, "skill_gravity_crush");
        this.levitationStrikeSkillKey = new NamespacedKey(plugin, "skill_levitation_strike");
        this.ritualCutSkillKey = new NamespacedKey(plugin, "skill_ritual_cut");
        this.circleTranceSkillKey = new NamespacedKey(plugin, "skill_circle_trance");
        this.duelistCounterStanceSkillKey = new NamespacedKey(plugin, "skill_duelist_counter_stance");
        this.duelistFeintSkillKey = new NamespacedKey(plugin, "skill_duelist_feint");
        this.cloneSummonSkillKey = new NamespacedKey(plugin, "skill_clone_summon");
        this.cloneModeSkillKey = new NamespacedKey(plugin, "skill_clone_mode");
        this.summonerWolvesSkillKey = new NamespacedKey(plugin, "skill_summoner_wolves");
        this.summonerPhantomSkillKey = new NamespacedKey(plugin, "skill_summoner_phantom");
        this.summonerGolemSkillKey = new NamespacedKey(plugin, "skill_summoner_golem");
        this.summonerVexSkillKey = new NamespacedKey(plugin, "skill_summoner_vex");
        this.summonerFocusSkillKey = new NamespacedKey(plugin, "skill_summoner_focus");
        this.summonerRegroupSkillKey = new NamespacedKey(plugin, "skill_summoner_regroup");
        this.summonerSacrificeSkillKey = new NamespacedKey(plugin, "skill_summoner_sacrifice");
        this.pingBlinkSkillKey = new NamespacedKey(plugin, "skill_ping_blink");
        this.pingPulseSkillKey = new NamespacedKey(plugin, "skill_ping_pulse");
        this.pingJitterSkillKey = new NamespacedKey(plugin, "skill_ping_jitter");
        this.harpoonAnchorSkillKey = new NamespacedKey(plugin, "skill_harpoon_anchor");
        this.harpoonPullSkillKey = new NamespacedKey(plugin, "skill_harpoon_pull");
        this.cyborgSlamSkillKey = new NamespacedKey(plugin, "skill_cyborg_slam");
        this.medicWaveSkillKey = new NamespacedKey(plugin, "skill_medic_wave");
        this.exorcistPurgeSkillKey = new NamespacedKey(plugin, "skill_exorcist_purge");
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
            syncSkillItemsForCurrentKit(p);
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
            case PING -> givePing(p);
            case HARPOONER -> giveHarpooner(p);
            case CYBORG -> giveCyborg(p);
            case MEDIC -> giveMedic(p);
            case EXORCIST -> giveExorcist(p);
        }

        if (plugin.cloneKit() != null) {
            plugin.cloneKit().onKitAssigned(p, kit);
        }
        if (plugin.summonerKit() != null) {
            plugin.summonerKit().onKitAssigned(p, kit);
        }
        if (plugin.harpoonerKit() != null) {
            plugin.harpoonerKit().onKitAssigned(p, kit);
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
            syncSkillItemsForCurrentKit(p);
        } catch (Throwable ignored) {
        }
    }

    public void syncSkillItemsForCurrentKit(Player p) {
        if (p == null || !p.isOnline())
            return;

        Kit kit = getKit(p.getUniqueId());
        int level = plugin.progress().getKitLevel(p.getUniqueId(), kit);

        switch (kit) {
            case ARCHER -> {
                ensureSkillItem(p, "archer_rain", createArcherRainItem(),
                        isSkillUnlockedForLevel(kit, "archer_rain", level));
                ensureSkillItem(p, "archer_mark", createArcherMarkItem(),
                        isSkillUnlockedForLevel(kit, "archer_mark", level));
                ensureSkillItem(p, "archer_trap_arrow", createArcherTrapArrowItem(),
                        isSkillUnlockedForLevel(kit, "archer_trap_arrow", level));
                ensureSkillItem(p, "archer_ricochet", createArcherRicochetItem(),
                        isSkillUnlockedForLevel(kit, "archer_ricochet", level));
            }
            case BERSERK -> {
                ensureSkillItem(p, "berserk_blood_dash", createBerserkBloodDashItem(),
                        isSkillUnlockedForLevel(kit, "berserk_blood_dash", level));
                ensureSkillItem(p, "berserk_execution", createBerserkExecutionItem(),
                        isSkillUnlockedForLevel(kit, "berserk_execution", level));
            }
            case GRAVITATOR -> {
                ensureSkillItem(p, "gravity_crush", createGravityCrushItem(),
                        isSkillUnlockedForLevel(kit, "gravity_crush", level));
                ensureSkillItem(p, "levitation_strike", createLevitationStrikeItem(),
                        isSkillUnlockedForLevel(kit, "levitation_strike", level));
            }
            case DUELIST -> {
                ensureSkillItem(p, "ritual_cut", createDuelistBreachItem(),
                        isSkillUnlockedForLevel(kit, "ritual_cut", level));
                ensureSkillItem(p, "circle_trance", createDuelistAegisItem(),
                        isSkillUnlockedForLevel(kit, "circle_trance", level));
                ensureSkillItem(p, "duelist_counter_stance", createDuelistCounterStanceItem(),
                        isSkillUnlockedForLevel(kit, "duelist_counter_stance", level));
                ensureSkillItem(p, "duelist_feint", createDuelistFeintItem(),
                        isSkillUnlockedForLevel(kit, "duelist_feint", level));
            }
            case CLONER -> {
                ensureSkillItem(p, "clone_summon", createCloneSummonItem(),
                        isSkillUnlockedForLevel(kit, "clone_summon", level));
                ensureSkillItem(p, "clone_mode", createCloneModeItem(),
                        isSkillUnlockedForLevel(kit, "clone_mode", level));
            }
            case SUMMONER -> {
                if (plugin.summonerKit() != null) {
                    plugin.summonerKit().syncSkillItems(p);
                }
            }
            case PING -> {
                ensureSkillItem(p, "ping_blink", createPingBlinkItem(),
                        isSkillUnlockedForLevel(kit, "ping_blink", level));
                ensureSkillItem(p, "ping_pulse", createPingPulseItem(),
                        isSkillUnlockedForLevel(kit, "ping_pulse", level));
                ensureSkillItem(p, "ping_jitter", createPingJitterItem(),
                        isSkillUnlockedForLevel(kit, "ping_jitter", level));
            }
            case HARPOONER -> {
                ensureSkillItem(p, "harpoon_anchor", createHarpoonAnchorItem(),
                        isSkillUnlockedForLevel(kit, "harpoon_anchor", level));
                ensureSkillItem(p, "harpoon_pull", createHarpoonPullItem(),
                        isSkillUnlockedForLevel(kit, "harpoon_pull", level));
            }
            case CYBORG -> {
                ensureSkillItem(p, "cyborg_slam", createCyborgSlamItem(),
                        isSkillUnlockedForLevel(kit, "cyborg_slam", level));
            }
            case MEDIC -> {
                ensureSkillItem(p, "medic_wave", createMedicWaveItem(),
                        isSkillUnlockedForLevel(kit, "medic_wave", level));
            }
            case EXORCIST -> {
                ensureSkillItem(p, "exorcist_purge", createExorcistPurgeItem(),
                        isSkillUnlockedForLevel(kit, "exorcist_purge", level));
            }
            case MINER, BUILDER, FIGHTER -> {
                // У этих китов нет активаторов SkillManager.
            }
        }

        if (plugin.workbenchMenu() != null) {
            plugin.workbenchMenu().ensureWorkbenchItem(p);
        }
    }

    public boolean isSkillUnlockedForLevel(Kit kit, String skillId, int level) {
        return level >= getSkillUnlockLevel(kit, skillId);
    }

    public int getSkillUnlockLevel(Kit kit, String skillId) {
        if (kit == null || skillId == null || skillId.isBlank())
            return Integer.MAX_VALUE;

        return switch (kit) {
            case ARCHER -> switch (skillId) {
                case "archer_rain" -> 1;
                case "archer_mark" -> 3;
                case "archer_trap_arrow" -> 5;
                case "archer_ricochet" -> 7;
                default -> Integer.MAX_VALUE;
            };
            case BERSERK -> switch (skillId) {
                case "berserk_blood_dash" -> 4;
                case "berserk_execution" -> 7;
                default -> Integer.MAX_VALUE;
            };
            case GRAVITATOR -> switch (skillId) {
                case "gravity_crush" -> 1;
                case "levitation_strike" -> 5;
                default -> Integer.MAX_VALUE;
            };
            case DUELIST -> switch (skillId) {
                case "ritual_cut", "circle_trance" -> 1;
                case "duelist_counter_stance" -> 5;
                case "duelist_feint" -> 7;
                default -> Integer.MAX_VALUE;
            };
            case CLONER -> switch (skillId) {
                case "clone_summon" -> 1;
                case "clone_mode" -> 4;
                default -> Integer.MAX_VALUE;
            };
            case SUMMONER -> switch (skillId) {
                case "summoner_wolves" -> 1;
                case "summoner_phantom" -> 3;
                case "summoner_golem" -> 4;
                case "summoner_focus" -> 6;
                case "summoner_vex" -> 7;
                case "summoner_regroup" -> 8;
                case "summoner_sacrifice" -> 10;
                default -> Integer.MAX_VALUE;
            };
            case PING -> switch (skillId) {
                case "ping_blink" -> 1;
                case "ping_pulse" -> 4;
                case "ping_jitter" -> 6;
                default -> Integer.MAX_VALUE;
            };
            case HARPOONER -> switch (skillId) {
                case "harpoon_anchor" -> 1;
                case "harpoon_pull" -> 4;
                default -> Integer.MAX_VALUE;
            };
            case CYBORG -> switch (skillId) {
                case "cyborg_slam" -> 1;
                default -> Integer.MAX_VALUE;
            };
            case MEDIC -> switch (skillId) {
                case "medic_wave" -> 1;
                default -> Integer.MAX_VALUE;
            };
            case EXORCIST -> switch (skillId) {
                case "exorcist_purge" -> 1;
                default -> Integer.MAX_VALUE;
            };
            case FIGHTER, MINER, BUILDER -> Integer.MAX_VALUE;
        };
    }

    public List<String> getKitSkillOrder(Kit kit) {
        if (kit == null)
            return Collections.emptyList();

        return switch (kit) {
            case ARCHER -> List.of("archer_rain", "archer_mark", "archer_trap_arrow", "archer_ricochet");
            case BERSERK -> List.of("berserk_blood_dash", "berserk_execution");
            case GRAVITATOR -> List.of("gravity_crush", "levitation_strike");
            case DUELIST -> List.of("ritual_cut", "circle_trance", "duelist_counter_stance", "duelist_feint");
            case CLONER -> List.of("clone_summon", "clone_mode");
            case SUMMONER -> List.of(
                    "summoner_wolves",
                    "summoner_phantom",
                    "summoner_golem",
                    "summoner_focus",
                    "summoner_vex",
                    "summoner_regroup",
                    "summoner_sacrifice");
            case PING -> List.of("ping_blink", "ping_pulse", "ping_jitter");
            case HARPOONER -> List.of("harpoon_anchor", "harpoon_pull");
            case CYBORG -> List.of("cyborg_slam");
            case MEDIC -> List.of("medic_wave");
            case EXORCIST -> List.of("exorcist_purge");
            case FIGHTER, MINER, BUILDER -> Collections.emptyList();
        };
    }

    public String getSkillDisplayName(String skillId) {
        if (skillId == null || skillId.isBlank())
            return "Неизвестный навык";

        return switch (skillId) {
            case "archer_rain" -> "Дождь стрел";
            case "archer_mark" -> "Клеймо лучника";
            case "archer_trap_arrow" -> "Стрела-ловушка";
            case "archer_ricochet" -> "Рикошет";
            case "berserk_blood_dash" -> "Кровавый рывок";
            case "berserk_execution" -> "Казнь";
            case "gravity_crush" -> "Грави-удар";
            case "levitation_strike" -> "Левитационный удар";
            case "ritual_cut" -> "Ритуальный разрез";
            case "circle_trance" -> "Круг транса";
            case "duelist_counter_stance" -> "Контрстойка";
            case "duelist_feint" -> "Финт";
            case "clone_summon" -> "Призыв клона";
            case "clone_mode" -> "Режим клонов";
            case "summoner_wolves" -> "Стая волков";
            case "summoner_phantom" -> "Фантом";
            case "summoner_golem" -> "Железный голем";
            case "summoner_focus" -> "Фокус призывателя";
            case "summoner_vex" -> "Рой vex'ов";
            case "summoner_regroup" -> "Перестройка";
            case "summoner_sacrifice" -> "Жертвенный обряд";
            case "ping_blink" -> "Блинк";
            case "ping_pulse" -> "Пульс";
            case "ping_jitter" -> "Джиттер";
            case "harpoon_anchor" -> "Гарпун";
            case "harpoon_pull" -> "Подтяжка";
            case "cyborg_slam" -> "Реактивный таран";
            case "medic_wave" -> "Полевая терапия";
            case "exorcist_purge" -> "Священный изгиб";
            default -> skillId;
        };
    }

    public String getKitDisplayName(Kit kit) {
        if (kit == null)
            return "Боец";

        return switch (kit) {
            case FIGHTER -> "Боец";
            case MINER -> "Шахтёр";
            case BUILDER -> "Билдер";
            case ARCHER -> "Лучник";
            case BERSERK -> "Берсерк";
            case GRAVITATOR -> "Гравитатор";
            case DUELIST -> "Ритуалист";
            case CLONER -> "Клонер";
            case SUMMONER -> "Призыватель";
            case PING -> "Пинг";
            case HARPOONER -> "Гарпунер";
            case CYBORG -> "Киборг";
            case MEDIC -> "Медик";
            case EXORCIST -> "Экзорцист";
        };
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
            String id = getSkillIdFromItem(item);
            if (skillId.equals(id))
                return true;
        }

        ItemStack off = owner.getInventory().getItemInOffHand();
        if (off != null) {
            String id = getSkillIdFromItem(off);
            return skillId.equals(id);
        }

        return false;
    }

    private void removeSkillItem(Player owner, String skillId) {
        for (int i = 0; i < owner.getInventory().getSize(); i++) {
            ItemStack item = owner.getInventory().getItem(i);
            if (item == null)
                continue;
            String id = getSkillIdFromItem(item);
            if (skillId.equals(id)) {
                owner.getInventory().setItem(i, null);
            }
        }

        ItemStack off = owner.getInventory().getItemInOffHand();
        if (off != null) {
            String offId = getSkillIdFromItem(off);
            if (skillId.equals(offId)) {
                owner.getInventory().setItemInOffHand(null);
            }
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

        p.getInventory().addItem(createBerserkBloodDashItem());
        p.getInventory().addItem(createBerserkExecutionItem());
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
        p.getInventory().addItem(createArcherMarkItem());
        p.getInventory().addItem(createArcherTrapArrowItem());
        p.getInventory().addItem(createArcherRicochetItem());
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
        p.getInventory().addItem(createDuelistCounterStanceItem());
        p.getInventory().addItem(createDuelistFeintItem());
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
        p.getInventory().addItem(createSummonerFocusItem());
        p.getInventory().addItem(createSummonerRegroupItem());
        p.getInventory().addItem(createSummonerSacrificeItem());
    }

    private void givePing(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 14));

        p.getInventory().addItem(createPingBlinkItem());
        p.getInventory().addItem(createPingPulseItem());
        p.getInventory().addItem(createPingJitterItem());
    }

    private void giveHarpooner(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.IRON_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 14));

        p.getInventory().addItem(createHarpoonAnchorItem());
        p.getInventory().addItem(createHarpoonPullItem());
    }

    private void giveCyborg(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.IRON_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.IRON_BOOTS));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 14));

        p.getInventory().addItem(createCyborgSlamItem());
    }

    private void giveMedic(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));

        p.getInventory().addItem(createMedicWaveItem());
    }

    private void giveExorcist(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.IRON_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 15));

        p.getInventory().addItem(createExorcistPurgeItem());
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
            List<String> finalLore = new ArrayList<>();
            if (lore != null)
                finalLore.addAll(lore);
            finalLore.add("§8");
            finalLore.add("§8Использование: предмет в руке + ПКМ");
            finalLore.add("§8Часть навыков открывается по уровню кита");
            im.setLore(finalLore);

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

        Byte archerMark = meta.getPersistentDataContainer().get(archerMarkSkillKey, PersistentDataType.BYTE);
        if (archerMark != null && archerMark == (byte) 1)
            return "archer_mark";

        Byte archerTrapArrow = meta.getPersistentDataContainer().get(archerTrapArrowSkillKey,
                PersistentDataType.BYTE);
        if (archerTrapArrow != null && archerTrapArrow == (byte) 1)
            return "archer_trap_arrow";

        Byte archerRicochet = meta.getPersistentDataContainer().get(archerRicochetSkillKey,
                PersistentDataType.BYTE);
        if (archerRicochet != null && archerRicochet == (byte) 1)
            return "archer_ricochet";

        Byte berserkDash = meta.getPersistentDataContainer().get(berserkBloodDashSkillKey,
                PersistentDataType.BYTE);
        if (berserkDash != null && berserkDash == (byte) 1)
            return "berserk_blood_dash";

        Byte berserkExecution = meta.getPersistentDataContainer().get(berserkExecutionSkillKey,
                PersistentDataType.BYTE);
        if (berserkExecution != null && berserkExecution == (byte) 1)
            return "berserk_execution";

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

        Byte counterStance = meta.getPersistentDataContainer().get(duelistCounterStanceSkillKey,
                PersistentDataType.BYTE);
        if (counterStance != null && counterStance == (byte) 1)
            return "duelist_counter_stance";

        Byte duelistFeint = meta.getPersistentDataContainer().get(duelistFeintSkillKey,
                PersistentDataType.BYTE);
        if (duelistFeint != null && duelistFeint == (byte) 1)
            return "duelist_feint";

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

        Byte summonerFocus = meta.getPersistentDataContainer().get(summonerFocusSkillKey,
                PersistentDataType.BYTE);
        if (summonerFocus != null && summonerFocus == (byte) 1)
            return "summoner_focus";

        Byte summonerRegroup = meta.getPersistentDataContainer().get(summonerRegroupSkillKey,
                PersistentDataType.BYTE);
        if (summonerRegroup != null && summonerRegroup == (byte) 1)
            return "summoner_regroup";

        Byte summonerSacrifice = meta.getPersistentDataContainer().get(summonerSacrificeSkillKey,
                PersistentDataType.BYTE);
        if (summonerSacrifice != null && summonerSacrifice == (byte) 1)
            return "summoner_sacrifice";

        Byte pingBlink = meta.getPersistentDataContainer().get(pingBlinkSkillKey,
                PersistentDataType.BYTE);
        if (pingBlink != null && pingBlink == (byte) 1)
            return "ping_blink";

        Byte pingPulse = meta.getPersistentDataContainer().get(pingPulseSkillKey,
                PersistentDataType.BYTE);
        if (pingPulse != null && pingPulse == (byte) 1)
            return "ping_pulse";

        Byte pingJitter = meta.getPersistentDataContainer().get(pingJitterSkillKey,
                PersistentDataType.BYTE);
        if (pingJitter != null && pingJitter == (byte) 1)
            return "ping_jitter";

        Byte harpoonAnchor = meta.getPersistentDataContainer().get(harpoonAnchorSkillKey,
                PersistentDataType.BYTE);
        if (harpoonAnchor != null && harpoonAnchor == (byte) 1)
            return "harpoon_anchor";

        Byte harpoonPull = meta.getPersistentDataContainer().get(harpoonPullSkillKey,
                PersistentDataType.BYTE);
        if (harpoonPull != null && harpoonPull == (byte) 1)
            return "harpoon_pull";

        Byte cyborgSlam = meta.getPersistentDataContainer().get(cyborgSlamSkillKey,
                PersistentDataType.BYTE);
        if (cyborgSlam != null && cyborgSlam == (byte) 1)
            return "cyborg_slam";

        Byte medicWave = meta.getPersistentDataContainer().get(medicWaveSkillKey,
                PersistentDataType.BYTE);
        if (medicWave != null && medicWave == (byte) 1)
            return "medic_wave";

        Byte exorcistPurge = meta.getPersistentDataContainer().get(exorcistPurgeSkillKey,
                PersistentDataType.BYTE);
        if (exorcistPurge != null && exorcistPurge == (byte) 1)
            return "exorcist_purge";

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

    public ItemStack createArcherMarkItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.SPECTRAL_ARROW,
                "§eМетка охотника",
                Arrays.asList(
                        "§7ПКМ: отметить цель перед собой",
                        "§7Следующий твой удар сильнее"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(archerMarkSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createArcherTrapArrowItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.TIPPED_ARROW,
                "§6Капкан-стрела",
                Arrays.asList(
                        "§7ПКМ: зарядить капкан-стрелу",
                        "§7Попадание ставит зону замедления"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(archerTrapArrowSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createArcherRicochetItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.FIREWORK_STAR,
                "§eРикошет",
                Arrays.asList(
                        "§7ПКМ: зарядить рикошет",
                        "§7Следующая стрела прыгает в 2-ю цель"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(archerRicochetSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createBerserkBloodDashItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.RABBIT_FOOT,
                "§4Кровавый рывок",
                Arrays.asList(
                        "§7ПКМ: рывок с уроном по линии",
                        "§7Попадание по 2+ целям ускоряет рёв"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(berserkBloodDashSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createBerserkExecutionItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.NETHERITE_AXE,
                "§4Казнь",
                Arrays.asList(
                        "§7ПКМ: рывок в цель",
                        "§7По раненым целям наносит больше"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(berserkExecutionSkillKey, PersistentDataType.BYTE, (byte) 1);
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

    public ItemStack createDuelistCounterStanceItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.SHIELD,
                "§dКонтрстойка",
                Arrays.asList(
                        "§7ПКМ: окно парирования 0.8с",
                        "§7Срезает урон и ослабляет атакующего"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(duelistCounterStanceSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createDuelistFeintItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.FEATHER,
                "§dФинт",
                Arrays.asList(
                        "§7ПКМ: быстрый шаг в сторону",
                        "§7Следующий удар усиливается"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(duelistFeintSkillKey, PersistentDataType.BYTE, (byte) 1);
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

    public ItemStack createSummonerFocusItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.RECOVERY_COMPASS,
                "§6Фокус-команда",
                Arrays.asList(
                        "§7ПКМ: все призывы фокусят цель",
                        "§7Ускоряет добивание приоритетного врага"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(summonerFocusSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createSummonerRegroupItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.AMETHYST_CLUSTER,
                "§bПерегруппировка",
                Arrays.asList(
                        "§7ПКМ: призывы стягиваются к тебе",
                        "§7И получают защитный щит"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(summonerRegroupSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createSummonerSacrificeItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.REDSTONE,
                "§cЖертвенный импульс",
                Arrays.asList(
                        "§7ПКМ: жертвует одним призывом",
                        "§7Остальные получают яростный баф"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(summonerSacrificeSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createPingBlinkItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.ENDER_PEARL,
                "§bПинг-Рывок",
                Arrays.asList(
                        "§7ПКМ: короткий рывок вперед",
                        "§7Даёт ускорение после телепорта"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(pingBlinkSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createPingPulseItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.LIGHTNING_ROD,
                "§9Пинг-Импульс",
                Arrays.asList(
                        "§7ПКМ: ударная волна вокруг тебя",
                        "§7Отталкивает и ранит ближайших врагов"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(pingPulseSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createPingJitterItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.CLOCK,
                "§9Джиттер",
                Arrays.asList(
                        "§7ПКМ: запускает ложные следы",
                        "§7Враги периодически теряют фокус"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(pingJitterSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createHarpoonAnchorItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.TRIDENT,
                "§3Якорный Гарпун",
                Arrays.asList(
                        "§7ПКМ: заякорить цель перед собой",
                        "§7Наказывает резкие рывки и телепорты"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(harpoonAnchorSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createHarpoonPullItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.LEAD,
                "§bПодтяжка",
                Arrays.asList(
                        "§7ПКМ: подтянуть цель или подтянуться к ней",
                        "§7Требуется активная цепь от гарпуна"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(harpoonPullSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createCyborgSlamItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.BREEZE_ROD,
                "§6Реактивный таран",
                Arrays.asList(
                        "§7ПКМ: взлететь и обрушиться на врагов",
                        "§7В полете оставляет огненный шлейф"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(cyborgSlamSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createMedicWaveItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.GOLDEN_APPLE,
                "§aПолевая терапия",
                Arrays.asList(
                        "§7ПКМ: исцелить себя и союзников рядом",
                        "§7Снимает основные негативные эффекты"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(medicWaveSkillKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public ItemStack createExorcistPurgeItem() {
        ItemStack it = makeSkillActivatorItem(
                Material.SOUL_LANTERN,
                "§bСвященный изгиб",
                Arrays.asList(
                        "§7ПКМ: очищающая волна против нежити",
                        "§7Ослабляет попавших врагов"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(exorcistPurgeSkillKey, PersistentDataType.BYTE, (byte) 1);
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
