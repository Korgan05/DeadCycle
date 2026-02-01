package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class RepairGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private final BlockHealthManager blocks;

    private final Map<UUID, BukkitTask> repairing = new HashMap<>();
    private final Map<UUID, Integer> sessionRepaired = new HashMap<>();
    private final Map<UUID, Integer> sessionStartMissing = new HashMap<>();

    private static final String TITLE = "§aРемонт базы";

    public RepairGUI(DeadCyclePlugin plugin, BlockHealthManager blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);
        for (int i = 0; i < 27; i++) inv.setItem(i, pane());

        inv.setItem(11, statsItem(p));
        inv.setItem(13, button(p));
        inv.setItem(15, hintItem());

        p.openInventory(inv);
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getRawSlot() == 13) {
            toggleRepair(p);
            open(p);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!TITLE.equals(e.getView().getTitle())) return;
        if (e.getPlayer() instanceof Player p) {
            stop(p);
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

        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            p.sendMessage(ChatColor.RED + "Ремонт доступен только на базе.");
            return;
        }

        sessionRepaired.put(p.getUniqueId(), 0);
        int startMissing = blocks.getTotalMissingHp();
        sessionStartMissing.put(p.getUniqueId(), Math.max(1, startMissing));

        int tickPeriod = Math.max(5, plugin.getConfig().getInt("repair_gui.tick_period_ticks", 20));

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p), 0L, tickPeriod);
        repairing.put(p.getUniqueId(), task);

        p.sendMessage(ChatColor.GREEN + "Ремонт начался...");
    }

    private void tick(Player p) {
        if (!p.isOnline()) {
            stop(p);
            return;
        }

        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            stop(p);
            return;
        }

        int missing = blocks.getTotalMissingHp();
        if (missing <= 0) {
            p.sendMessage(ChatColor.GREEN + "Все блоки уже целые!");
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

        int beforeMissing = blocks.getTotalMissingHp();
        blocks.repair(b, amount);
        int afterMissing = blocks.getTotalMissingHp();

        int repairedNow = Math.max(0, beforeMissing - afterMissing);
        sessionRepaired.put(p.getUniqueId(), sessionRepaired.getOrDefault(p.getUniqueId(), 0) + repairedNow);

        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.25f, 1.2f);

        // прогресс шкала в actionbar
        int start = sessionStartMissing.getOrDefault(p.getUniqueId(), Math.max(1, beforeMissing));
        int done = Math.max(0, start - afterMissing);
        double pct = Math.max(0.0, Math.min(1.0, done / (double) start));

        String bar = progressBar(pct, 20);
        String msg = "§aРемонт: §f" + bar + " §7" + (int) Math.round(pct * 100) + "% §7(осталось HP: §f" + afterMissing + "§7)";
        p.sendActionBar(Component.text(msg.replace('§', '\u00A7')));

        // обновим GUI
        if (p.getOpenInventory() != null && TITLE.equals(p.getOpenInventory().getTitle())) {
            Inventory inv = p.getOpenInventory().getTopInventory();
            inv.setItem(11, statsItem(p));
            inv.setItem(13, button(p));
        }
    }

    private void stop(Player p) {
        BukkitTask t = repairing.remove(p.getUniqueId());
        if (t != null) t.cancel();
        if (t != null) p.sendMessage(ChatColor.YELLOW + "Ремонт остановлен.");
        sessionStartMissing.remove(p.getUniqueId());
    }

    private ItemStack statsItem(Player p) {
        int damaged = blocks.getDamagedBlocksCount();
        int missing = blocks.getTotalMissingHp();
        int done = sessionRepaired.getOrDefault(p.getUniqueId(), 0);

        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName("§eСтатус ремонта");

            List<String> lore = new ArrayList<>();
            lore.add("§7Повреждено/сломано: §f" + damaged);
            lore.add("§7Осталось чинить (HP): §f" + missing);
            lore.add("§7Сделано за сессию: §a" + done);

            long points = plugin.baseResources().getBasePoints();
            lore.add(" ");
            lore.add("§bОчки базы: §f" + points);

            m.setLore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack button(Player p) {
        boolean active = repairing.containsKey(p.getUniqueId());

        ItemStack it = new ItemStack(active ? Material.REDSTONE_BLOCK : Material.LIME_WOOL);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(active ? "§cОстановить ремонт" : "§aНачать ремонт");
            m.setLore(Collections.singletonList(active
                    ? "§7Ремонт идёт... (выход из меню остановит ремонт)"
                    : "§7Нажми чтобы начать ремонт"));
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack hintItem() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName("§fКак чинить?");
            m.setLore(Arrays.asList(
                    "§7Смотри на поврежденный блок",
                    "§7или на место сломанного блока (AIR).",
                    "§7Ремонт тратит очки базы."
            ));
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack pane() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(" ");
            it.setItemMeta(m);
        }
        return it;
    }

    private String progressBar(double pct, int len) {
        int filled = (int) Math.round(pct * len);
        filled = Math.max(0, Math.min(len, filled));

        StringBuilder sb = new StringBuilder();
        sb.append("§a");
        for (int i = 0; i < filled; i++) sb.append("■");
        sb.append("§7");
        for (int i = filled; i < len; i++) sb.append("■");
        return sb.toString();
    }
}
