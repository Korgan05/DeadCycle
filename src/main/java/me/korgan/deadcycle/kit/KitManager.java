package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class KitManager implements Listener {

    public enum Kit {
        FIGHTER,
        MINER,
        BUILDER,
        ARCHER,
        BERSERK
    }

    private final DeadCyclePlugin plugin;
    private final Map<UUID, Kit> kits = new HashMap<>();

    private final NamespacedKey builderRepairKey;
    private final NamespacedKey builderUpgradeKey;

    public KitManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.builderRepairKey = new NamespacedKey(plugin, "builder_repair_tool");
        this.builderUpgradeKey = new NamespacedKey(plugin, "builder_wall_upgrade_tool");
    }

    public Kit getKit(UUID id) {
        return kits.getOrDefault(id, Kit.FIGHTER);
    }

    public void setKit(UUID id, Kit kit) {
        kits.put(id, kit);
    }

    // ✅ удобная перегрузка чтобы GUI мог вызывать setKit(p, kit)
    public void setKit(Player p, Kit kit) {
        setKit(p.getUniqueId(), kit);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Восстанавливаем выбранный кит и применяем эффекты (иначе после рестарта
        // игрок остаётся с предметами кита, но логически считается FIGHTER и без
        // бафов).
        try {
            Player p = e.getPlayer();
            Kit saved = plugin.progress().getSavedKit(p.getUniqueId());
            setKit(p.getUniqueId(), saved);
            plugin.progress().applyKitEffects(p);
        } catch (Throwable ignored) {
        }
    }

    // ====== выдача китов ======

    public void giveKit(Player p, Kit kit) {
        p.getInventory().clear();
        setKit(p.getUniqueId(), kit);

        // сохраняем выбор
        try {
            plugin.progress().saveKit(p.getUniqueId(), kit);
        } catch (Throwable ignored) {
        }

        switch (kit) {
            case FIGHTER -> giveFighter(p);
            case MINER -> giveMiner(p);
            case BUILDER -> giveBuilder(p);
            case ARCHER -> giveArcher(p);
            case BERSERK -> giveBerserk(p);
        }

        // применяем эффекты/бафы кита сразу после выдачи
        try {
            plugin.progress().applyKitEffects(p);
        } catch (Throwable ignored) {
        }
    }

    private void giveFighter(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.SHIELD));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
    }

    private void giveBerserk(Player p) {
        // Стартовые предметы берсерка (пока минимально, дальше можно расширять)
        p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
    }

    private void giveMiner(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_PICKAXE));
        p.getInventory().addItem(new ItemStack(Material.TORCH, 32));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 12));
    }

    private void giveArcher(Player p) {
        p.getInventory().addItem(new ItemStack(Material.BOW));
        p.getInventory().addItem(new ItemStack(Material.ARROW, 32));
        p.getInventory().addItem(new ItemStack(Material.LEATHER_CHESTPLATE));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 12));
    }

    private void giveBuilder(Player p) {
        p.getInventory().addItem(new ItemStack(Material.IRON_AXE));
        p.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 64));
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 12));

        // ✅ наковальня -> ремонт
        p.getInventory().addItem(makeBuilderRepairItem());

        // ✅ smithing table -> апгрейд стен
        p.getInventory().addItem(makeBuilderWallUpgradeItem());
    }

    // ====== builder tools ======

    private ItemStack makeBuilderRepairItem() {
        ItemStack it = new ItemStack(Material.ANVIL);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(ChatColor.GREEN + "Ремонт базы");
            im.setLore(Arrays.asList(
                    ChatColor.GRAY + "ПКМ — открыть меню ремонта",
                    ChatColor.DARK_GRAY + "Только для билдера"));
            im.getPersistentDataContainer().set(builderRepairKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack makeBuilderWallUpgradeItem() {
        ItemStack it = new ItemStack(Material.SMITHING_TABLE);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(ChatColor.AQUA + "Прокачка стен");
            im.setLore(Arrays.asList(
                    ChatColor.GRAY + "ПКМ — открыть меню прокачки",
                    ChatColor.GRAY + "Потом ПКМ по блоку на базе",
                    ChatColor.DARK_GRAY + "Только для билдера"));
            im.getPersistentDataContainer().set(builderUpgradeKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(im);
        }
        return it;
    }

    public boolean isBuilderRepairTool(ItemStack it) {
        if (it == null || it.getType() != Material.ANVIL)
            return false;
        if (!it.hasItemMeta())
            return false;
        Byte v = it.getItemMeta().getPersistentDataContainer().get(builderRepairKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public boolean isBuilderWallUpgradeTool(ItemStack it) {
        if (it == null || it.getType() != Material.SMITHING_TABLE)
            return false;
        if (!it.hasItemMeta())
            return false;
        Byte v = it.getItemMeta().getPersistentDataContainer().get(builderUpgradeKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }
}
