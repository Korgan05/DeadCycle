package me.korgan.deadcycle.shop;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /shop — открыть магазин (ТОЛЬКО НА БАЗЕ)
 */
public class ShopCommand implements CommandExecutor {

    private final DeadCyclePlugin plugin;

    public ShopCommand(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players.");
            return true;
        }

        if (!plugin.base().isEnabled()) {
            p.sendMessage(ChatColor.RED + "База не включена. (base.enabled=false)");
            return true;
        }

        if (!plugin.base().isOnBase(p.getLocation())) {
            p.sendMessage(ChatColor.RED + "Магазин доступен только на базе!");
            return true;
        }

        plugin.shopGui().open(p);
        return true;
    }
}
