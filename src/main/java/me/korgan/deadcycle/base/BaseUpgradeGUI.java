package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class BaseUpgradeGUI implements Listener {

    private final DeadCyclePlugin plugin;

    public BaseUpgradeGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Апгрейды базы");

        inv.setItem(11, item(Material.PAPER, ChatColor.YELLOW + "Скоро",
                ChatColor.GRAY + "Здесь будут общие апгрейды базы."));

        inv.setItem(15, item(Material.BARRIER, ChatColor.RED + "Закрыть"));

        p.openInventory(inv);
    }

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore != null && lore.length > 0) im.setLore(Arrays.asList(lore));
            it.setItemMeta(im);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title == null) return;
        if (!ChatColor.stripColor(title).equalsIgnoreCase("Апгрейды базы")) return;

        e.setCancelled(true);

        if (e.getCurrentItem() == null) return;
        if (e.getRawSlot() == 15) p.closeInventory();
    }
}
