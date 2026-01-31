package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class RepairCommand implements CommandExecutor {

    private final DeadCyclePlugin plugin;
    private final RepairGUI gui;

    public RepairCommand(DeadCyclePlugin plugin, RepairGUI gui) {
        this.plugin = plugin;
        this.gui = gui;
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

        if (!plugin.base().isEnabled()) {
            p.sendMessage(ChatColor.RED + "База не включена.");
            return true;
        }

        gui.open(p);
        return true;
    }
}
