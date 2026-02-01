package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class DcCommand implements CommandExecutor {

    private final DeadCyclePlugin plugin;

    public DcCommand(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isAdmin(CommandSender s) {
        return !(s instanceof Player) || s.hasPermission("deadcycle.admin") || s.isOp();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage(ChatColor.GRAY + "DeadCycle: "
                    + ChatColor.YELLOW + "/dc start"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc stop"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc reload"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc setphase <day|night>"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc setbase <radius>");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {

            case "start" -> {
                if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Нет прав."); return true; }
                plugin.phase().start();
                sender.sendMessage(ChatColor.GREEN + "DeadCycle запущен. (PhaseManager START)");
                return true;
            }

            case "stop" -> {
                if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Нет прав."); return true; }
                plugin.phase().stop();
                sender.sendMessage(ChatColor.RED + "DeadCycle остановлен. (PhaseManager STOP)");
                return true;
            }

            case "reload" -> {
                if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Нет прав."); return true; }

                plugin.reloadConfig();

                // база
                plugin.base().reload();

                // ресурсы базы
                plugin.baseResources().load();

                // апгрейды: НЕ трогаем, пока у менеджера нет reload/load метода
                // (позже добавим нормально)
                sender.sendMessage(ChatColor.GREEN + "Конфиг перезагружен.");
                return true;
            }

            case "setphase" -> {
                if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Нет прав."); return true; }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Используй: /dc setphase <day|night>");
                    return true;
                }
                plugin.phase().forcePhase(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Фаза принудительно: " + args[1]);
                return true;
            }

            case "setbase" -> {
                if (!isAdmin(sender)) { sender.sendMessage(ChatColor.RED + "Нет прав."); return true; }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players.");
                    return true;
                }

                int radius = 30;
                if (args.length >= 2) {
                    try { radius = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                }

                Location loc = p.getLocation();
                plugin.base().setBase(loc, radius);

                sender.sendMessage(ChatColor.GREEN + "База установлена: "
                        + ChatColor.WHITE + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()
                        + ChatColor.GREEN + " | Радиус: " + ChatColor.WHITE + radius);
                return true;
            }

            default -> {
                return false; // покажет usage из plugin.yml
            }
        }
    }
}
