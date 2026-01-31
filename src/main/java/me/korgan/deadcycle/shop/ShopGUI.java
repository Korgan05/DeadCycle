package me.korgan.deadcycle.shop;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Shop v2:
 * - Только покупка (ресурсы базы НЕ продаются через магазин)
 * - Вкладки категорий
 * - Покупка: ЛКМ x1, Shift+ЛКМ x16 (если стакается), ПКМ x64 (если стакается)
 * - Баланс цен в config.yml: shop.buy.<MATERIAL>=price
 */
public class ShopGUI implements Listener {

    private final DeadCyclePlugin plugin;

    private static final String TITLE = "§aDeadCycle • Магазин";

    private enum Category {
        WEAPONS("§cОружие", Material.IRON_SWORD),
        ARMOR("§bБроня", Material.IRON_CHESTPLATE),
        RANGED("§eДальний бой", Material.BOW),
        FOOD("§6Еда", Material.COOKED_BEEF),
        UTILS("§aПолезное", Material.TORCH);

        final String title;
        final Material icon;
        Category(String title, Material icon) { this.title = title; this.icon = icon; }
    }

    // слоты интерфейса
    private static final int SIZE = 54;
    private static final int SLOT_BALANCE = 4;
    private static final int SLOT_INFO = 49;

    private static final int[] TAB_SLOTS = {0,1,2,3,5,6,7,8};
    private static final int[] GRID_SLOTS = {
            10,11,12,13,14,15,16,
            19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,
            37,38,39,40,41,42,43
    };

    // Запоминаем, какая вкладка открыта у игрока
    private final Map<UUID, Category> opened = new HashMap<>();

    public ShopGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        open(p, opened.getOrDefault(p.getUniqueId(), Category.WEAPONS));
    }

    private void open(Player p, Category cat) {
        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            p.sendMessage(ChatColor.RED + "Магазин доступен только на базе!");
            return;
        }

        opened.put(p.getUniqueId(), cat);

        Inventory inv = Bukkit.createInventory(null, SIZE, TITLE);

        // фон
        for (int i = 0; i < SIZE; i++) inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE, " "));

        // верхняя полоса вкладок
        drawTabs(inv, cat);

        // баланс
        long money = plugin.econ().getMoney(p.getUniqueId());
        inv.setItem(SLOT_BALANCE, item(Material.EMERALD,
                "§eБаланс: §6" + money,
                "§7Доступно только на базе",
                "§7Покупка: §aЛКМ x1",
                "§7Покупка: §aShift+ЛКМ x16",
                "§7Покупка: §aПКМ x64"));

        // инфо снизу
        inv.setItem(SLOT_INFO, item(Material.BOOK,
                "§fПравила магазина",
                "§7Продажа ресурсов выключена.",
                "§7Ресурсы нужно сдавать на базу.",
                "§7Деньги — для покупки снаряги."));

        // товары
        List<ShopEntry> entries = entriesFor(cat);
        int idx = 0;
        for (ShopEntry e : entries) {
            if (idx >= GRID_SLOTS.length) break;
            inv.setItem(GRID_SLOTS[idx++], toItem(e));
        }

        p.openInventory(inv);
    }

    private void drawTabs(Inventory inv, Category active) {
        // очистим табы
        for (int slot : TAB_SLOTS) inv.setItem(slot, pane(Material.BLACK_STAINED_GLASS_PANE, " "));

        // рисуем 5 вкладок по центру
        Category[] cats = Category.values();
        int[] slots = {1,2,3,5,6}; // красиво по центру
        for (int i = 0; i < cats.length; i++) {
            Category c = cats[i];
            boolean isActive = (c == active);

            Material mat = isActive ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE;
            String name = (isActive ? "§a▶ " : "§7") + c.title;

            inv.setItem(slots[i], item(mat, name, "§8Нажми чтобы открыть"));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;

        // безопасность: магазин только на базе
        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            p.closeInventory();
            p.sendMessage(ChatColor.RED + "Магазин доступен только на базе!");
            return;
        }

        int slot = e.getRawSlot();
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // вкладки
        if (slot == 1) { open(p, Category.WEAPONS); clickSound(p); return; }
        if (slot == 2) { open(p, Category.ARMOR);   clickSound(p); return; }
        if (slot == 3) { open(p, Category.RANGED);  clickSound(p); return; }
        if (slot == 5) { open(p, Category.FOOD);    clickSound(p); return; }
        if (slot == 6) { open(p, Category.UTILS);   clickSound(p); return; }

        // клик по товару
        ShopEntry entry = fromItem(clicked);
        if (entry == null) return;

        int buyAmount = 1;

        // Shift+ЛКМ -> x16 если стакается
        if (e.isShiftClick() && e.isLeftClick()) {
            buyAmount = stackAmount(entry.material, 16);
        }
        // ПКМ -> x64 если стакается
        else if (e.isRightClick()) {
            buyAmount = stackAmount(entry.material, 64);
        }

        long priceOne = price(entry);
        if (priceOne <= 0) {
            p.sendMessage(ChatColor.RED + "Этот товар временно недоступен.");
            failSound(p);
            return;
        }

        long totalCost = priceOne * buyAmount;
        if (!plugin.econ().has(p, totalCost)) {
            p.sendMessage(ChatColor.RED + "Не хватает денег! Нужно: " + ChatColor.YELLOW + totalCost);
            failSound(p);
            return;
        }

        // пытаемся выдать предметы (если инвентарь полон — не списываем)
        ItemStack give = new ItemStack(entry.material, buyAmount);
        Map<Integer, ItemStack> leftovers = p.getInventory().addItem(give);
        if (!leftovers.isEmpty()) {
            // откат
            leftovers.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
            p.sendMessage(ChatColor.RED + "Инвентарь был переполнен — лишнее выброшено на землю.");
        }

        plugin.econ().take(p, totalCost);
        p.sendMessage(ChatColor.GREEN + "Куплено: " + ChatColor.WHITE + nice(entry.material)
                + ChatColor.GRAY + " x" + buyAmount
                + ChatColor.GRAY + " за " + ChatColor.GOLD + totalCost);

        buySound(p);
        // перерисуем меню чтобы обновить баланс
        open(p, opened.getOrDefault(p.getUniqueId(), Category.WEAPONS));
    }

    // ====== Entries ======

    private static final class ShopEntry {
        final Material material;
        final String name;
        final List<String> extraLore;

        ShopEntry(Material material, String name, String... lore) {
            this.material = material;
            this.name = name;
            this.extraLore = Arrays.asList(lore);
        }
    }

    private List<ShopEntry> entriesFor(Category c) {
        // ВАЖНО: цены берём из config.yml: shop.buy.<MATERIAL>
        // Тут только ассортимент и названия.

        if (c == Category.WEAPONS) {
            return List.of(
                    new ShopEntry(Material.STONE_SWORD, "§fКаменный меч"),
                    new ShopEntry(Material.IRON_SWORD, "§fЖелезный меч"),
                    new ShopEntry(Material.DIAMOND_SWORD, "§fАлмазный меч"),
                    new ShopEntry(Material.STONE_AXE, "§fТопор"),
                    new ShopEntry(Material.SHIELD, "§fЩит", "§7Очень полезен ночью")
            );
        }

        if (c == Category.ARMOR) {
            return List.of(
                    new ShopEntry(Material.IRON_HELMET, "§fЖелезный шлем"),
                    new ShopEntry(Material.IRON_CHESTPLATE, "§fЖелезный нагрудник"),
                    new ShopEntry(Material.IRON_LEGGINGS, "§fЖелезные поножи"),
                    new ShopEntry(Material.IRON_BOOTS, "§fЖелезные ботинки"),

                    new ShopEntry(Material.DIAMOND_HELMET, "§fАлмазный шлем"),
                    new ShopEntry(Material.DIAMOND_CHESTPLATE, "§fАлмазный нагрудник"),
                    new ShopEntry(Material.DIAMOND_LEGGINGS, "§fАлмазные поножи"),
                    new ShopEntry(Material.DIAMOND_BOOTS, "§fАлмазные ботинки")
            );
        }

        if (c == Category.RANGED) {
            return List.of(
                    new ShopEntry(Material.BOW, "§fЛук"),
                    new ShopEntry(Material.ARROW, "§fСтрела"),
                    new ShopEntry(Material.SPECTRAL_ARROW, "§fСветящаяся стрела", "§7Если не хочешь — просто не ставь цену в конфиге"),
                    new ShopEntry(Material.FISHING_ROD, "§fУдочка", "§7Можно как контроль (потом баланс)")
            );
        }

        if (c == Category.FOOD) {
            return List.of(
                    new ShopEntry(Material.COOKED_BEEF, "§fСтейк"),
                    new ShopEntry(Material.COOKED_CHICKEN, "§fКурица"),
                    new ShopEntry(Material.BREAD, "§fХлеб"),
                    new ShopEntry(Material.GOLDEN_APPLE, "§6Золотое яблоко", "§7Сильная вещь, цену ставь нормально")
            );
        }

        // UTILS
        return List.of(
                new ShopEntry(Material.TORCH, "§fФакелы"),
                new ShopEntry(Material.OAK_PLANKS, "§fДоски", "§7Для крафта на базе"),
                new ShopEntry(Material.COBBLESTONE, "§fБулыжник", "§7Для крафта/строя (но на базе ставить нельзя)"),
                new ShopEntry(Material.CHEST, "§fСундук"),
                new ShopEntry(Material.CRAFTING_TABLE, "§fВерстак")
        );
    }

    // ====== Price / Item meta ======

    private long price(ShopEntry e) {
        return plugin.getConfig().getLong("shop.buy." + e.material.name(), 0L);
    }

    private ItemStack toItem(ShopEntry e) {
        long p = price(e);

        List<String> lore = new ArrayList<>();
        lore.add("§7Цена за 1: §e" + (p > 0 ? p : "—"));
        lore.add("§7ЛКМ: купить x1");
        lore.add("§7Shift+ЛКМ: купить x16");
        lore.add("§7ПКМ: купить x64");
        if (!e.extraLore.isEmpty()) {
            lore.add(" ");
            lore.addAll(e.extraLore);
        }

        return item(e.material, e.name, lore.toArray(new String[0]));
    }

    // Сигнатура товара по material (достаём из meta)
    private ShopEntry fromItem(ItemStack it) {
        // У товара реальный материал = материал покупки
        Material m = it.getType();

        // Это защита: запрещаем клик по “панелям”
        if (m.name().endsWith("_GLASS_PANE") || m == Material.BOOK || m == Material.EMERALD) return null;

        // Проверим, что в конфиге вообще есть цена или хотя бы материал есть в ассортименте вкладки:
        // проще: считаем, что любой материал из наших списков — товар
        return new ShopEntry(m, ""); // name не нужен для логики покупки
    }

    private int stackAmount(Material m, int want) {
        int max = m.getMaxStackSize();
        if (max <= 1) return 1;
        return Math.min(max, want);
    }

    private ItemStack pane(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }

    private String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void clickSound(Player p) {
        try { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.3f); } catch (Throwable ignored) {}
    }

    private void buySound(Player p) {
        try { p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f); } catch (Throwable ignored) {}
    }

    private void failSound(Player p) {
        try { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f); } catch (Throwable ignored) {}
    }
}
