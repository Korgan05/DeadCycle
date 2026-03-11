package me.korgan.deadcycle.phase;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.siege.SiegeManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
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
        if (plugin.miniBoss() != null)
            plugin.miniBoss().stopNight();
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

    public void setDayCount(int dayNum) {
        dayCount = Math.max(1, dayNum);
    }

    public void reset() {
        // Полный сброс сохранений и переход на День 1

        // Помечаем что сейчас будет полный рестарт - скиллы НЕ будут выдаваться при
        // возрождении
        if (plugin.deathSpectator() != null) {
            plugin.deathSpectator().setFullGameResetFlag(true);
        }

        // Сохраняем «версию» полного ресета, чтобы на следующем входе оффлайн-игроки
        // принудительно получили чистый инвентарь.
        int nextResetVersion = Math.max(0, plugin.getConfig().getInt("runtime.full_reset_version", 0)) + 1;
        plugin.getConfig().set("runtime.full_reset_version", nextResetVersion);

        // Перед очисткой players.yml рассчитываем стартовый максимум маны после ресета.
        if (plugin.mana() != null) {
            plugin.mana().prepareCarryoverForReset();
        }

        // 1. СНАЧАЛА очищаем все данные в players.yml (уровни китов, опыт, мана и т.д.)
        if (plugin.playerData() != null)
            plugin.playerData().clearAll();

        // 2. Очищаем экономику (деньги)
        if (plugin.econ() != null)
            plugin.econ().clearAll();

        // 3. Сбрасываем специальные навыки (авто-хил, авто-реген, уклонение)
        if (plugin.specialSkills() != null)
            plugin.specialSkills().resetAll();

        // 4. Сбрасываем ману (очищаем кеш после очистки playerData)
        if (plugin.mana() != null)
            plugin.mana().resetAll();

        // 5. Сбрасываем прогресс китов (уровни и опыт)
        if (plugin.progress() != null)
            plugin.progress().resetAll();

        // 6. Сбрасываем ресурсы базы
        if (plugin.baseResources() != null)
            plugin.baseResources().resetAll();

        // 7. Сбрасываем уровни улучшений в config.yml
        plugin.getConfig().set("base.wall_level", 1);
        plugin.getConfig().set("upgrades.wall.level", 0);
        plugin.getConfig().set("upgrades.repair.level", 0);
        plugin.saveConfig();

        // 8. Возвращаемся на День 1
        dayCount = 0;

        if (plugin.bossDuel() != null) {
            plugin.bossDuel().clearSkillAdaptationData();
        }
        if (plugin.miniBoss() != null) {
            plugin.miniBoss().resetProgress();
        }
        if (plugin.cloneKit() != null) {
            plugin.cloneKit().resetAll();
        }
        if (plugin.summonerKit() != null) {
            plugin.summonerKit().resetAll();
        }

        switchToDay(true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            clearFullInventory(p);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage("§c[ИГРА] Все игроки умерли. Игра перезагружена до Дня 1!");
        }
    }

    private void clearFullInventory(Player p) {
        if (p == null)
            return;

        try {
            PlayerInventory inv = p.getInventory();
            inv.clear();
            inv.setArmorContents(null);
            inv.setItemInOffHand(null);
            p.getEnderChest().clear();
            p.setItemOnCursor(null);
            p.updateInventory();
        } catch (Throwable ignored) {
        }
    }

    private void switchToDay(boolean first) {
        phase = Phase.DAY;

        plugin.zombie().stopNight();
        if (plugin.miniBoss() != null)
            plugin.miniBoss().stopNight();
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

        // Удаляем свитки призыва у всех игроков при наступлении дня
        try {
            if (plugin.getBossHelpScrollListener() != null) {
                plugin.getBossHelpScrollListener().removeAllScrollsFromPlayers();
            }
        } catch (Throwable ignored) {
        }

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

        // С 10 дня - ночь 1000 секунд если босс спавнится, иначе 300
        int nightDuration = plugin.getConfig().getInt("phase.night_seconds", 300);
        if (dayCount >= 10) {
            nightDuration = 1000;
        }
        secondsLeft = nightDuration;

        for (World w : Bukkit.getWorlds())
            w.setTime(13000);
        bar.setColor(BarColor.PURPLE);

        plugin.zombie().startNight(dayCount);
        if (plugin.miniBoss() != null)
            plugin.miniBoss().startNight(dayCount);

        // осада стартует, если условия соблюдены (start_day, кто-то на базе и т.д.)
        siege.onNightStart(dayCount);

        if (plugin.bossDuel() != null)
            plugin.bossDuel().trySpawnBoss(dayCount);
    }

    public void endNightEarly() {
        // Преждевременное завершение ночи (смерть босса или всех игроков)
        if (phase != Phase.NIGHT)
            return;
        switchToDay(false);
    }
}
