package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
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

import java.util.Arrays;

@SuppressWarnings("deprecation")
public class KitMenu implements Listener {

    private final DeadCyclePlugin plugin;

    public KitMenu(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Выбор кита");

        inv.setItem(11, item(Material.IRON_SWORD,
                ChatColor.RED + "Боец",
                ChatColor.GRAY + "Стартовый кит бойца"));

        inv.setItem(13, item(Material.IRON_PICKAXE,
                ChatColor.GOLD + "Шахтёр",
                ChatColor.GRAY + "Добыча ресурсов и опыт"));

        inv.setItem(15, item(Material.ANVIL,
                ChatColor.GREEN + "Билдер",
                ChatColor.GRAY + "Ремонт базы + улучшение стен"));

        inv.setItem(22, item(Material.BOW,
                ChatColor.AQUA + "Лучник",
                ChatColor.GRAY + "Дальний бой"));

        inv.setItem(20, item(Material.ANVIL,
                ChatColor.DARK_PURPLE + "Гравитатор",
                ChatColor.GRAY + "Усиливает гравитацию",
                ChatColor.GRAY + "Прижимает зомби к земле"));

        inv.setItem(24, item(Material.NETHERITE_AXE,
                ChatColor.DARK_RED + "Берсерк",
                ChatColor.GRAY + "Ярость на грани смерти",
                ChatColor.GRAY + "Прокает от низкого HP"));

        p.openInventory(inv);
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
        if (e.getView().getTitle() == null)
            return;

        String title = ChatColor.stripColor(e.getView().getTitle());
        if (!"Выбор кита".equalsIgnoreCase(title))
            return;

        e.setCancelled(true);

        if (e.getCurrentItem() == null)
            return;

        int slot = e.getRawSlot();

        // ✅ ВАЖНО: выдаём кит предметами, а не просто setKit()
        if (slot == 11) {
            plugin.kit().giveKit(p, KitManager.Kit.FIGHTER);
            plugin.progress().setKitChoiceRequired(p.getUniqueId(), false);
            p.sendMessage(ChatColor.GREEN + "Ты выбрал кит: " + ChatColor.RED + "Боец");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
            p.closeInventory();
            return;
        }

        if (slot == 13) {
            plugin.kit().giveKit(p, KitManager.Kit.MINER);
            plugin.progress().setKitChoiceRequired(p.getUniqueId(), false);
            p.sendMessage(ChatColor.GREEN + "Ты выбрал кит: " + ChatColor.GOLD + "Шахтёр");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
            p.closeInventory();
            return;
        }

        if (slot == 15) {
            plugin.kit().giveKit(p, KitManager.Kit.BUILDER);
            plugin.progress().setKitChoiceRequired(p.getUniqueId(), false);
            p.sendMessage(ChatColor.GREEN + "Ты выбрал кит: " + ChatColor.GREEN + "Билдер");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
            p.closeInventory();
            return;
        }

        if (slot == 22) {
            plugin.kit().giveKit(p, KitManager.Kit.ARCHER);
            plugin.progress().setKitChoiceRequired(p.getUniqueId(), false);
            p.sendMessage(ChatColor.GREEN + "Ты выбрал кит: " + ChatColor.AQUA + "Лучник");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
            p.closeInventory();
            return;
        }

        if (slot == 20) {
            plugin.kit().giveKit(p, KitManager.Kit.GRAVITATOR);
            plugin.progress().setKitChoiceRequired(p.getUniqueId(), false);
            p.sendMessage(ChatColor.GREEN + "Ты выбрал кит: " + ChatColor.DARK_PURPLE + "Гравитатор");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
            p.closeInventory();
            return;
        }

        if (slot == 24) {
            plugin.kit().giveKit(p, KitManager.Kit.BERSERK);
            plugin.progress().setKitChoiceRequired(p.getUniqueId(), false);
            p.sendMessage(ChatColor.GREEN + "Ты выбрал кит: " + ChatColor.DARK_RED + "Берсерк");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
            p.closeInventory();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p))
            return;
        if (e.getView().getTitle() == null)
            return;

        String title = ChatColor.stripColor(e.getView().getTitle());
        if (!"Выбор кита".equalsIgnoreCase(title))
            return;

        if (!plugin.progress().isKitChoiceRequired(p.getUniqueId()))
            return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline())
                return;
            if (p.getGameMode() == GameMode.SPECTATOR)
                return;
            open(p);
        });
    }
}
