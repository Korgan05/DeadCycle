package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

public class DcCommand implements CommandExecutor {

    private final DeadCyclePlugin plugin;

    public DcCommand(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isAdmin(CommandSender s) {
        return !(s instanceof Player) || s.hasPermission("deadcycle.admin") || s.isOp();
    }

    private OfflinePlayer resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null)
            return online;

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline()))
            return cached;

        return null;
    }

    private String onOff(boolean value) {
        return value ? "§aON" : "§cOFF";
    }

    private String formatLastTick(long timestamp) {
        if (timestamp <= 0)
            return "нет";

        long diffMs = System.currentTimeMillis() - timestamp;
        if (diffMs < 0)
            diffMs = 0;

        long seconds = diffMs / 1000L;
        if (seconds < 60)
            return seconds + "с назад";

        long minutes = seconds / 60L;
        long remSec = seconds % 60L;
        return minutes + "м " + remSec + "с назад";
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
                    + ChatColor.YELLOW + "/dc setdays <число>"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc kitlvl <уровень> <кит> [игрок]"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc boss help <ник>"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc setbase <radius>"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc stat <ник>"
                    + ChatColor.GRAY + ", "
                    + ChatColor.YELLOW + "/dc setstat <ник> <ключ> <значение>");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {

            case "start" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                plugin.phase().start();
                sender.sendMessage(ChatColor.GREEN + "DeadCycle запущен. (PhaseManager START)");
                return true;
            }

            case "stop" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                plugin.phase().stop();
                sender.sendMessage(ChatColor.RED + "DeadCycle остановлен. (PhaseManager STOP)");
                return true;
            }

            case "reload" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }

                plugin.reloadRuntimeConfig();
                sender.sendMessage(ChatColor.GREEN + "Конфиг перезагружен.");
                return true;
            }

            case "stat" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Используй: /dc stat <ник>");
                    return true;
                }

                OfflinePlayer target = resolveTarget(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден (нужен онлайн или кеш сервера): " + args[1]);
                    return true;
                }

                UUID uuid = target.getUniqueId();
                String name = target.getName() != null ? target.getName() : args[1];

                var special = plugin.specialSkills();
                var progress = plugin.progress();
                var mana = plugin.mana();

                sender.sendMessage(ChatColor.DARK_AQUA + "=== [DC STAT] " + ChatColor.AQUA + name + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.GRAY + "Онлайн: " + (target.isOnline() ? ChatColor.GREEN + "да" : ChatColor.RED + "нет"));
                sender.sendMessage(ChatColor.GRAY + "Деньги: " + ChatColor.GOLD + plugin.econ().getMoney(uuid));

                sender.sendMessage(ChatColor.GRAY + "Player Lvl/XP: " + ChatColor.WHITE + progress.getPlayerLevel(uuid)
                        + ChatColor.GRAY + " | " + ChatColor.WHITE + progress.getPlayerExp(uuid)
                        + ChatColor.GRAY + "/" + ChatColor.WHITE + progress.getPlayerNeedExp(uuid));

                sender.sendMessage(ChatColor.GRAY + "Киты lvl: " + ChatColor.WHITE
                        + "miner=" + progress.getMinerLevel(uuid)
                        + ", fighter=" + progress.getFighterLevel(uuid)
                        + ", builder=" + progress.getBuilderLevel(uuid)
                        + ", berserk=" + progress.getBerserkLevel(uuid)
                        + ", archer=" + progress.getArcherLevel(uuid)
                        + ", gravitator=" + progress.getGravitatorLevel(uuid));

                if (target.isOnline() && target.getPlayer() != null) {
                    Player online = target.getPlayer();
                    double hp = online.getHealth();
                    double hpMax = 20.0;
                    var attr = online.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if (attr != null)
                        hpMax = attr.getValue();

                    sender.sendMessage(ChatColor.GRAY + "HP: " + ChatColor.WHITE + String.format(Locale.US, "%.1f/%.1f", hp, hpMax));
                    sender.sendMessage(ChatColor.GRAY + "Мана XP: " + ChatColor.WHITE + mana.getCurrentXp(online)
                            + ChatColor.GRAY + "/" + ChatColor.WHITE + mana.getMaxXp(uuid));
                } else {
                    sender.sendMessage(ChatColor.GRAY + "Мана MaxXP: " + ChatColor.WHITE + mana.getMaxXp(uuid));
                }

                sender.sendMessage(ChatColor.DARK_GRAY + "--- Special Skills ---");
                sender.sendMessage(ChatColor.GRAY + "Потеряно HP (damage_taken): " + ChatColor.WHITE + special.getDamageTaken(uuid));
                sender.sendMessage(ChatColor.GRAY + "Нанесено урона (damage_dealt): " + ChatColor.WHITE + special.getDamageDealt(uuid));
                sender.sendMessage(ChatColor.GRAY + "Выхилено HP (heal_with_regen): " + ChatColor.WHITE + special.getHealWithRegen(uuid));
                sender.sendMessage(ChatColor.GRAY + "Потрачено маны (mana_spent): " + ChatColor.WHITE + special.getManaSpent(uuid));
                sender.sendMessage(ChatColor.GRAY + "Срабатывания: " + ChatColor.WHITE
                        + "regen=" + special.getRegenProcCount(uuid)
                        + ", auto_regen=" + special.getAutoRegenProcCount(uuid)
                        + ", auto_dodge=" + special.getAutoDodgeProcCount(uuid));

                sender.sendMessage(ChatColor.GRAY + "Разблокировано: " + ChatColor.WHITE
                        + "regen=" + onOff(special.isRegenUnlocked(uuid)) + ChatColor.WHITE
                        + ", auto_regen=" + onOff(special.isAutoRegenUnlocked(uuid)) + ChatColor.WHITE
                        + ", auto_dodge=" + onOff(special.isAutoDodgeUnlocked(uuid)));

                sender.sendMessage(ChatColor.GRAY + "До навыков: " + ChatColor.WHITE
                        + "regen=" + special.getRemainingToRegenUnlock(uuid)
                        + ", auto_regen=" + special.getRemainingToAutoRegenUnlock(uuid)
                        + ", auto_dodge(taken/dealt)=" + special.getRemainingToAutoDodgeTakenUnlock(uuid)
                        + "/" + special.getRemainingToAutoDodgeDealtUnlock(uuid));

                sender.sendMessage(ChatColor.DARK_GRAY + "--- Debug ---");
                sender.sendMessage(ChatColor.GRAY + "auto_regen_enabled=" + onOff(special.isAutoRegenEnabled(uuid))
                        + ChatColor.GRAY + " | regen_acc=" + ChatColor.WHITE
                        + String.format(Locale.US, "%.2f", special.getRegenAccumulator(uuid))
                        + ChatColor.GRAY + " | auto_regen_acc=" + ChatColor.WHITE
                        + String.format(Locale.US, "%.2f", special.getAutoRegenAccumulator(uuid))
                        + ChatColor.GRAY + " | last_tick=" + ChatColor.WHITE + formatLastTick(special.getLastSkillTickAt(uuid)));
                return true;
            }

            case "setstat" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.YELLOW + "Используй: /dc setstat <ник> <ключ> <значение>");
                    sender.sendMessage(ChatColor.GRAY + "Ключи special: " + plugin.specialSkills().getSupportedSetStatKeys());
                    sender.sendMessage(ChatColor.GRAY + "Доп. ключи: money, player_level, player_exp, mana_max, mana_current");
                    return true;
                }

                OfflinePlayer target = resolveTarget(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден (нужен онлайн или кеш сервера): " + args[1]);
                    return true;
                }

                UUID uuid = target.getUniqueId();
                String name = target.getName() != null ? target.getName() : args[1];
                String stat = args[2].toLowerCase(Locale.ROOT);

                int value;
                try {
                    value = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "Значение должно быть числом: " + args[3]);
                    return true;
                }

                boolean applied;
                switch (stat) {
                    case "money" -> {
                        long targetMoney = Math.max(0L, value);
                        long cur = plugin.econ().getMoney(uuid);
                        plugin.econ().addMoney(uuid, targetMoney - cur);
                        plugin.econ().save();
                        applied = true;
                    }
                    case "player_level" -> {
                        plugin.playerData().setInt(uuid, "player.level", Math.max(1, value));
                        plugin.playerData().save();
                        applied = true;
                    }
                    case "player_exp" -> {
                        plugin.playerData().setInt(uuid, "player.exp", Math.max(0, value));
                        plugin.playerData().save();
                        applied = true;
                    }
                    case "mana_max" -> {
                        plugin.mana().setMaxXp(uuid, Math.max(1, value));
                        applied = true;
                    }
                    case "mana_current" -> {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online == null) {
                            sender.sendMessage(ChatColor.RED + "mana_current можно менять только онлайн-игроку.");
                            return true;
                        }
                        plugin.mana().setCurrentXp(online, Math.max(0, value));
                        applied = true;
                    }
                    default -> applied = plugin.specialSkills().setStat(uuid, stat, value);
                }

                if (!applied) {
                    sender.sendMessage(ChatColor.RED + "Неизвестный ключ: " + stat);
                    sender.sendMessage(ChatColor.GRAY + "Ключи special: " + plugin.specialSkills().getSupportedSetStatKeys());
                    sender.sendMessage(ChatColor.GRAY + "Доп. ключи: money, player_level, player_exp, mana_max, mana_current");
                    return true;
                }

                sender.sendMessage(ChatColor.GREEN + "Установлено: " + ChatColor.YELLOW + stat + ChatColor.GREEN
                        + " = " + ChatColor.WHITE + value + ChatColor.GREEN + " для " + ChatColor.WHITE + name);

                if (target.isOnline() && target.getPlayer() != null) {
                    target.getPlayer().sendMessage(ChatColor.YELLOW + "Админ изменил твою статистику: "
                            + ChatColor.WHITE + stat + ChatColor.GRAY + " -> " + ChatColor.WHITE + value);
                }
                return true;
            }

            case "setphase" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Используй: /dc setphase <day|night>");
                    return true;
                }
                plugin.phase().forcePhase(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Фаза принудительно: " + args[1]);
                return true;
            }

            case "setdays" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Используй: /dc setdays <число>");
                    return true;
                }
                try {
                    int dayNum = Integer.parseInt(args[1]);
                    plugin.phase().setDayCount(dayNum);
                    sender.sendMessage(ChatColor.GREEN + "День установлен на: " + ChatColor.WHITE + dayNum);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Неверное число: " + args[1]);
                }
                return true;
            }

            case "setbase" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players.");
                    return true;
                }

                int radius = 30;
                if (args.length >= 2) {
                    try {
                        radius = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }

                Location loc = p.getLocation();
                plugin.base().setBase(loc, radius);

                sender.sendMessage(ChatColor.GREEN + "База установлена: "
                        + ChatColor.WHITE + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()
                        + ChatColor.GREEN + " | Радиус: " + ChatColor.WHITE + radius);
                return true;
            }

            case "kitlvl" -> {
                if (!isAdmin(sender)) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Используй: /dc kitlvl <уровень> <кит> [ник игрока]");
                    return true;
                }

                int level;
                try {
                    level = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Неверный уровень: " + args[1]);
                    return true;
                }

                String kitName = args[2].toUpperCase();
                KitManager.Kit kit;
                try {
                    kit = KitManager.Kit.valueOf(kitName);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(ChatColor.RED + "Неизвестный кит: " + args[2]);
                    return true;
                }

                Player target;
                if (args.length >= 4) {
                    target = Bukkit.getPlayer(args[3]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Игрок не найден: " + args[3]);
                        return true;
                    }
                } else {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage("Укажи ник игрока.");
                        return true;
                    }
                    target = p;
                }

                plugin.progress().setKitLevel(target.getUniqueId(), kit, level);
                sender.sendMessage(ChatColor.GREEN + "Уровень " + ChatColor.YELLOW + kit.name()
                        + ChatColor.GREEN + " игроку " + ChatColor.WHITE + target.getName()
                        + ChatColor.GREEN + " установлен на: " + ChatColor.WHITE + level);
                target.sendMessage(ChatColor.GREEN + "Твой уровень " + ChatColor.YELLOW + kit.name()
                        + ChatColor.GREEN + " теперь: " + ChatColor.WHITE + level);
                return true;
            }

            case "boss" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Используй: /dc boss help <ник игрока>");
                    return true;
                }

                String subCmd = args[1].toLowerCase();

                if (subCmd.equals("help")) {
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.YELLOW + "Используй: /dc boss help <ник игрока>");
                        return true;
                    }

                    Player ally = Bukkit.getPlayer(args[2]);
                    if (ally == null) {
                        sender.sendMessage(ChatColor.RED + "Игрок не найден: " + args[2]);
                        return true;
                    }

                    if (plugin.bossDuel().trySummonAlly(p, ally)) {
                        sender.sendMessage(ChatColor.GREEN + "Помощник вызван!");
                    }
                    return true;
                }

                return false;
            }

            default -> {
                return false; // покажет usage из plugin.yml
            }
        }
    }
}
