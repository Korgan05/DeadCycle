package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager.Kit;
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

public class KitMenu implements Listener {

    private final DeadCyclePlugin plugin;
    private final String title = ChatColor.DARK_GREEN + "DeadCycle: Выбор кита";

    public KitMenu(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 9, title);

        inv.setItem(1, item(Material.STONE_PICKAXE, ChatColor.GREEN + "Шахтёр"));
        inv.setItem(3, item(Material.STONE_SWORD, ChatColor.RED + "Боец"));
        inv.setItem(5, item(Material.BOW, ChatColor.YELLOW + "Лучник"));
        inv.setItem(7, item(Material.OAK_PLANKS, ChatColor.AQUA + "Строитель"));

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!title.equals(e.getView().getTitle())) return;

        e.setCancelled(true);
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() == Material.AIR) return;

        Material m = it.getType();

        if (m == Material.STONE_PICKAXE) plugin.kit().setKit(p, Kit.MINER);
        else if (m == Material.STONE_SWORD) plugin.kit().setKit(p, Kit.FIGHTER);
        else if (m == Material.BOW) plugin.kit().setKit(p, Kit.ARCHER);
        else if (m == Material.OAK_PLANKS) plugin.kit().setKit(p, Kit.BUILDER);
        else return;

        p.closeInventory();
    }

    private ItemStack item(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }
}
