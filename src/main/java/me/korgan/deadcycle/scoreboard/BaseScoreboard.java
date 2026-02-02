package me.korgan.deadcycle.scoreboard;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

public class BaseScoreboard {

    private final DeadCyclePlugin plugin;

    public BaseScoreboard(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void updateAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p);
        }
    }

    private void update(Player p) {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return;

        Scoreboard sb = sm.getNewScoreboard();
        Objective o = sb.registerNewObjective("dc", "dummy",
                ChatColor.GREEN + "" + ChatColor.BOLD + "DEADCYCLE");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);

        int line = 15;

        int onBase = plugin.base().isEnabled() ? plugin.base().countOnBase() : 0;
        o.getScore(ChatColor.YELLOW + "На базе: " + ChatColor.WHITE + onBase).setScore(line--);

        o.getScore(ChatColor.YELLOW + "День: " + ChatColor.WHITE + plugin.phase().getDayCount()).setScore(line--);
        o.getScore(ChatColor.YELLOW + "Фаза: " + ChatColor.WHITE + plugin.phase().getPhase().name()).setScore(line--);

        o.getScore(" ").setScore(line--);

        long money = plugin.econ().getMoney(p.getUniqueId());
        o.getScore(ChatColor.GOLD + "Деньги: " + ChatColor.WHITE + money).setScore(line--);

        KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
        String kitName = (kit == null) ? "-" : kit.name();
        o.getScore(ChatColor.AQUA + "Кит: " + ChatColor.WHITE + kitName).setScore(line--);

        int kitLvl = plugin.progress().getKitLevel(p.getUniqueId(), kit);
        int kitExp = plugin.progress().getKitExp(p.getUniqueId(), kit);
        int kitNeed = plugin.progress().getKitNeedExp(p.getUniqueId(), kit);

        o.getScore(ChatColor.GREEN + "Лвл кита: " + ChatColor.WHITE + kitLvl).setScore(line--);
        o.getScore(ChatColor.GRAY + "Опыт кита: " + ChatColor.WHITE + kitExp + "/" + kitNeed).setScore(line--);

        p.setScoreboard(sb);
    }
}
