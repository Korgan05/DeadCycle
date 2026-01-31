package me.korgan.deadcycle.base;

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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class BaseUpgradeGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private final String TITLE = "§9Улучшения базы";

    public BaseUpgradeGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        // фон
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, pane());
        }

        inv.setItem(11, wallItem());
        inv.setItem(15, repairItem());

        p.openInventory(inv);
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getRawSlot();

        if (slot == 11) {
            buyWall(p);
            open(p);
        }

        if (slot == 15) {
            buyRepair(p);
            open(p);
        }
    }

    private void buyWall(Player p) {
        BaseUpgradeManager u = plugin.upgrades();

        if (!u.canUpgradeWall()) {
            p.sendMessage(ChatColor.RED + "Укрепление стен уже максимальное.");
            return;
        }

        int cost = u.wallCost();
        if (!plugin.baseResources().spendPoints(cost)) {
            p.sendMessage(ChatColor.RED + "Недостаточно очков базы.");
            return;
        }

        u.upgradeWall();
        success(p, "Укрепление стен улучшено!");
    }

    private void buyRepair(Player p) {
        BaseUpgradeManager u = plugin.upgrades();

        if (!u.canUpgradeRepair()) {
            p.sendMessage(ChatColor.RED + "Скорость ремонта уже максимальная.");
            return;
        }

        int cost = u.repairCost();
        if (!plugin.baseResources().spendPoints(cost)) {
            p.sendMessage(ChatColor.RED + "Недостаточно очков базы.");
            return;
        }

        u.upgradeRepair();
        success(p, "Скорость ремонта увеличена!");
    }

    private ItemStack wallItem() {
        BaseUpgradeManager u = plugin.upgrades();

        ItemStack it = new ItemStack(Material.OBSIDIAN);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§bУкрепление стен");
        m.setLore(java.util.List.of(
                "§7Уровень: §f" + u.getWallLevel() + "/" + u.getWallMax(),
                "§7HP множитель: §a" + String.format("%.2f", u.wallHpMultiplier()),
                " ",
                "§eЦена: §6" + u.wallCost() + " очков базы",
                "§8Нажми чтобы улучшить"
        ));
        it.setItemMeta(m);
        return it;
    }

    private ItemStack repairItem() {
        BaseUpgradeManager u = plugin.upgrades();

        ItemStack it = new ItemStack(Material.ANVIL);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§aСкорость ремонта");
        m.setLore(java.util.List.of(
                "§7Уровень: §f" + u.getRepairLevel() + "/" + u.getRepairMax(),
                "§7Множитель: §a" + String.format("%.2f", u.repairMultiplier()),
                " ",
                "§eЦена: §6" + u.repairCost() + " очков базы",
                "§8Нажми чтобы улучшить"
        ));
        it.setItemMeta(m);
        return it;
    }

    private ItemStack pane() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(" ");
        it.setItemMeta(m);
        return it;
    }

    private void success(Player p, String msg) {
        p.sendMessage(ChatColor.GREEN + msg);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
    }
}
