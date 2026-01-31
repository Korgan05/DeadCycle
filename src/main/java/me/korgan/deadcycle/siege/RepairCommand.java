package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RepairCommand implements CommandExecutor {

    private final DeadCyclePlugin plugin;
    private final BlockHealthManager blocks;

    public RepairCommand(DeadCyclePlugin plugin, BlockHealthManager blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players.");
            return true;
        }

        // только BUILDER
        KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
        if (kit != KitManager.Kit.BUILDER) {
            p.sendMessage(ChatColor.RED + "Ремонт доступен только киту BUILDER.");
            return true;
        }

        int dist = plugin.getConfig().getInt("builder.repair_distance", 5);
        int amount = plugin.getConfig().getInt("builder.repair_amount", 8);

        Block b = p.getTargetBlockExact(dist);
        if (b == null) {
            p.sendMessage(ChatColor.RED + "Смотри на блок (до " + dist + " блоков).");
            return true;
        }

        if (!plugin.base().isEnabled() || !plugin.base().isOnBase(b.getLocation())) {
            p.sendMessage(ChatColor.RED + "Ремонт только внутри базы.");
            return true;
        }

        if (blocks.getMaxHp(b.getType()) <= 0) {
            p.sendMessage(ChatColor.RED + "Этот блок нельзя ремонтировать (не из списка).");
            return true;
        }

        blocks.repair(b, amount);

        int money = plugin.getConfig().getInt("builder.reward_money", 2);
        int xp = plugin.getConfig().getInt("builder.reward_xp", 1);

        plugin.econ().give(p, money);
        p.giveExp(xp);

        p.sendMessage(ChatColor.GREEN + "Починено +" + amount + " | +" + money + "$ | +" + xp + "xp");
        return true;
    }
}
