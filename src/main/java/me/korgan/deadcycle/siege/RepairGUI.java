package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
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

    private static final String TITLE = ChatColor.DARK_GREEN + "Ремонт базы";

    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, Integer> sessionTotal = new HashMap<>();
    private final Map<UUID, Integer> sessionRepaired = new HashMap<>();

    public RepairGUI(DeadCyclePlugin plugin, BlockHealthManager blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);
        build(inv, p);
        p.openInventory(inv);
    }

    private void build(Inventory inv, Player p) {
        inv.clear();

        inv.setItem(11, item(Material.ANVIL, ChatColor.GREEN + "Начать / Остановить ремонт",
                ChatColor.GRAY + "Чинит базу автоматически.",
                ChatColor.GRAY + "Сломано: " + ChatColor.RED + "красный",
                ChatColor.GRAY + "Повреждено: " + ChatColor.GOLD + "оранжевый",
                ChatColor.GRAY + "Чинится: " + ChatColor.WHITE + "белый"));

        inv.setItem(15, item(Material.BARRIER, ChatColor.RED + "Закрыть"));
        inv.setItem(13, makeInfoItem(p));
    }

    private ItemStack makeInfoItem(Player p) {
        Location center = plugin.base().getCenter();
        int radius = plugin.base().getRadius();

        int broken = blocks.getBrokenCountOnBase(center, radius);
        int damaged = blocks.getDamagedCountOnBase(center, radius);
        int missing = blocks.getTotalMissingHpOnBase(center, radius);

        int total = sessionTotal.getOrDefault(p.getUniqueId(), Math.max(1, missing));
        int repaired = sessionRepaired.getOrDefault(p.getUniqueId(), 0);

        double percent = Math.min(100.0, (repaired * 100.0) / Math.max(1, total));
        boolean running = tasks.containsKey(p.getUniqueId());

        return item(Material.PAPER,
                ChatColor.YELLOW + "Статус ремонта",
                ChatColor.GRAY + "Сломано: " + ChatColor.RED + broken,
                ChatColor.GRAY + "Повреждено: " + ChatColor.GOLD + damaged,
                ChatColor.GRAY + "Не хватает HP: " + ChatColor.WHITE + missing,
                "",
                ChatColor.GRAY + "Прогресс сессии: " + ChatColor.AQUA + String.format(Locale.US, "%.1f%%", percent),
                ChatColor.GRAY + "Очки базы: " + ChatColor.WHITE + plugin.baseResources().getBasePoints(),
                ChatColor.GRAY + "Режим: "
                        + (running ? (ChatColor.GREEN + "Ремонт идёт") : (ChatColor.RED + "Остановлен")));
    }

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore != null && lore.length > 0)
                im.setLore(Arrays.asList(lore));
            it.setItemMeta(im);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;

        String title = e.getView().getTitle();
        if (title == null)
            return;
        if (!ChatColor.stripColor(title).equalsIgnoreCase(ChatColor.stripColor(TITLE)))
            return;

        e.setCancelled(true);

        if (e.getCurrentItem() == null)
            return;
        int slot = e.getRawSlot();

        if (slot == 15) {
            p.closeInventory();
            return;
        }

        if (slot == 11) {
            toggleRepair(p);
            build(e.getInventory(), p);
        }
    }

    private void toggleRepair(Player p) {
        UUID id = p.getUniqueId();

        if (tasks.containsKey(id)) {
            stopRepair(p, ChatColor.YELLOW + "Ремонт остановлен.");
            return;
        }

        if (!plugin.base().isEnabled()) {
            p.sendMessage(ChatColor.RED + "База выключена в конфиге.");
            return;
        }

        if (!plugin.base().isOnBase(p.getLocation())) {
            p.sendMessage(ChatColor.RED + "Ты должен быть на базе, чтобы чинить.");
            return;
        }

        Location center = plugin.base().getCenter();
        int radius = plugin.base().getRadius();

        int missing = blocks.getTotalMissingHpOnBase(center, radius);
        if (missing <= 0) {
            p.sendMessage(ChatColor.GREEN + "На базе нечего чинить.");
            return;
        }

        sessionTotal.put(id, missing);
        sessionRepaired.put(id, 0);

        int tickPeriod = plugin.getConfig().getInt("repair_gui.tick_period_ticks", 20);
        if (tickPeriod < 5)
            tickPeriod = 5;

        // включаем “глобальный режим ремонта”, чтобы сломанные/поврежденные стали
        // белыми пока чинятся
        blocks.addActiveRepairer(id);
        updateGlobalRepairMode();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p), 0L, tickPeriod);
        tasks.put(id, task);

        p.sendMessage(ChatColor.GREEN + "Починка началась.");
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.25f, 1.2f);
    }

    private void tick(Player p) {
        UUID id = p.getUniqueId();

        if (!p.isOnline()) {
            stopRepairSilent(id);
            return;
        }

        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            stopRepair(p, ChatColor.RED + "Ты вышел с базы — ремонт остановлен.");
            return;
        }

        Location center = plugin.base().getCenter();
        int radius = plugin.base().getRadius();

        int missing = blocks.getTotalMissingHpOnBase(center, radius);
        if (missing <= 0) {
            stopRepair(p, ChatColor.GREEN + "Ремонт завершён! Всё целое.");
            return;
        }

        int wallLevel = plugin.getConfig().getInt("base.wall_level", 1);

        int pointsPerTick = getPointsPerTickForWallLevel(wallLevel);
        if (pointsPerTick < 1)
            pointsPerTick = 1;

        // корректное списание очков базы
        if (!plugin.baseResources().spendPoints(pointsPerTick)) {
            stopRepair(p, ChatColor.RED + "Недостаточно очков базы для ремонта.");
            return;
        }

        int amount = plugin.getConfig().getInt("builder.repair_amount", 8);
        if (amount <= 0)
            amount = 8;

        // автоматический ремонт по базе (сломанные приоритетнее)
        int repairedNow = blocks.repairAnyOnBase(center, radius, amount);

        if (repairedNow <= 0) {
            // чтобы не сжигать очки бесконечно
            stopRepair(p, ChatColor.RED + "Ремонт не может продолжаться (нет подходящих блоков).");
            return;
        }

        sessionRepaired.put(id, sessionRepaired.getOrDefault(id, 0) + repairedNow);

        // XP билдера за реальный ремонт
        if (plugin.kit().getKit(id) == KitManager.Kit.BUILDER) {
            int exp = plugin.getConfig().getInt("kit_xp.builder.exp_per_repair_tick", 1);
            if (exp > 0)
                plugin.progress().addBuilderExp(p, exp);
        }

        int total = sessionTotal.getOrDefault(id, 1);
        int rep = sessionRepaired.getOrDefault(id, 0);
        double percent = Math.min(100.0, (rep * 100.0) / Math.max(1, total));

        p.sendActionBar(ChatColor.GREEN + "Ремонт: " + ChatColor.AQUA + String.format(Locale.US, "%.1f%%", percent)
                + ChatColor.GRAY + " | " + ChatColor.YELLOW + "Очки базы: " + ChatColor.WHITE
                + plugin.baseResources().getBasePoints());

        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.18f, 1.6f);

        // если GUI открыт — обновим инфо
        String t = p.getOpenInventory() != null ? p.getOpenInventory().getTitle() : null;
        if (t != null && ChatColor.stripColor(t).equalsIgnoreCase(ChatColor.stripColor(TITLE))) {
            Inventory inv = p.getOpenInventory().getTopInventory();
            inv.setItem(13, makeInfoItem(p));
        }
    }

    private int getPointsPerTickForWallLevel(int wallLevel) {
        String byLevel = "repair_cost.points_per_tick_by_wall_level.l" + wallLevel;
        if (plugin.getConfig().isInt(byLevel)) {
            int v = plugin.getConfig().getInt(byLevel);
            return Math.max(1, v);
        }

        int fallback = plugin.getConfig().getInt("repair_cost.points_per_tick", 10);
        return Math.max(1, fallback);
    }

    private void stopRepair(Player p, String msg) {
        UUID id = p.getUniqueId();
        BukkitTask t = tasks.remove(id);
        if (t != null)
            t.cancel();

        blocks.removeActiveRepairer(id);
        updateGlobalRepairMode();

        p.sendMessage(msg);

        String title = p.getOpenInventory() != null ? p.getOpenInventory().getTitle() : null;
        if (title != null && ChatColor.stripColor(title).equalsIgnoreCase(ChatColor.stripColor(TITLE))) {
            Inventory inv = p.getOpenInventory().getTopInventory();
            inv.setItem(13, makeInfoItem(p));
        }
    }

    private void stopRepairSilent(UUID id) {
        BukkitTask t = tasks.remove(id);
        if (t != null)
            t.cancel();

        blocks.removeActiveRepairer(id);
        updateGlobalRepairMode();
    }

    private void updateGlobalRepairMode() {
        // глобальный режим ремонта включаем если хотя бы один ремонтник активен
        blocks.setGlobalRepairMode(blocks.hasActiveRepairers());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player))
            return;

        String title = e.getView().getTitle();
        if (title == null)
            return;
        if (!ChatColor.stripColor(title).equalsIgnoreCase(ChatColor.stripColor(TITLE)))
            return;

        // ремонт НЕ останавливаем при закрытии (по твоей логике)
    }
}
