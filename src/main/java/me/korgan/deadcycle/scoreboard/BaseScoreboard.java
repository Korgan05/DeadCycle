package me.korgan.deadcycle.scoreboard;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.base.BaseUpgradeManager;
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
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    private void update(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("dc", "dummy", "§c§lDeadCycle");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);

        int l = 15;
        BaseUpgradeManager u = plugin.upgrades();

        o.getScore("§7День: §f" + plugin.phase().getDayCount()).setScore(l--);
        o.getScore("§7На базе: §f" + plugin.base().countOnBase()).setScore(l--);
        o.getScore(" ").setScore(l--);

        o.getScore("§bОчки базы: §f" + plugin.baseResources().getBasePoints()).setScore(l--);
        o.getScore("§7Стены: §f" + u.getWallLevel()).setScore(l--);
        o.getScore("§7Ремонт: §f" + u.getRepairLevel()).setScore(l--);

        o.getScore("  ").setScore(l--);

        o.getScore("§6Деньги: §f" + plugin.econ().getMoney(p.getUniqueId())).setScore(l--);

        p.setScoreboard(sb);
    }
}
