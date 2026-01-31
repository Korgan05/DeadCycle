package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RepairGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private final BlockHealthManager blocks;
    private final Map<UUID, BukkitTask> repairing = new HashMap<>();

    private final String TITLE = "§aРемонт базы";

    public RepairGUI(DeadCyclePlugin plugin, BlockHealthManager blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);
        for (int i = 0; i < 27; i++) inv.setItem(i, pane());

        inv.setItem(13, button());
        p.openInventory(inv);
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getRawSlot() == 13) {
            toggleRepair(p);
        }
    }

    private void toggleRepair(Player p) {
        if (repairing.containsKey(p.getUniqueId())) {
            stop(p);
            return;
        }

        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.BUILDER) {
            p.sendMessage(ChatColor.RED + "Только BUILDER может чинить базу.");
            return;
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p), 0L, 20L);
        repairing.put(p.getUniqueId(), task);
        p.sendMessage(ChatColor.GREEN + "Ремонт начался...");
    }

    private void tick(Player p) {
        if (!p.isOnline() || !plugin.base().isOnBase(p.getLocation())) {
            stop(p);
            return;
        }

        int cost = plugin.getConfig().getInt("repair_cost.points_per_tick", 10);
        if (!plugin.baseResources().spendPoints(cost)) {
            p.sendMessage(ChatColor.RED + "Очки базы закончились!");
            stop(p);
            return;
        }

        double mult = plugin.upgrades().repairMultiplier();
        int amount = (int) Math.round(plugin.getConfig().getInt("builder.repair_amount", 8) * mult);

        Block b = p.getTargetBlockExact(5);
        if (b == null) return;
        if (!plugin.base().isOnBase(b.getLocation())) return;

        blocks.repair(b, amount);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.3f, 1.2f);
    }

    private void stop(Player p) {
        BukkitTask t = repairing.remove(p.getUniqueId());
        if (t != null) t.cancel();
        p.sendMessage(ChatColor.YELLOW + "Ремонт остановлен.");
    }

    private ItemStack button() {
        ItemStack it = new ItemStack(Material.ANVIL);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§aНачать / остановить ремонт");
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
}
