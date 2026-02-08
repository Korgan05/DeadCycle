package me.korgan.deadcycle.phase;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.siege.SiegeManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.scheduler.BukkitTask;

public class PhaseManager {

    public enum Phase {
        DAY, NIGHT
    }

    private final DeadCyclePlugin plugin;
    private final SiegeManager siege;

    private Phase phase = Phase.DAY;
    private int dayCount = 0;

    private BukkitTask task;
    private final BossBar bar;

    private int secondsLeft = 0;

    public PhaseManager(DeadCyclePlugin plugin, SiegeManager siege) {
        this.plugin = plugin;
        this.siege = siege;
        this.bar = Bukkit.createBossBar("DeadCycle", BarColor.GREEN, BarStyle.SEGMENTED_10);
    }

    public Phase getPhase() {
        return phase;
    }

    public int getDayCount() {
        return dayCount;
    }

    public void start() {
        stop();

        bar.removeAll();
        Bukkit.getOnlinePlayers().forEach(bar::addPlayer);
        bar.setVisible(true);

        switchToDay(true);

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (secondsLeft > 0)
                secondsLeft--;

            double total = (phase == Phase.DAY)
                    ? plugin.getConfig().getInt("phase.day_seconds", 600)
                    : plugin.getConfig().getInt("phase.night_seconds", 300);

            double progress = Math.max(0.0, Math.min(1.0, secondsLeft / total));
            bar.setProgress(progress);

            bar.setTitle((phase == Phase.DAY ? "☀ День " : "🌙 Ночь ")
                    + dayCount + " | " + secondsLeft + "s");

            if (secondsLeft == 0) {
                if (phase == Phase.DAY)
                    switchToNight();
                else
                    switchToDay(false);
            }
        }, 20L, 20L);

        // чтобы тем, кто зашёл позже, тоже показывало BossBar
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!bar.isVisible())
                return;
            Bukkit.getOnlinePlayers().forEach(p -> {
                if (!bar.getPlayers().contains(p))
                    bar.addPlayer(p);
            });
        }, 40L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        bar.setVisible(false);
        bar.removeAll();

        plugin.zombie().stopNight();
        siege.stop();
        if (plugin.bossDuel() != null)
            plugin.bossDuel().forceEnd("phase_stop");
    }

    public void forcePhase(String phaseName) {
        if (phaseName == null)
            return;
        if (phaseName.equalsIgnoreCase("day"))
            switchToDay(false);
        if (phaseName.equalsIgnoreCase("night"))
            switchToNight();
    }

    private void switchToDay(boolean first) {
        phase = Phase.DAY;

        plugin.zombie().stopNight();
        siege.stop();
        if (plugin.bossDuel() != null)
            plugin.bossDuel().forceEnd("night_end");

        if (first)
            dayCount = 1;
        else
            dayCount++;

        secondsLeft = plugin.getConfig().getInt("phase.day_seconds", 600);

        for (World w : Bukkit.getWorlds())
            w.setTime(1000);
        bar.setColor(BarColor.GREEN);

        // Респавним игроков, которые умерли ночью и были в spectator.
        try {
            plugin.deathSpectator().reviveAllAtDayStart();
        } catch (Throwable ignored) {
        }

        // По просьбе: булыжники регена мгновенно "отрегениваются" на старте дня.
        try {
            if (plugin.regenMining() != null)
                plugin.regenMining().restoreAllNowAtDayStart();
        } catch (Throwable ignored) {
        }
    }

    private void switchToNight() {
        phase = Phase.NIGHT;

        secondsLeft = plugin.getConfig().getInt("phase.night_seconds", 300);

        for (World w : Bukkit.getWorlds())
            w.setTime(13000);
        bar.setColor(BarColor.PURPLE);

        plugin.zombie().startNight(dayCount);

        // осада стартует, если условия соблюдены (start_day, кто-то на базе и т.д.)
        siege.onNightStart(dayCount);

        if (plugin.bossDuel() != null)
            plugin.bossDuel().trySpawnBoss(dayCount);
    }
}
