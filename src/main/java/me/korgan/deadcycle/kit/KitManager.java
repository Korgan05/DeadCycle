package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class KitManager implements Listener {

    public enum Kit { MINER, FIGHTER, ARCHER, BUILDER }

    private final DeadCyclePlugin plugin;
    private final Map<UUID, Kit> chosen = new HashMap<>();

    public KitManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public Kit getKit(UUID uuid) {
        return chosen.get(uuid);
    }

    public void setKit(Player p, Kit kit) {
        chosen.put(p.getUniqueId(), kit);
        giveKitItems(p, kit);
        p.sendMessage(ChatColor.GREEN + "Выбран кит: " + ChatColor.YELLOW + kit.name());

        plugin.progress().applyKitEffects(p);
    }

    private void giveKitItems(Player p, Kit kit) {
        p.getInventory().clear();

        switch (kit) {
            case MINER -> {
                p.getInventory().addItem(new ItemStack(Material.STONE_PICKAXE, 1));
                p.getInventory().addItem(new ItemStack(Material.BREAD, 8));
            }
            case FIGHTER -> {
                p.getInventory().addItem(new ItemStack(Material.STONE_SWORD, 1));
                p.getInventory().addItem(new ItemStack(Material.BREAD, 8));
            }
            case ARCHER -> {
                p.getInventory().addItem(new ItemStack(Material.BOW, 1));
                p.getInventory().addItem(new ItemStack(Material.ARROW, 32));
                p.getInventory().addItem(new ItemStack(Material.BREAD, 8));
            }
            case BUILDER -> {
                p.getInventory().addItem(new ItemStack(Material.WOODEN_AXE, 1));
                p.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 64));
                p.getInventory().addItem(new ItemStack(Material.BREAD, 8));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Если кит ещё не выбран — показываем меню выбора
        if (getKit(e.getPlayer().getUniqueId()) == null) {
            plugin.kitMenu().open(e.getPlayer());
        }
    }
}
