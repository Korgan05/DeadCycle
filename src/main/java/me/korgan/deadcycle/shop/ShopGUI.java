package me.korgan.deadcycle.shop;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.List;

public class ShopGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private final String title = "§aDeadCycle Магазин";

    private final int[] sellSlots = {10, 11, 12, 13, 14};
    private final int[] buySlots  = {16, 17, 18, 19, 20};

    private final List<Material> sellShow = List.of(
            Material.COBBLESTONE, Material.COAL, Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND
    );

    private final List<Material> buyShow = List.of(
            Material.IRON_SWORD, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_HELMET, Material.IRON_BOOTS
    );

    public ShopGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, title);

        inv.setItem(4, named(Material.EMERALD, "§eБаланс: §6" + plugin.econ().getMoney(p.getUniqueId())));
        inv.setItem(7, named(Material.REDSTONE, "§cПродажа (клик)"));
        inv.setItem(8, named(Material.DIAMOND_SWORD, "§bПокупка (клик)"));

        for (int i = 0; i < Math.min(sellShow.size(), sellSlots.length); i++) {
            Material m = sellShow.get(i);
            long price = plugin.getConfig().getLong("shop.sell." + m.name(), 0);
            inv.setItem(sellSlots[i], priced(m, "§aПродать: §f" + nice(m), "§7Цена за 1: §e" + price));
        }

        for (int i = 0; i < Math.min(buyShow.size(), buySlots.length); i++) {
            Material m = buyShow.get(i);
            long price = plugin.getConfig().getLong("shop.buy." + m.name(), 0);
            inv.setItem(buySlots[i], priced(m, "§bКупить: §f" + nice(m), "§7Цена: §e" + price));
        }

        // доп-товары пакетами (если есть цены)
        long a16 = plugin.getConfig().getLong("shop.buy.ARROW_16", 0);
        if (a16 > 0) inv.setItem(22, priced(Material.ARROW, "§bКупить: §fстрелы x16", "§7Цена: §e" + a16));

        long f16 = plugin.getConfig().getLong("shop.buy.COOKED_BEEF_16", 0);
        if (f16 > 0) inv.setItem(23, priced(Material.COOKED_BEEF, "§bКупить: §fеда x16", "§7Цена: §e" + f16));

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

        // Покупка обычная
        for (int s : buySlots) {
            if (slot == s) {
                Material m = clicked.getType();
                long price = plugin.getConfig().getLong("shop.buy." + m.name(), 0);
                if (price <= 0) { p.sendMessage("§cНельзя купить."); return; }
                if (!plugin.econ().has(p, price)) { p.sendMessage("§cНедостаточно денег."); return; }
                plugin.econ().take(p, price);
                p.getInventory().addItem(new ItemStack(m, 1));
                p.sendMessage("§bКуплено: §f" + nice(m) + " §7за §e" + price);
                open(p);
                return;
            }
        }

        // Покупка пакетами
        if (slot == 22 && clicked.getType() == Material.ARROW) {
            long price = plugin.getConfig().getLong("shop.buy.ARROW_16", 0);
            if (price <= 0) return;
            if (!plugin.econ().has(p, price)) { p.sendMessage("§cНедостаточно денег."); return; }
            plugin.econ().take(p, price);
            p.getInventory().addItem(new ItemStack(Material.ARROW, 16));
            p.sendMessage("§bКуплено: §fстрелы x16 §7за §e" + price);
            open(p);
        }

        if (slot == 23 && clicked.getType() == Material.COOKED_BEEF) {
            long price = plugin.getConfig().getLong("shop.buy.COOKED_BEEF_16", 0);
            if (price <= 0) return;
            if (!plugin.econ().has(p, price)) { p.sendMessage("§cНедостаточно денег."); return; }
            plugin.econ().take(p, price);
            p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
            p.sendMessage("§bКуплено: §fеда x16 §7за §e" + price);
            open(p);
        }
    }

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
        return m.name().toLowerCase().replace('_', ' ');
    }
}
