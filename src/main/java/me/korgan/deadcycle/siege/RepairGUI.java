package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class RepairGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private final BlockHealthManager blocks;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Component TITLE = LEGACY.deserialize("§2Ремонт базы");

    private static final class RepairGuiHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, Integer> sessionTotal = new HashMap<>();
    private final Map<UUID, Integer> sessionRepaired = new HashMap<>();

    public RepairGUI(DeadCyclePlugin plugin, BlockHealthManager blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    public void open(Player p) {
        RepairGuiHolder holder = new RepairGuiHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE);
        holder.setInventory(inv);
        build(inv, p);
        p.openInventory(inv);
    }

    private boolean isRepairInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof RepairGuiHolder;
    }

    private void build(Inventory inv, Player p) {
        inv.clear();

        inv.setItem(11, item(Material.ANVIL, "§aНачать / Остановить ремонт",
                "§7Чинит базу автоматически.",
                "§7Сломано: §cкрасный",
                "§7Повреждено: §6оранжевый",
                "§7Чинится: §fбелый"));

        inv.setItem(15, item(Material.BARRIER, "§cЗакрыть"));
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
                "§eСтатус ремонта",
                "§7Сломано: §c" + broken,
                "§7Повреждено: §6" + damaged,
                "§7Не хватает HP: §f" + missing,
                "",
                "§7Прогресс сессии: §b" + String.format(Locale.US, "%.1f%%", percent),
                "§7Очки базы: §f" + plugin.baseResources().getBasePoints(),
                "§7Режим: " + (running ? "§aРемонт идёт" : "§cОстановлен"));
    }

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(LEGACY.deserialize(name));
            if (lore != null && lore.length > 0) {
                List<Component> loreComponents = new ArrayList<>(lore.length);
                for (String line : lore) {
                    loreComponents.add(LEGACY.deserialize(line));
                }
                im.lore(loreComponents);
            }
            it.setItemMeta(im);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;

        Inventory top = e.getView().getTopInventory();
        if (!isRepairInventory(top))
            return;

        if (e.getRawSlot() < 0 || e.getRawSlot() >= top.getSize())
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
            build(top, p);
        }
    }

    private void toggleRepair(Player p) {
        UUID id = p.getUniqueId();

        if (tasks.containsKey(id)) {
            stopRepair(p, "§eРемонт остановлен.");
            return;
        }

        if (!plugin.base().isEnabled()) {
            p.sendMessage("§cБаза выключена в конфиге.");
            return;
        }

        if (!plugin.base().isOnBase(p.getLocation())) {
            p.sendMessage("§cТы должен быть на базе, чтобы чинить.");
            return;
        }

        Location center = plugin.base().getCenter();
        int radius = plugin.base().getRadius();

        int missing = blocks.getTotalMissingHpOnBase(center, radius);
        if (missing <= 0) {
            p.sendMessage("§aНа базе нечего чинить.");
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

        p.sendMessage("§aПочинка началась.");
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.25f, 1.2f);
    }

    private void tick(Player p) {
        UUID id = p.getUniqueId();

        if (!p.isOnline()) {
            stopRepairSilent(id);
            return;
        }

        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            stopRepair(p, "§cТы вышел с базы — ремонт остановлен.");
            return;
        }

        Location center = plugin.base().getCenter();
        int radius = plugin.base().getRadius();

        int missing = blocks.getTotalMissingHpOnBase(center, radius);
        if (missing <= 0) {
            stopRepair(p, "§aРемонт завершён! Всё целое.");
            return;
        }

        int wallLevel = plugin.getConfig().getInt("base.wall_level", 1);

        int pointsPerTick = getPointsPerTickForWallLevel(wallLevel);
        if (pointsPerTick < 1)
            pointsPerTick = 1;

        // корректное списание очков базы
        if (!plugin.baseResources().spendPoints(pointsPerTick)) {
            stopRepair(p, "§cНедостаточно очков базы для ремонта.");
            return;
        }

        int amount = plugin.getConfig().getInt("builder.repair_amount", 8);
        if (amount <= 0)
            amount = 8;

        // автоматический ремонт по базе (сломанные приоритетнее)
        int repairedNow = blocks.repairAnyOnBase(center, radius, amount);

        if (repairedNow <= 0) {
            // чтобы не сжигать очки бесконечно
            stopRepair(p, "§cРемонт не может продолжаться (нет подходящих блоков).");
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

        String actionBar = "§aРемонт: §b" + String.format(Locale.US, "%.1f%%", percent)
                + "§7 | §eОчки базы: §f" + plugin.baseResources().getBasePoints();
        p.sendActionBar(LEGACY.deserialize(actionBar));

        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.18f, 1.6f);

        // если GUI открыт — обновим инфо
        Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
        if (isRepairInventory(top)) {
            top.setItem(13, makeInfoItem(p));
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

        Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
        if (isRepairInventory(top)) {
            top.setItem(13, makeInfoItem(p));
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
        if (!isRepairInventory(e.getView().getTopInventory()))
            return;

        // ремонт НЕ останавливаем при закрытии (по твоей логике)
    }
}
