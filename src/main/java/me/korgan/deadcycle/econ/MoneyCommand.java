package me.korgan.deadcycle.econ;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MoneyCommand implements CommandExecutor {

    private final DeadCyclePlugin plugin;

    public MoneyCommand(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players.");
            return true;
        }

        long money = plugin.econ().getMoney(p.getUniqueId());
        p.sendMessage(ChatColor.GOLD + "Баланс: " + ChatColor.WHITE + money + "$");
        return true;
    }
}
