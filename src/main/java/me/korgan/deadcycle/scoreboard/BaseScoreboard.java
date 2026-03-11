package me.korgan.deadcycle.scoreboard;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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
        if (sm == null)
            return;

        Scoreboard sb = sm.getNewScoreboard();
        Objective o = sb.registerNewObjective("dc", Criteria.DUMMY,
                Component.text("DEADCYCLE", NamedTextColor.GREEN, TextDecoration.BOLD));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);

        int line = 15;

        int onBase = plugin.base().isEnabled() ? plugin.base().countOnBase() : 0;
        o.getScore("§eНа базе: §f" + onBase).setScore(line--);

        o.getScore("§eДень: §f" + plugin.phase().getDayCount()).setScore(line--);
        o.getScore("§eФаза: §f" + plugin.phase().getPhase().name()).setScore(line--);

        o.getScore(" ").setScore(line--);

        long money = plugin.econ().getMoney(p.getUniqueId());
        o.getScore("§6Деньги: §f" + money).setScore(line--);

        KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
        String kitName = switch (kit) {
            case FIGHTER -> "Боец";
            case MINER -> "Шахтёр";
            case BUILDER -> "Билдер";
            case ARCHER -> "Лучник";
            case BERSERK -> "Берсерк";
            case GRAVITATOR -> "Гравитатор";
            case DUELIST -> "Ритуалист";
            case CLONER -> "Клонер";
            case SUMMONER -> "Призыватель";
            case null, default -> "-";
        };
        o.getScore("§bКит: §f" + kitName).setScore(line--);

        int kitLvl = plugin.progress().getKitLevel(p.getUniqueId(), kit);
        int kitExp = plugin.progress().getKitExp(p.getUniqueId(), kit);
        int kitNeed = plugin.progress().getKitNeedExp(p.getUniqueId(), kit);

        o.getScore("§aЛвл кита: §f" + kitLvl).setScore(line--);
        o.getScore("§7Опыт кита: §f" + kitExp + "/" + kitNeed).setScore(line--);

        p.setScoreboard(sb);
    }
}
