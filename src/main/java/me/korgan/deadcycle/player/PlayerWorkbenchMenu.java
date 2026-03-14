package me.korgan.deadcycle.player;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class PlayerWorkbenchMenu implements Listener {

    private static final String TITLE_MAIN_PLAIN = "Полевой верстак";
    private static final String TITLE_KIT_PLAIN = "Путь кита";
    private static final String TITLE_SPECIAL_PLAIN = "Особые навыки";

    private static final String TITLE_MAIN = ChatColor.GOLD + TITLE_MAIN_PLAIN;
    private static final String TITLE_KIT = ChatColor.DARK_GREEN + TITLE_KIT_PLAIN;
    private static final String TITLE_SPECIAL = ChatColor.DARK_PURPLE + TITLE_SPECIAL_PLAIN;

    private final DeadCyclePlugin plugin;
    private final NamespacedKey workbenchKey;

    private final Map<UUID, Long> lastOpenAt = new HashMap<>();
    private final Map<UUID, KitManager.Kit> viewedKit = new HashMap<>();

    private static final int[] KIT_BUTTON_SLOTS = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25 };
    private static final int[] PATH_SLOTS = { 29, 30, 31, 32, 33, 34, 38, 39, 40, 41, 42, 43 };

    public PlayerWorkbenchMenu(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.workbenchKey = new NamespacedKey(plugin, "profile_workbench_item");
    }

    public ItemStack createWorkbenchItem() {
        ItemStack it = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6Полевой верстак");
            meta.setLore(Arrays.asList(
                    "§7ПКМ — открыть профиль выжившего",
                    "§7Показывает прогресс кита и навыков",
                    "§8Системный предмет"));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(workbenchKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isWorkbenchItem(ItemStack it) {
        if (it == null || !it.hasItemMeta())
            return false;
        Byte marker = it.getItemMeta().getPersistentDataContainer().get(workbenchKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public void ensureWorkbenchItem(Player p) {
        if (p == null || !p.isOnline())
            return;

        ItemStack canonicalWorkbench = null;

        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (!isWorkbenchItem(item))
                continue;

            if (canonicalWorkbench == null) {
                canonicalWorkbench = item.clone();
            }
            p.getInventory().setItem(i, null);
        }

        ItemStack offHand = p.getInventory().getItemInOffHand();
        if (isWorkbenchItem(offHand)) {
            if (canonicalWorkbench == null) {
                canonicalWorkbench = offHand.clone();
            }
            p.getInventory().setItemInOffHand(null);
        }

        if (canonicalWorkbench == null) {
            canonicalWorkbench = createWorkbenchItem();
        }

        final int targetHotbarSlot = 8; // 9-й слот для игрока.
        ItemStack displaced = p.getInventory().getItem(targetHotbarSlot);
        if (displaced != null && !isWorkbenchItem(displaced)) {
            p.getInventory().setItem(targetHotbarSlot, null);
        }

        p.getInventory().setItem(targetHotbarSlot, canonicalWorkbench);

        if (displaced != null && !isWorkbenchItem(displaced)) {
            Map<Integer, ItemStack> leftovers = p.getInventory().addItem(displaced);
            for (ItemStack left : leftovers.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
    }

    public void openMain(Player p) {
        if (p == null || !p.isOnline())
            return;

        Inventory inv = Bukkit.createInventory(null, 45, TITLE_MAIN);
        fillFrame(inv);

        inv.setItem(4, createProfileItem(p));
        inv.setItem(20, createKitsSummaryItem(p));
        inv.setItem(22, button(Material.KNOWLEDGE_BOOK,
                "§eПуть уровней ?",
                "§7Показывает, что откроется дальше",
                "§7Можно переключаться между китами"));
        inv.setItem(24, createSpecialSummaryItem(p));

        inv.setItem(40, button(Material.ENDER_CHEST,
                "§bСменить кит",
                "§7Открыть меню выбора кита"));
        inv.setItem(44, button(Material.BARRIER,
                "§cЗакрыть"));

        p.openInventory(inv);
    }

    private void openKitPath(Player p, KitManager.Kit selectedKit) {
        if (p == null || !p.isOnline())
            return;

        Inventory inv = Bukkit.createInventory(null, 54, TITLE_KIT);
        fillFrame(inv);

        UUID uuid = p.getUniqueId();
        KitManager.Kit current = plugin.kit().getKit(uuid);
        KitManager.Kit selected = (selectedKit == null) ? current : selectedKit;
        viewedKit.put(uuid, selected);

        inv.setItem(4, createSelectedKitItem(p, selected, current));

        List<KitManager.Kit> order = Arrays.asList(
                KitManager.Kit.FIGHTER,
                KitManager.Kit.MINER,
                KitManager.Kit.BUILDER,
                KitManager.Kit.ARCHER,
                KitManager.Kit.BERSERK,
                KitManager.Kit.GRAVITATOR,
                KitManager.Kit.DUELIST,
                KitManager.Kit.CLONER,
                KitManager.Kit.SUMMONER,
                KitManager.Kit.PING,
                KitManager.Kit.HARPOONER,
                KitManager.Kit.CYBORG,
                KitManager.Kit.MEDIC,
                KitManager.Kit.EXORCIST);

        for (int i = 0; i < order.size() && i < KIT_BUTTON_SLOTS.length; i++) {
            KitManager.Kit kit = order.get(i);
            inv.setItem(KIT_BUTTON_SLOTS[i], createKitSwitchItem(uuid, kit, current, selected));
        }

        int level = plugin.progress().getKitLevel(uuid, selected);
        List<String> skillOrder = plugin.kit().getKitSkillOrder(selected);

        if (skillOrder.isEmpty()) {
            inv.setItem(31, button(Material.SHIELD,
                    "§fУ этого кита нет активируемых предметов",
                    "§7Прогресс идёт через пассивные бонусы",
                    "§7и базовую роль кита."));
        } else {
            for (int i = 0; i < skillOrder.size() && i < PATH_SLOTS.length; i++) {
                String skillId = skillOrder.get(i);
                inv.setItem(PATH_SLOTS[i], createSkillPathItem(selected, skillId, level));
            }
        }

        inv.setItem(45, button(Material.ARROW, "§aНазад", "§7Вернуться в профиль"));
        inv.setItem(49, button(Material.AMETHYST_SHARD, "§dПрогресс особых навыков"));
        inv.setItem(53, button(Material.NETHER_STAR, "§bОткрыть выбор кита"));

        p.openInventory(inv);
    }

    private void openSpecialProgress(Player p) {
        if (p == null || !p.isOnline())
            return;

        Inventory inv = Bukkit.createInventory(null, 45, TITLE_SPECIAL);
        fillFrame(inv);

        UUID uuid = p.getUniqueId();
        SpecialSkillManager special = plugin.specialSkills();

        inv.setItem(13, specialProgressItem(
                Material.AMETHYST_SHARD,
                "§aРегенерация",
                special != null && special.isRegenUnlocked(uuid),
                special == null ? 0 : special.getDamageTaken(uuid),
                special == null ? 0 : special.getRemainingToRegenUnlock(uuid),
                "Получить урон"));

        inv.setItem(22, specialProgressItem(
                Material.GLOW_BERRIES,
                "§bАвтореген",
                special != null && special.isAutoRegenUnlocked(uuid),
                special == null ? 0 : special.getHealWithRegen(uuid),
                special == null ? 0 : special.getRemainingToAutoRegenUnlock(uuid),
                "Восстановить здоровья"));

        inv.setItem(31, createAutoDodgeProgressItem(uuid, special));

        inv.setItem(45 - 9, button(Material.ARROW, "§aНазад", "§7Вернуться в профиль"));
        inv.setItem(45 - 5, button(Material.KNOWLEDGE_BOOK, "§eПуть кита"));
        inv.setItem(44, button(Material.BARRIER, "§cЗакрыть"));

        p.openInventory(inv);
    }

    private ItemStack createProfileItem(Player p) {
        UUID uuid = p.getUniqueId();
        KitManager.Kit currentKit = plugin.kit().getKit(uuid);

        int playerLevel = plugin.progress().getPlayerLevel(uuid);
        int playerExp = plugin.progress().getPlayerExp(uuid);
        int playerNeed = Math.max(1, plugin.progress().getPlayerNeedExp(uuid));

        int kitLevel = plugin.progress().getKitLevel(uuid, currentKit);
        int kitExp = plugin.progress().getKitExp(uuid, currentKit);
        int kitNeed = Math.max(1, plugin.progress().getKitNeedExp(uuid, currentKit));

        int manaCurrent = plugin.mana() == null ? 0 : plugin.mana().getCurrentXp(p);
        int manaMax = plugin.mana() == null ? 0 : plugin.mana().getMaxXp(uuid);

        long money = plugin.econ() == null ? 0L : plugin.econ().getMoney(uuid);

        List<String> lore = new ArrayList<>();
        lore.add("§7Текущий кит: §f" + plugin.kit().getKitDisplayName(currentKit));
        lore.add("§7Уровень игрока: §a" + playerLevel + " §8(" + playerExp + "/" + playerNeed + ")");
        lore.add("§8" + progressBar(playerExp, playerNeed, 16));
        lore.add("§7Уровень кита: §b" + kitLevel + " §8(" + kitExp + "/" + kitNeed + ")");
        lore.add("§8" + progressBar(kitExp, kitNeed, 16));
        lore.add("§7Мана: §d" + manaCurrent + "§8/§d" + manaMax);
        lore.add("§7Баланс: §6" + money + "$");

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(p);
            skullMeta.setDisplayName("§6Профиль: §f" + p.getName());
            skullMeta.setLore(lore);
            skullMeta.setEnchantmentGlintOverride(true);
            head.setItemMeta(skullMeta);
            return head;
        }

        if (meta != null) {
            meta.setDisplayName("§6Профиль: §f" + p.getName());
            meta.setLore(lore);
            meta.setEnchantmentGlintOverride(true);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createKitsSummaryItem(Player p) {
        UUID uuid = p.getUniqueId();
        KitManager.Kit current = plugin.kit().getKit(uuid);
        int level = plugin.progress().getKitLevel(uuid, current);

        String nextUnlockLine = "§7Следующая веха: §fвсе навыки открыты";
        for (String skillId : plugin.kit().getKitSkillOrder(current)) {
            int unlock = plugin.kit().getSkillUnlockLevel(current, skillId);
            if (unlock > level) {
                nextUnlockLine = "§7Следующая веха: §eур. " + unlock + " §8- §f"
                        + plugin.kit().getSkillDisplayName(skillId);
                break;
            }
        }

        return button(Material.CHEST,
                "§aКиты и прогресс",
                "§7Текущий: §f" + plugin.kit().getKitDisplayName(current),
                "§7Уровень кита: §b" + level,
                nextUnlockLine,
                "§8Нажми, чтобы открыть список китов");
    }

    private ItemStack createSpecialSummaryItem(Player p) {
        UUID uuid = p.getUniqueId();
        SpecialSkillManager special = plugin.specialSkills();

        int remainRegen = special == null ? 0 : special.getRemainingToRegenUnlock(uuid);
        int remainAutoRegen = special == null ? 0 : special.getRemainingToAutoRegenUnlock(uuid);
        int remainAutoDodgeTaken = special == null ? 0 : special.getRemainingToAutoDodgeTakenUnlock(uuid);
        int remainAutoDodgeDealt = special == null ? 0 : special.getRemainingToAutoDodgeDealtUnlock(uuid);

        return button(Material.AMETHYST_SHARD,
                "§dОсобые навыки",
                "§7До регена осталось: §f" + remainRegen,
                "§7До авторегена осталось: §f" + remainAutoRegen,
                "§7До автоуклонения осталось: §f"
                        + Math.max(remainAutoDodgeTaken, remainAutoDodgeDealt),
                "§8Нажми для подробного прогресса");
    }

    private ItemStack createKitSwitchItem(UUID uuid, KitManager.Kit kit, KitManager.Kit current,
            KitManager.Kit selected) {
        int level = plugin.progress().getKitLevel(uuid, kit);
        Material icon = iconForKit(kit);

        ItemStack it = new ItemStack(icon);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = (kit == selected ? "§e▶ " : "§7") + plugin.kit().getKitDisplayName(kit);
            meta.setDisplayName(name);

            List<String> lore = new ArrayList<>();
            lore.add("§7Уровень: §b" + level);
            lore.add(kit == current ? "§aТекущий выбранный кит" : "§8Не активный сейчас");
            lore.add("§8Нажми для просмотра пути навыков");

            if (kit == selected) {
                meta.setEnchantmentGlintOverride(true);
            }

            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack createSelectedKitItem(Player p, KitManager.Kit selected, KitManager.Kit current) {
        UUID uuid = p.getUniqueId();
        int level = plugin.progress().getKitLevel(uuid, selected);
        int exp = plugin.progress().getKitExp(uuid, selected);
        int need = Math.max(1, plugin.progress().getKitNeedExp(uuid, selected));

        String currentMarker = (selected == current) ? "§a(текущий)" : "§7(просмотр)";

        return button(Material.NETHER_STAR,
                "§f" + plugin.kit().getKitDisplayName(selected) + " " + currentMarker,
                "§7Уровень: §b" + level,
                "§7Опыт: §f" + exp + "§8/§f" + need,
                "§8" + progressBar(exp, need, 18),
                "§7Ниже показан путь навыков и будущие открытия.");
    }

    private ItemStack createSkillPathItem(KitManager.Kit kit, String skillId, int currentLevel) {
        int unlock = plugin.kit().getSkillUnlockLevel(kit, skillId);
        boolean unlocked = currentLevel >= unlock;

        String name = plugin.kit().getSkillDisplayName(skillId);
        Material mat = unlocked ? Material.LIME_DYE : Material.KNOWLEDGE_BOOK;

        List<String> lore = new ArrayList<>();
        if (unlocked) {
            lore.add("§aОткрыто");
            lore.add("§7Требуемый уровень: §f" + unlock);
        } else {
            int remain = Math.max(0, unlock - currentLevel);
            lore.add("§7? Скрытый потенциал");
            lore.add("§7Откроется на: §eур. " + unlock);
            lore.add("§7Осталось уровней: §f" + remain);
        }

        if (unlock >= 7) {
            lore.add("§dВысокий уровень кита");
        }

        return button(mat,
                (unlocked ? "§a" : "§7") + name,
                lore.toArray(new String[0]));
    }

    private ItemStack specialProgressItem(Material mat, String title, boolean unlocked, int current, int remaining,
            String objectiveText) {
        int need = Math.max(current, current + remaining);
        List<String> lore = new ArrayList<>();
        if (unlocked) {
            lore.add("§aОткрыто");
            lore.add("§7Условие выполнено.");
        } else {
            lore.add("§7Прогресс: §f" + current + "§8/§f" + need);
            lore.add("§8" + progressBar(current, need, 16));
            lore.add("§7Осталось: §f" + remaining);
            lore.add("§7Цель: §f" + objectiveText);
        }

        return button(mat, title, lore.toArray(new String[0]));
    }

    private ItemStack createAutoDodgeProgressItem(UUID uuid, SpecialSkillManager special) {
        boolean unlocked = special != null && special.isAutoDodgeUnlocked(uuid);
        int taken = special == null ? 0 : special.getDamageTaken(uuid);
        int dealt = special == null ? 0 : special.getDamageDealt(uuid);
        int remainTaken = special == null ? 0 : special.getRemainingToAutoDodgeTakenUnlock(uuid);
        int remainDealt = special == null ? 0 : special.getRemainingToAutoDodgeDealtUnlock(uuid);

        List<String> lore = new ArrayList<>();
        if (unlocked) {
            lore.add("§aОткрыто");
            lore.add("§7Обе боевые проверки выполнены.");
        } else {
            lore.add("§7Получить урон: §f" + taken + " §8(осталось " + remainTaken + ")");
            lore.add("§7Нанести урон: §f" + dealt + " §8(осталось " + remainDealt + ")");
            lore.add("§7Навык откроется после выполнения обеих целей.");
        }

        return button(Material.FEATHER,
                "§eАвтоуклонение",
                lore.toArray(new String[0]));
    }

    private ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && lore.length > 0)
                meta.setLore(Arrays.asList(lore));
            it.setItemMeta(meta);
        }
        return it;
    }

    private void fillFrame(Inventory inv) {
        ItemStack frame = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        int size = inv.getSize();
        int rows = size / 9;

        for (int x = 0; x < 9; x++) {
            inv.setItem(x, frame);
            inv.setItem(size - 9 + x, frame);
        }

        for (int y = 1; y < rows - 1; y++) {
            inv.setItem(y * 9, frame);
            inv.setItem(y * 9 + 8, frame);
        }
    }

    private String progressBar(int current, int max, int width) {
        int safeMax = Math.max(1, max);
        int safeCurrent = Math.max(0, Math.min(current, safeMax));
        int filled = (int) Math.round((safeCurrent / (double) safeMax) * width);

        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.DARK_GREEN);
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '|' : ChatColor.DARK_GRAY + "|");
            if (i < filled - 1) {
                sb.append(ChatColor.DARK_GREEN);
            }
        }
        return sb.toString();
    }

    private Material iconForKit(KitManager.Kit kit) {
        return switch (kit) {
            case FIGHTER -> Material.IRON_SWORD;
            case MINER -> Material.IRON_PICKAXE;
            case BUILDER -> Material.SMITHING_TABLE;
            case ARCHER -> Material.BOW;
            case BERSERK -> Material.NETHERITE_AXE;
            case GRAVITATOR -> Material.HEAVY_CORE;
            case DUELIST -> Material.NETHER_STAR;
            case CLONER -> Material.ENDER_EYE;
            case SUMMONER -> Material.TOTEM_OF_UNDYING;
            case PING -> Material.ENDER_PEARL;
            case HARPOONER -> Material.TRIDENT;
            case CYBORG -> Material.BREEZE_ROD;
            case MEDIC -> Material.GOLDEN_APPLE;
            case EXORCIST -> Material.SOUL_LANTERN;
        };
    }

    private boolean isWorkbenchMenuTitle(String title) {
        if (title == null)
            return false;
        return TITLE_MAIN_PLAIN.equalsIgnoreCase(title)
                || TITLE_KIT_PLAIN.equalsIgnoreCase(title)
                || TITLE_SPECIAL_PLAIN.equalsIgnoreCase(title);
    }

    private KitManager.Kit kitBySlot(int slot) {
        List<KitManager.Kit> order = Arrays.asList(
                KitManager.Kit.FIGHTER,
                KitManager.Kit.MINER,
                KitManager.Kit.BUILDER,
                KitManager.Kit.ARCHER,
                KitManager.Kit.BERSERK,
                KitManager.Kit.GRAVITATOR,
                KitManager.Kit.DUELIST,
                KitManager.Kit.CLONER,
                KitManager.Kit.SUMMONER,
                KitManager.Kit.PING,
                KitManager.Kit.HARPOONER,
                KitManager.Kit.CYBORG,
                KitManager.Kit.MEDIC,
                KitManager.Kit.EXORCIST);

        for (int i = 0; i < KIT_BUTTON_SLOTS.length && i < order.size(); i++) {
            if (KIT_BUTTON_SLOTS[i] == slot)
                return order.get(i);
        }
        return null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
            return;

        ItemStack item = e.getItem();
        if (!isWorkbenchItem(item))
            return;

        e.setCancelled(true);

        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        long last = lastOpenAt.getOrDefault(p.getUniqueId(), 0L);
        if ((now - last) < 250L)
            return;

        lastOpenAt.put(p.getUniqueId(), now);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
        openMain(p);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (!isWorkbenchItem(e.getItemDrop().getItemStack()))
            return;

        e.setCancelled(true);
        e.getPlayer().sendMessage("§cЭтот предмет нельзя выбрасывать.");
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        for (ItemStack ingredient : e.getInventory().getMatrix()) {
            if (!isWorkbenchItem(ingredient))
                continue;

            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) {
                p.sendMessage("§cПолевой верстак нельзя использовать в крафте.");
            }
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        String title = ChatColor.stripColor(e.getView().getTitle());
        if (!isWorkbenchMenuTitle(title))
            return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;

        String title = ChatColor.stripColor(e.getView().getTitle());

        if (!isWorkbenchMenuTitle(title)) {
            InventoryType type = e.getInventory().getType();
            if (type != InventoryType.PLAYER) {
                if (isWorkbenchItem(e.getCurrentItem()) || isWorkbenchItem(e.getCursor())) {
                    e.setCancelled(true);
                    p.sendMessage("§cПолевой верстак нельзя перемещать в этот инвентарь.");
                }
            }
            return;
        }

        e.setCancelled(true);
        int slot = e.getRawSlot();

        if (TITLE_MAIN_PLAIN.equalsIgnoreCase(title)) {
            if (slot == 20 || slot == 22) {
                openKitPath(p, plugin.kit().getKit(p.getUniqueId()));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                return;
            }
            if (slot == 24) {
                openSpecialProgress(p);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                return;
            }
            if (slot == 40) {
                plugin.kitMenu().open(p);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                return;
            }
            if (slot == 44) {
                p.closeInventory();
            }
            return;
        }

        if (TITLE_KIT_PLAIN.equalsIgnoreCase(title)) {
            if (slot == 45) {
                openMain(p);
                return;
            }
            if (slot == 49) {
                openSpecialProgress(p);
                return;
            }
            if (slot == 53) {
                plugin.kitMenu().open(p);
                return;
            }

            KitManager.Kit nextKit = kitBySlot(slot);
            if (nextKit != null) {
                openKitPath(p, nextKit);
            }
            return;
        }

        if (TITLE_SPECIAL_PLAIN.equalsIgnoreCase(title)) {
            if (slot == 36) {
                openMain(p);
                return;
            }
            if (slot == 40) {
                KitManager.Kit selected = viewedKit.getOrDefault(
                        p.getUniqueId(),
                        plugin.kit().getKit(p.getUniqueId()));
                openKitPath(p, selected);
                return;
            }
            if (slot == 44) {
                p.closeInventory();
            }
        }
    }
}
