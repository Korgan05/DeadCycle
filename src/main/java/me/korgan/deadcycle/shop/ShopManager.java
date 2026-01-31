package me.korgan.deadcycle.shop;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class ShopManager {

    private final DeadCyclePlugin plugin;

    public ShopManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        if (!plugin.base().isOnBase(p.getLocation())) {
            p.sendMessage(ChatColor.RED + "Магазин доступен только на базе!");
            return;
        }

        p.sendMessage(ChatColor.YELLOW + "Магазин временно доступен только для покупки.");
        p.sendMessage(ChatColor.GRAY + "Продажа ресурсов отключена (они нужны базе).");
    }
}
