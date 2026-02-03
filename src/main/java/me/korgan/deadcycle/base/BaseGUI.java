package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.phase.PhaseManager;
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
import java.util.Locale;

public class BaseGUI implements Listener {

    private final DeadCyclePlugin plugin;
    private final String title = ChatColor.DARK_GREEN + "DeadCycle • База";

    // слоты кнопок
    private static final int SLOT_INFO = 11;
    private static final int SLOT_REPAIR = 13;
    private static final int SLOT_SHOP = 15;
    private static final int SLOT_UPGRADES = 22; // пока заглушка

    public BaseGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, title);

        // рамка
        for (int i = 0; i < inv.getSize(); i++)
            inv.setItem(i, pane());

        // инфо (ресурсы базы + игроки на базе + фаза)
        inv.setItem(SLOT_INFO, infoItem(p));

        // ремонт (builder only)
        inv.setItem(SLOT_REPAIR, button(Material.ANVIL,
                ChatColor.AQUA + "Ремонт базы",
                ChatColor.GRAY + "Открыть меню ремонта",
                ChatColor.GRAY + "Только для билдера"));

        // магазин
        inv.setItem(SLOT_SHOP, button(Material.EMERALD,
                ChatColor.GOLD + "Магазин",
                ChatColor.GRAY + "Открыть магазин (/shop)",
                ChatColor.GRAY + "Только на базе"));

        // апгрейды (пока заглушка в пакете 2 сделаем полностью)
        inv.setItem(SLOT_UPGRADES, button(Material.NETHER_STAR,
                ChatColor.LIGHT_PURPLE + "Апгрейды базы",
                ChatColor.GRAY + "Будет в пакете v0.6-2",
                ChatColor.GRAY + "Скорость ремонта / HP стен"));

        p.openInventory(inv);
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
        String wallNext = ChatColor.RED + "недоступно";
        if (wallLevel < maxWallLevel) {
            Material nextMat = getWallMaterialForLevel(wallLevel + 1);
            wallNext = ChatColor.AQUA + String.valueOf(wallLevel + 1)
                    + ChatColor.GRAY + " (" + ChatColor.WHITE + prettyMat(nextMat) + ChatColor.GRAY + ")";
        }

        return button(Material.BOOK,
                ChatColor.GREEN + "Статус базы",
                ChatColor.YELLOW + "На базе: " + ChatColor.WHITE + onBase,
                ChatColor.AQUA + "Фаза: " + ChatColor.WHITE + phase + ChatColor.GRAY + " | День: " + ChatColor.WHITE
                        + day,
                ChatColor.GOLD + "Твои деньги: " + ChatColor.WHITE + money,
                ChatColor.BLUE + "Твой кит: " + ChatColor.WHITE + kitName,
                " ",
                ChatColor.GREEN + "Стены: " + ChatColor.WHITE + wallLevel + ChatColor.GRAY + " / " + ChatColor.WHITE
                        + maxWallLevel,
                ChatColor.GRAY + "След. уровень: " + wallNext,
                " ",
                ChatColor.AQUA + "Очки базы: " + ChatColor.WHITE + total,
                ChatColor.GRAY + "Камень: " + stone,
                ChatColor.GRAY + "Уголь: " + coal,
                ChatColor.GRAY + "Железо: " + iron,
                ChatColor.GRAY + "Алмазы: " + dia);
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
        if (!title.equals(e.getView().getTitle()))
            return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p))
            return;

        // безопасность: /base только на базе
        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(p.getLocation())) {
            p.closeInventory();
            p.sendMessage(ChatColor.RED + "Меню базы доступно только внутри базы!");
            return;
        }

        int slot = e.getRawSlot();

        if (slot == SLOT_REPAIR) {
            // builder only
            KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
            if (kit != KitManager.Kit.BUILDER) {
                p.sendMessage(ChatColor.RED + "Ремонт доступен только киту BUILDER.");
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
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Апгрейды будут в пакете v0.6-2.");
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
            meta.setDisplayName(" ");
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            it.setItemMeta(meta);
        }
        return it;
    }
}
