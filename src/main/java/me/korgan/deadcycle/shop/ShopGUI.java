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
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class ShopGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private final String title = "§aDeadCycle Магазин";

    // слоты
    private final int[] buySlots  = {10, 11, 12, 13, 14, 15, 16};
    private final int[] sellSlots = {19, 20, 21, 22, 23};

    private static final int BASE_SCROLL_SLOT = 25;

    private final NamespacedKey baseScrollKey;

    // что показываем
    private final List<Material> buyShow = List.of(
            Material.IRON_SWORD,
            Material.SHIELD,
            Material.BOW,
            Material.ARROW,
            Material.COOKED_BEEF,
            Material.GOLDEN_APPLE,
            Material.POTION
    );

    private final List<Material> sellShow = List.of(
            Material.COBBLESTONE, Material.COAL, Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND
    );

    public ShopGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.baseScrollKey = new NamespacedKey(plugin, "base_scroll");
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, title);

        inv.setItem(4, named(Material.EMERALD, "§eБаланс: §6" + plugin.econ().getMoney(p.getUniqueId())));
        inv.setItem(8, named(Material.BARRIER, "§cЗакрыть"));

        // покупка
        for (int i = 0; i < Math.min(buyShow.size(), buySlots.length); i++) {
            Material m = buyShow.get(i);

            // potion — будет специальная цена
            if (m == Material.POTION) {
                long price = plugin.getConfig().getLong("shop.buy.POTION_HEALING", 0);
                inv.setItem(buySlots[i], priced(Material.POTION, "§bЗелье лечения", "§7Цена: §e" + price));
                continue;
            }

            long price = plugin.getConfig().getLong("shop.buy." + m.name(), 0);
            inv.setItem(buySlots[i], priced(m, "§bКупить: §f" + nice(m), "§7Цена: §e" + price));
        }

        // продажа
        for (int i = 0; i < Math.min(sellShow.size(), sellSlots.length); i++) {
            Material m = sellShow.get(i);
            long price = plugin.getConfig().getLong("shop.sell." + m.name(), 0);
            inv.setItem(sellSlots[i], priced(m, "§aПродать: §f" + nice(m), "§7Цена за 1: §e" + price));
        }

        // свиток телепорта на базу (PAPER)
        long scrollPrice = plugin.getConfig().getLong("shop.buy.BASE_SCROLL", 1000);
        inv.setItem(BASE_SCROLL_SLOT, baseScrollItem(scrollPrice));

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!title.equals(e.getView().getTitle())) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (slot == 8) { // close
            p.closeInventory();
            return;
        }

        // BASE_SCROLL
        if (slot == BASE_SCROLL_SLOT) {
            long price = plugin.getConfig().getLong("shop.buy.BASE_SCROLL", 1000);
            if (price <= 0) return;
            if (!plugin.econ().has(p, price)) { p.sendMessage("§cНедостаточно денег."); return; }

            plugin.econ().take(p, price);
            p.getInventory().addItem(createTaggedScroll(1));
            p.sendMessage("§dКуплено: §fСвиток телепорта на базу §7за §e" + price);
            open(p);
            return;
        }

        // Продажа
        for (int s : sellSlots) {
            if (slot == s) {
                Material m = clicked.getType();
                long price = plugin.getConfig().getLong("shop.sell." + m.name(), 0);
                if (price <= 0) { p.sendMessage("§cНельзя продать."); return; }
                if (!removeOne(p, m)) { p.sendMessage("§cНет ресурса."); return; }
                plugin.econ().give(p, price);
                p.sendMessage("§aПродано 1x §f" + nice(m) + " §7за §e" + price);
                open(p);
                return;
            }
        }

        // Покупка
        for (int s : buySlots) {
            if (slot != s) continue;

            Material m = clicked.getType();

            // potion special
            if (m == Material.POTION) {
                long price = plugin.getConfig().getLong("shop.buy.POTION_HEALING", 0);
                if (price <= 0) { p.sendMessage("§cНельзя купить."); return; }
                if (!plugin.econ().has(p, price)) { p.sendMessage("§cНедостаточно денег."); return; }

                plugin.econ().take(p, price);
                p.getInventory().addItem(new ItemStack(Material.POTION, 1));
                p.sendMessage("§bКуплено: §fзелье лечения §7за §e" + price);
                open(p);
                return;
            }

            long price = plugin.getConfig().getLong("shop.buy." + m.name(), 0);
            if (price <= 0) { p.sendMessage("§cНельзя купить."); return; }
            if (!plugin.econ().has(p, price)) { p.sendMessage("§cНедостаточно денег."); return; }

            plugin.econ().take(p, price);

            int amount = 1;
            if (m == Material.ARROW) amount = 16;
            if (m == Material.COOKED_BEEF) amount = 8;

            p.getInventory().addItem(new ItemStack(m, amount));
            p.sendMessage("§bКуплено: §f" + nice(m) + " x" + amount + " §7за §e" + price);
            open(p);
            return;
        }
    }

    // ===== scroll item =====
    private ItemStack baseScrollItem(long price) {
        ItemStack it = createTaggedScroll(1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§dСвиток: телепорт на базу");
            meta.setLore(Arrays.asList(
                    "§7ПКМ — телепорт на базу",
                    "§7Одноразовый предмет",
                    "§7Цена: §e" + price
            ));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack createTaggedScroll(int amount) {
        ItemStack it = new ItemStack(Material.PAPER, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(baseScrollKey, PersistentDataType.BYTE, (byte) 1);
            meta.setDisplayName("§dСвиток: телепорт на базу");
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isBaseScroll(ItemStack it) {
        if (it == null || it.getType() != Material.PAPER) return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        Byte v = meta.getPersistentDataContainer().get(baseScrollKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public void consumeOne(Player p, ItemStack inHand) {
        if (inHand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else inHand.setAmount(inHand.getAmount() - 1);
    }

    // ===== helpers =====
    private ItemStack named(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); it.setItemMeta(meta); }
        return it;
    }

    private ItemStack priced(Material m, String name, String loreLine) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Collections.singletonList(loreLine));
            it.setItemMeta(meta);
        }
        return it;
    }

    private boolean removeOne(Player p, Material m) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null) continue;
            if (it.getType() != m) continue;

            int amount = it.getAmount();
            if (amount <= 1) p.getInventory().setItem(i, null);
            else it.setAmount(amount - 1);
            return true;
        }
        return false;
    }

    private String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
