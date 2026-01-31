package me.korgan.deadcycle.scoreboard;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.base.BaseResourceManager;
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
        Objective o = sb.registerNewObjective("dc", "dummy", ChatColor.RED + "" + ChatColor.BOLD + "DeadCycle");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);

        int l = 15;

        long total = plugin.baseResources().getBasePoints();
        BaseResourceManager br = plugin.baseResources();

        o.getScore(ChatColor.AQUA + "Ресурсы базы: " + ChatColor.WHITE + total).setScore(l--);
        o.getScore(ChatColor.GRAY + "Камень: " + br.getPoints(BaseResourceManager.ResourceType.STONE)).setScore(l--);
        o.getScore(ChatColor.GRAY + "Уголь: " + br.getPoints(BaseResourceManager.ResourceType.COAL)).setScore(l--);
        o.getScore(ChatColor.GRAY + "Железо: " + br.getPoints(BaseResourceManager.ResourceType.IRON)).setScore(l--);
        o.getScore(ChatColor.GRAY + "Алмазы: " + br.getPoints(BaseResourceManager.ResourceType.DIAMOND)).setScore(l--);

        o.getScore(" ").setScore(l--);

        double money = plugin.economy().getMoney(p.getUniqueId());
        o.getScore(ChatColor.GOLD + "Деньги: " + ChatColor.WHITE + (int) money).setScore(l--);

        p.setScoreboard(sb);
    }
}
