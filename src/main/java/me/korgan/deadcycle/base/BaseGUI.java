package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.phase.PhaseManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Locale;

public class BaseGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Component TITLE = LEGACY.deserialize("§2DeadCycle • База");

    private static final class BaseGuiHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    // слоты кнопок
    private static final int SLOT_INFO = 11;
    private static final int SLOT_REPAIR = 13;
    private static final int SLOT_SHOP = 15;
    private static final int SLOT_UPGRADES = 22;

    public BaseGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        BaseGuiHolder holder = new BaseGuiHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE);
        holder.setInventory(inv);

        // рамка
        for (int i = 0; i < inv.getSize(); i++)
            inv.setItem(i, pane());

        // инфо (ресурсы базы + игроки на базе + фаза)
        inv.setItem(SLOT_INFO, infoItem(p));

        // ремонт (builder only)
        inv.setItem(SLOT_REPAIR, button(Material.ANVIL,
                "§bРемонт базы",
                "§7Открыть меню ремонта",
                "§7Только для билдера"));

        // магазин
        inv.setItem(SLOT_SHOP, button(Material.EMERALD,
                "§6Магазин",
                "§7Открыть магазин (/shop)",
                "§7Только на базе"));

        // апгрейды
        inv.setItem(SLOT_UPGRADES, button(Material.NETHER_STAR,
                "§dПрокачка стен",
                "§7Открыть меню улучшения стен",
                "§7Только для билдера"));

        p.openInventory(inv);
    }

    private boolean isBaseInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof BaseGuiHolder;
    }

    private ItemStack infoItem(Player p) {
        BaseResourceManager br = plugin.baseResources();

        long total = br.getBasePoints();
        long stone = br.getPoints(BaseResourceManager.ResourceType.STONE);
        long coal = br.getPoints(BaseResourceManager.ResourceType.COAL);
        long iron = br.getPoints(BaseResourceManager.ResourceType.IRON);
        long dia = br.getPoints(BaseResourceManager.ResourceType.DIAMOND);

        int onBase = plugin.base().countOnBase();

        PhaseManager pm = plugin.phase();
        String phase = (pm == null) ? "-" : pm.getPhase().name();
        int day = (pm == null) ? 0 : pm.getDayCount();

        long money = plugin.econ().getMoney(p.getUniqueId());
        KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
        String kitName = (kit == null) ? "-" : kit.name();

        int wallLevel = plugin.getConfig().getInt("base.wall_level", 1);
        int maxWallLevel = plugin.getConfig().getInt("wall_upgrade.max_level", 3);
        String wallNext = "§cнедоступно";
        if (wallLevel < maxWallLevel) {
            Material nextMat = getWallMaterialForLevel(wallLevel + 1);
            wallNext = "§b" + String.valueOf(wallLevel + 1)
                    + "§7 (§f" + prettyMat(nextMat) + "§7)";
        }

        return button(Material.BOOK,
                "§aСтатус базы",
                "§eНа базе: §f" + onBase,
                "§bФаза: §f" + phase + "§7 | День: §f"
                        + day,
                "§6Твои деньги: §f" + money,
                "§9Твой кит: §f" + kitName,
                " ",
                "§aСтены: §f" + wallLevel + "§7 / §f"
                        + maxWallLevel,
                "§7След. уровень: " + wallNext,
                " ",
                "§bОчки базы: §f" + total,
                "§7Камень: " + stone,
                "§7Уголь: " + coal,
                "§7Железо: " + iron,
                "§7Алмазы: " + dia);
    }

    private Material getWallMaterialForLevel(int level) {
        String key = "wall_upgrade.levels.l" + level;
        String raw = plugin.getConfig().getString(key);
        Material mat = (raw == null) ? null : Material.matchMaterial(raw);
        if (mat != null)
            return mat;

        return switch (level) {
            case 1 -> Material.OAK_PLANKS;
            case 2 -> Material.SPRUCE_PLANKS;
            case 3 -> Material.COBBLESTONE;
            case 4 -> Material.STONE;
            case 5 -> Material.STONE_BRICKS;
            default -> Material.OAK_PLANKS;
        };
    }

    private String prettyMat(Material m) {
        return m.name().toLowerCase(Locale.ROOT);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isBaseInventory(top))
            return;
        if (e.getRawSlot() < 0 || e.getRawSlot() >= top.getSize())
            return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p))
            return;

        // безопасность: /base только на базе
        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            p.closeInventory();
            p.sendMessage("§cМеню базы доступно только внутри базы!");
            return;
        }

        int slot = e.getRawSlot();

        if (slot == SLOT_REPAIR) {
            // builder only
            KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
            if (kit != KitManager.Kit.BUILDER) {
                p.sendMessage("§cРемонт доступен только киту BUILDER.");
                return;
            }
            plugin.repairGui().open(p);
            return;
        }

        if (slot == SLOT_SHOP) {
            // /shop уже проверяет базу, но тут тоже ок
            plugin.shopGui().open(p);
            return;
        }

        if (slot == SLOT_UPGRADES) {
            plugin.wallUpgradeGui().open(p);
        }

        if (slot == SLOT_INFO) {
            // обновим инфо
            open(p);
        }
    }

    private ItemStack pane() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name));
            ArrayList<Component> loreComponents = new ArrayList<>(lore.length);
            for (String line : lore) {
                loreComponents.add(LEGACY.deserialize(line));
            }
            meta.lore(loreComponents);
            it.setItemMeta(meta);
        }
        return it;
    }
}
