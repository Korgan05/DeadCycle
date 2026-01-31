package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

public class BaseScoreboard {

    private final DeadCyclePlugin plugin;

    private Scoreboard board;
    private Objective obj;

    private int taskId = -1;

    public BaseScoreboard(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();

        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;

        board = sm.getNewScoreboard();
        obj = board.registerNewObjective("deadcycle", "dummy", ChatColor.GREEN + "" + ChatColor.BOLD + "DEADCYCLE");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Обновляем раз в 1 сек
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::updateAll, 20L, 20L);
        updateAll();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        // сбрасывать scoreboard игрокам НЕ будем, чтобы не ломать чужие плагины
    }

    private void updateAll() {
        if (obj == null) return;

        // для каждого игрока свой sidebar (потому что деньги/кит/уровни разные)
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateFor(p);
        }
    }

    private void updateFor(Player p) {
        // создаём отдельный board на игрока (так проще, но безопасно по логике)
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;

        Scoreboard sb = sm.getNewScoreboard();
        Objective o = sb.registerNewObjective("deadcycle", "dummy", ChatColor.GREEN + "" + ChatColor.BOLD + "DEADCYCLE");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);

        int onBase = plugin.base().isEnabled() ? plugin.base().countOnBase() : 0;

        String dayStr = String.valueOf(plugin.phase().getDayCount());
        String phaseStr = plugin.phase().getPhase().name().equalsIgnoreCase("DAY") ? "День" : "Ночь";

        long money = plugin.econ().getMoney(p.getUniqueId());

        // общий уровень игрока
        int pLvl = plugin.progress().getPlayerLevel(p.getUniqueId());
        int pExp = plugin.progress().getPlayerExp(p.getUniqueId());
        int pNeed = plugin.progress().needPlayerExp(p.getUniqueId());

        // кит + уровень кита
        KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
        String kitName = (kit == null) ? "-" : kit.name();
        int kitLvl = plugin.progress().getKitLevel(p.getUniqueId(), kit);

        // ВАЖНО: каждая строка должна быть уникальна в scoreboard.
        // Поэтому используем уникальные "невидимые" суффиксы (цвет-коды).
        int line = 15;

        setLine(o, line--, ChatColor.YELLOW + "На базе: " + ChatColor.WHITE + onBase, 1);
        setLine(o, line--, ChatColor.YELLOW + "День: " + ChatColor.WHITE + dayStr, 2);
        setLine(o, line--, ChatColor.YELLOW + "Фаза: " + ChatColor.WHITE + phaseStr, 3);

        setLine(o, line--, ChatColor.DARK_GRAY + " ", 4);

        setLine(o, line--, ChatColor.GOLD + "Деньги: " + ChatColor.WHITE + money, 5);

        setLine(o, line--, ChatColor.AQUA + "Уровень: " + ChatColor.WHITE + pLvl, 6);
        setLine(o, line--, ChatColor.AQUA + "Опыт: " + ChatColor.WHITE + pExp + "/" + pNeed, 7);

        setLine(o, line--, ChatColor.GREEN + "Кит: " + ChatColor.WHITE + kitName, 8);
        setLine(o, line--, ChatColor.GREEN + "Лвл кита: " + ChatColor.WHITE + kitLvl, 9);

        int kitExp = plugin.progress().getKitExp(p.getUniqueId(), kit);
        int kitNeed = plugin.progress().getKitNeedExp(p.getUniqueId(), kit);

        setLine(o, line--, ChatColor.GREEN + "Опыт кита: " + ChatColor.WHITE + kitExp + "/" + kitNeed, 10);

        p.setScoreboard(sb);
    }

    private void setLine(Objective o, int score, String text, int uniq) {
        // добавляем уникальный цвет-код, чтобы не было одинаковых строк
        // (Minecraft не допускает одинаковые entry-тексты)
        ChatColor suffix = ChatColor.values()[uniq % ChatColor.values().length];
        String entry = text + ChatColor.RESET + "" + suffix;

        Score s = o.getScore(entry);
        s.setScore(score);
    }
}
