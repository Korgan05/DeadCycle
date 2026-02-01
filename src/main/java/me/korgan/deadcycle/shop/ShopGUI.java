package me.korgan.deadcycle.shop;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;

public class ShopGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private final String title = "§aDeadCycle Магазин";
    private final NamespacedKey shopKey;

    public ShopGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.shopKey = new NamespacedKey(plugin, "dc_shop_key");
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, title);

        // шапка
        inv.setItem(4, info(Material.EMERALD, "§eБаланс: §6" + plugin.econ().getMoney(p.getUniqueId())));

        // разделители
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler());
        }

        // чистим рабочую зону
        clear(inv, 10, 16);
        clear(inv, 19, 25);
        clear(inv, 28, 34);
        clear(inv, 37, 43);

        // ===== оружие/броня =====
        putBuy(inv, 10, matItem(Material.IRON_SWORD, "§bЖелезный меч"), "IRON_SWORD", 1);
        putBuy(inv, 11, matItem(Material.SHIELD, "§bЩит"), "SHIELD", 1);
        putBuy(inv, 12, matItem(Material.BOW, "§bЛук"), "BOW", 1);

        putBuy(inv, 19, matItem(Material.IRON_HELMET, "§bЖелезный шлем"), "IRON_HELMET", 1);
        putBuy(inv, 20, matItem(Material.IRON_CHESTPLATE, "§bЖелезный нагрудник"), "IRON_CHESTPLATE", 1);
        putBuy(inv, 21, matItem(Material.IRON_LEGGINGS, "§bЖелезные поножи"), "IRON_LEGGINGS", 1);
        putBuy(inv, 22, matItem(Material.IRON_BOOTS, "§bЖелезные ботинки"), "IRON_BOOTS", 1);

        // ===== стрелы/еда =====
        putBuy(inv, 14, matItem(Material.ARROW, "§aСтрела"), "ARROW", 1);
        putBuy(inv, 15, packItem(Material.ARROW, "§aСтрелы x16"), "ARROW_16", 16);
        putBuy(inv, 16, packItem(Material.ARROW, "§aСтрелы x64"), "ARROW_64", 64);

        putBuy(inv, 23, matItem(Material.BREAD, "§6Хлеб"), "BREAD", 1);
        putBuy(inv, 24, matItem(Material.COOKED_CHICKEN, "§6Курица"), "COOKED_CHICKEN", 1);
        putBuy(inv, 25, matItem(Material.COOKED_BEEF, "§6Стейк"), "COOKED_BEEF", 1);

        putBuy(inv, 32, packItem(Material.BREAD, "§6Еда x16 (хлеб)"), "FOOD_16_BREAD", 16);
        putBuy(inv, 33, packItem(Material.COOKED_BEEF, "§6Еда x16 (стейк)"), "FOOD_16_BEEF", 16);

        putBuy(inv, 34, matItem(Material.GOLDEN_APPLE, "§eЗолотое яблоко"), "GOLDEN_APPLE", 1);

        // ===== зелья =====
        putBuy(inv, 37, potionItem("§dЗелье лечения", PotionType.HEALING), "POTION_HEALING", 1);
        putBuy(inv, 38, potionItem("§dЗелье скорости", PotionType.SWIFTNESS), "POTION_SWIFTNESS", 1);
        putBuy(inv, 39, potionItem("§dЗелье силы", PotionType.STRENGTH), "POTION_STRENGTH", 1);
        putBuy(inv, 40, potionItem("§dЗелье регена", PotionType.REGENERATION), "POTION_REGEN", 1);

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!title.equals(e.getView().getTitle())) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() == Material.AIR) return;

        String key = getKey(it);
        if (key == null) return;

        long price = plugin.getConfig().getLong("shop.buy." + key, -1);
        if (price < 0) {
            p.sendMessage("§cЦена не настроена: §f" + key);
            return;
        }

        if (!plugin.econ().has(p, price)) {
            p.sendMessage("§cНедостаточно денег.");
            return;
        }

        plugin.econ().take(p, price);

        ItemStack give = buildGiveItem(key, it);
        int amount = getAmount(it);

        give.setAmount(Math.max(1, amount));
        p.getInventory().addItem(give);

        p.sendMessage("§aКуплено: §f" + strip(it) + " §7за §e" + price + "$");
        open(p);
    }

    // ===== helpers =====

    private void putBuy(Inventory inv, int slot, ItemStack display, String key, int amount) {
        long price = plugin.getConfig().getLong("shop.buy." + key, -1);

        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("§7Цена: §e" + (price >= 0 ? price : "?") + "$");
            lore.add("§8Клик — купить");
            meta.setLore(lore);

            meta.getPersistentDataContainer().set(shopKey, PersistentDataType.STRING, key);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "dc_shop_amount"),
                    PersistentDataType.INTEGER, amount);

            display.setItemMeta(meta);
        }
        inv.setItem(slot, display);
    }

    private ItemStack buildGiveItem(String key, ItemStack clicked) {
        // наборы
        if (key.equals("ARROW_16") || key.equals("ARROW_64") || key.equals("ARROW")) return new ItemStack(Material.ARROW);
        if (key.equals("FOOD_16_BREAD") || key.equals("BREAD")) return new ItemStack(Material.BREAD);
        if (key.equals("FOOD_16_BEEF") || key.equals("COOKED_BEEF")) return new ItemStack(Material.COOKED_BEEF);
        if (key.equals("COOKED_CHICKEN")) return new ItemStack(Material.COOKED_CHICKEN);

        // зелья
        if (key.startsWith("POTION_")) {
            // берём “как на витрине”
            ItemStack potion = clicked.clone();
            potion.setAmount(1);
            // убираем лишние ключи из меты
            ItemMeta meta = potion.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().remove(shopKey);
                meta.getPersistentDataContainer().remove(new NamespacedKey(plugin, "dc_shop_amount"));
                potion.setItemMeta(meta);
            }
            return potion;
        }

        // остальное по названию материала
        try {
            Material m = Material.valueOf(key);
            return new ItemStack(m);
        } catch (IllegalArgumentException ex) {
            // fallback
            return new ItemStack(clicked.getType());
        }
    }

    private String getKey(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(shopKey, PersistentDataType.STRING);
    }

    private int getAmount(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return 1;
        Integer a = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "dc_shop_amount"),
                PersistentDataType.INTEGER);
        return (a == null ? 1 : a);
    }

    private ItemStack matItem(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack packItem(Material m, String name) {
        return matItem(m, name);
    }

    private ItemStack potionItem(String name, PotionType type) {
        ItemStack it = new ItemStack(Material.POTION);
        ItemMeta im = it.getItemMeta();
        if (im instanceof PotionMeta pm) {
            pm.setDisplayName(name);
            pm.setBasePotionType(type);
            it.setItemMeta(pm);
        } else if (im != null) {
            im.setDisplayName(name);
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack info(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private void clear(Inventory inv, int from, int to) {
        for (int i = from; i <= to; i++) inv.setItem(i, null);
    }

    private String strip(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null || meta.displayName() == null) return it.getType().name();
        // для Paper: просто вернём название через legacy
        return meta.getDisplayName();
    }
}
