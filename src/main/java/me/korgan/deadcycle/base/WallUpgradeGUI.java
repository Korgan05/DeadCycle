package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
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

public class WallUpgradeGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Component TITLE = LEGACY.deserialize("§3Прокачка стен");

    private static final class WallUpgradeGuiHolder implements InventoryHolder {
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
    private final Map<UUID, Integer> sessionDone = new HashMap<>();
    private final Map<UUID, List<Location>> targets = new HashMap<>();
    private final Map<UUID, Integer> index = new HashMap<>();
    private final Map<UUID, Integer> targetLevel = new HashMap<>();

    public WallUpgradeGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        WallUpgradeGuiHolder holder = new WallUpgradeGuiHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE);
        holder.setInventory(inv);
        build(inv, p);
        p.openInventory(inv);
    }

    private boolean isWallUpgradeInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof WallUpgradeGuiHolder;
    }

    private void build(Inventory inv, Player p) {
        inv.clear();

        inv.setItem(11, item(Material.SMITHING_TABLE, "§bНачать прокачку",
                "§7Прокачивает стены по 1 блоку.",
                "§7Тратит очки базы.",
                "§8Только для билдера"));

        inv.setItem(15, item(Material.BARRIER, "§cЗакрыть"));
        inv.setItem(13, makeInfo(p));
    }

    private ItemStack makeInfo(Player p) {
        UUID id = p.getUniqueId();

        int current = plugin.getConfig().getInt("base.wall_level", 1);
        int maxLevel = plugin.getConfig().getInt("wall_upgrade.max_level", 3);

        boolean running = tasks.containsKey(id);
        int total = sessionTotal.getOrDefault(id, 0);
        int done = sessionDone.getOrDefault(id, 0);

        double percent = 0;
        if (total > 0)
            percent = Math.min(100.0, (done * 100.0) / total);

        String nextInfo = "§7След. уровень: §cнедоступно";
        if (current < maxLevel) {
            Material to = getWallMaterialForLevel(current + 1);
            nextInfo = "§7След. уровень: §b" + (current + 1)
                    + "§7 (§f" + prettyMat(to) + "§7)";
        }

        return item(Material.PAPER,
                "§eСтатус прокачки",
                "§7Текущий уровень стен: §a" + current,
                nextInfo,
                "",
                "§7Прогресс сессии: §b" + String.format(Locale.US, "%.1f%%", percent),
                "§7Прокачено: §f" + done + "§7 / §f" + total,
                "§7Очки базы: §f" + plugin.baseResources().getPoints(),
                "§7Режим: " + (running ? "§aидёт" : "§cостановлен"));
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

    private Material getWallMaterialForLevel(int level) {
        String key = "wall_upgrade.levels.l" + level;
        String raw = plugin.getConfig().getString(key);
        Material mat = (raw == null) ? null : Material.matchMaterial(raw);
        if (mat != null)
            return mat;

        // Безопасные дефолты: нужны для старых/сломанных конфигов,
        // иначе меню показывает дуб и блоки не меняются визуально.
        return switch (level) {
            case 1 -> Material.OAK_PLANKS;
            case 2 -> Material.SPRUCE_PLANKS;
            case 3 -> Material.COBBLESTONE;
            case 4 -> Material.STONE;
            case 5 -> Material.STONE_BRICKS;
            default -> Material.OAK_PLANKS;
        };
    }

    private int getPointsPerBlockForLevel(int toLevel) {
        String byLevel = "wall_upgrade.points_per_block_by_level.l" + toLevel;
        if (plugin.getConfig().isInt(byLevel)) {
            int v = plugin.getConfig().getInt(byLevel);
            return Math.max(1, v);
        }

        int fallback = plugin.getConfig().getInt("wall_upgrade.points_per_block", 15);
        return Math.max(1, fallback);
    }

    private String prettyMat(Material m) {
        return m.name().toLowerCase(Locale.ROOT);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;

        Inventory top = e.getView().getTopInventory();
        if (!isWallUpgradeInventory(top))
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
            toggle(p);
            build(top, p);
        }
    }

    private void toggle(Player p) {
        UUID id = p.getUniqueId();

        if (plugin.kit().getKit(id) != KitManager.Kit.BUILDER) {
            p.sendMessage("§cЭто меню только для билдера.");
            return;
        }

        if (tasks.containsKey(id)) {
            stop(p, "§eПрокачка стен остановлена.");
            return;
        }

        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            p.sendMessage("§cТы должен быть на базе, чтобы прокачивать стены.");
            return;
        }

        // Нельзя прокачивать стены, пока есть повреждения/сломанные блоки базы.
        // Это заставляет сначала чинить базу, а потом уже улучшать.
        if (plugin.blocks() != null && plugin.base().getCenter() != null) {
            int broken = plugin.blocks().getBrokenCountOnBase(plugin.base().getCenter(), plugin.base().getRadius());
            int damaged = plugin.blocks().getDamagedCountOnBase(plugin.base().getCenter(), plugin.base().getRadius());
            if (broken > 0 || damaged > 0) {
                p.sendMessage("§cНельзя прокачивать стены, пока база повреждена.");
                p.sendMessage("§7Сломано: §f" + broken + "§7, повреждено: §f" + damaged);
                return;
            }
        }

        int current = plugin.getConfig().getInt("base.wall_level", 1);
        int maxLevel = plugin.getConfig().getInt("wall_upgrade.max_level", 3);
        if (current >= maxLevel) {
            p.sendMessage("§aСтены уже максимального уровня.");
            return;
        }

        int toLevel = current + 1;
        Material from = getWallMaterialForLevel(current);
        Material to = getWallMaterialForLevel(toLevel);

        List<Location> list = scanTargets(from);
        if (list.isEmpty()) {
            p.sendMessage("§cНе найдено блоков для прокачки (ожидались " + from + ").");
            return;
        }

        targets.put(id, list);
        index.put(id, 0);
        sessionTotal.put(id, list.size());
        sessionDone.put(id, 0);
        targetLevel.put(id, toLevel);

        int tickPeriod = plugin.getConfig().getInt("wall_upgrade.tick_period_ticks", 20);
        if (tickPeriod < 5)
            tickPeriod = 5;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p, from, to), 0L, tickPeriod);
        tasks.put(id, task);

        p.sendMessage("§bПрокачка стен началась (уровень " + toLevel + ").");
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.25f, 1.2f);
    }

    private List<Location> scanTargets(Material from) {
        if (!plugin.base().isEnabled() || plugin.base().getCenter() == null)
            return Collections.emptyList();

        Location c = plugin.base().getCenter();
        World w = c.getWorld();
        if (w == null)
            return Collections.emptyList();

        int r = plugin.base().getRadius();
        int yMin = c.getBlockY() + plugin.getConfig().getInt("wall_upgrade.scan_y_min_offset", -5);
        int yMax = c.getBlockY() + plugin.getConfig().getInt("wall_upgrade.scan_y_max_offset", 15);

        int cx = c.getBlockX();
        int cz = c.getBlockZ();

        ArrayList<Location> out = new ArrayList<>(2048);

        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                double dx = (x + 0.5) - c.getX();
                double dz = (z + 0.5) - c.getZ();
                if ((dx * dx + dz * dz) > (r * r))
                    continue;

                for (int y = yMin; y <= yMax; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == from)
                        out.add(b.getLocation());
                }
            }
        }

        Collections.shuffle(out, new Random());
        return out;
    }

    private void tick(Player p, Material from, Material to) {
        UUID id = p.getUniqueId();
        if (!p.isOnline()) {
            stopSilent(id);
            return;
        }

        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            stop(p, "§cТы вышел с базы — прокачка остановлена.");
            return;
        }

        // Если во время прокачки базу повредили/сломали — останавливаем.
        if (plugin.blocks() != null && plugin.base().getCenter() != null) {
            int broken = plugin.blocks().getBrokenCountOnBase(plugin.base().getCenter(), plugin.base().getRadius());
            int damaged = plugin.blocks().getDamagedCountOnBase(plugin.base().getCenter(), plugin.base().getRadius());
            if (broken > 0 || damaged > 0) {
                stop(p, "§c"
                        + "Прокачка остановлена: сначала почини базу (есть повреждённые/сломанные блоки).");
                return;
            }
        }

        List<Location> list = targets.get(id);
        if (list == null || list.isEmpty()) {
            stop(p, "§cПрокачка остановлена (список блоков пуст).");
            return;
        }

        int idx = index.getOrDefault(id, 0);
        if (idx >= list.size()) {
            complete(p);
            return;
        }

        int builderLvl = plugin.progress().getBuilderLevel(id);
        int basePerTick = plugin.getConfig().getInt("wall_upgrade.blocks_per_tick_base", 1);
        int perLvl = plugin.getConfig().getInt("wall_upgrade.blocks_per_tick_per_builder_level", 1);
        int blocksThisTick = Math.max(1, basePerTick + Math.max(0, builderLvl - 1) * perLvl);

        int toLevel = targetLevel.getOrDefault(id, plugin.getConfig().getInt("base.wall_level", 1) + 1);
        int pointsPerBlock = getPointsPerBlockForLevel(toLevel);

        int upgradedNow = 0;

        for (int i = 0; i < blocksThisTick && idx < list.size(); i++) {
            if (plugin.baseResources().getPoints() < pointsPerBlock) {
                stop(p, "§cНедостаточно очков базы для прокачки стен.");
                return;
            }

            Location loc = list.get(idx++);
            Block b = loc.getBlock();

            if (!plugin.base().isOnBase(loc))
                continue;
            if (b.getType() != from)
                continue;

            plugin.baseResources().addPoints(-pointsPerBlock);

            // важно: сбросить состояние урона/сломано, чтобы не было "красных искр" на
            // новом блоке
            plugin.blocks().clearStateAt(loc);

            b.setType(to, false);

            upgradedNow++;
        }

        index.put(id, idx);

        if (upgradedNow > 0) {
            int done = sessionDone.getOrDefault(id, 0) + upgradedNow;
            sessionDone.put(id, done);

            int exp = plugin.getConfig().getInt("kit_xp.builder.exp_per_wall_block_upgrade", 1);
            if (exp > 0)
                plugin.progress().addBuilderExp(p, exp * upgradedNow);
        }

        int total = sessionTotal.getOrDefault(id, 1);
        int done = sessionDone.getOrDefault(id, 0);
        double percent = Math.min(100.0, (done * 100.0) / Math.max(1, total));

        String actionBar = "§bПрокачка стен: §f" + String.format(Locale.US, "%.1f%%", percent)
                + "§7 | §eОчки базы: §f" + plugin.baseResources().getPoints();
        p.sendActionBar(LEGACY.deserialize(actionBar));

        Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
        if (isWallUpgradeInventory(top)) {
            top.setItem(13, makeInfo(p));
        }
    }

    private void complete(Player p) {
        UUID id = p.getUniqueId();
        stopSilent(id);

        int toLevel = targetLevel.getOrDefault(id, plugin.getConfig().getInt("base.wall_level", 1));

        plugin.getConfig().set("base.wall_level", toLevel);
        plugin.saveConfig();

        p.sendMessage("§aПрокачка стен завершена! Новый уровень: " + toLevel);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);

        targets.remove(id);
        index.remove(id);
        sessionTotal.remove(id);
        sessionDone.remove(id);
        targetLevel.remove(id);
    }

    private void stop(Player p, String msg) {
        UUID id = p.getUniqueId();
        BukkitTask t = tasks.remove(id);
        if (t != null)
            t.cancel();
        p.sendMessage(msg);

        Inventory top = p.getOpenInventory() != null ? p.getOpenInventory().getTopInventory() : null;
        if (isWallUpgradeInventory(top)) {
            top.setItem(13, makeInfo(p));
        }
    }

    private void stopSilent(UUID id) {
        BukkitTask t = tasks.remove(id);
        if (t != null)
            t.cancel();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // не останавливаем прокачку при закрытии
    }
}
