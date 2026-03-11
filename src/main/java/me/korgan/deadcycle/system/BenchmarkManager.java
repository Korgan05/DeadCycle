package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.phase.PhaseManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class BenchmarkManager implements Listener {

    public static final class PlayerMetrics {
        private final UUID uuid;
        private double damageDealt;
        private double damageTaken;
        private int deaths;
        private int zombieKills;

        private PlayerMetrics(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID getUuid() {
            return uuid;
        }

        public double getDamageDealt() {
            return damageDealt;
        }

        public double getDamageTaken() {
            return damageTaken;
        }

        public int getDeaths() {
            return deaths;
        }

        public int getZombieKills() {
            return zombieKills;
        }
    }

    public static final class BenchmarkReport {
        private final long startedAtMs;
        private final long endedAtMs;
        private final int plannedDurationSec;
        private final int maxOnline;
        private final int dayTransitions;
        private final int nightTransitions;
        private final int phaseSwitches;
        private final double totalDamageDealt;
        private final double totalDamageTaken;
        private final int totalDeaths;
        private final int totalZombieKills;
        private final double duelActiveSeconds;
        private final double siegeActiveSeconds;
        private final String reason;
        private final Map<UUID, PlayerMetrics> playerMetrics;

        private BenchmarkReport(
                long startedAtMs,
                long endedAtMs,
                int plannedDurationSec,
                int maxOnline,
                int dayTransitions,
                int nightTransitions,
                int phaseSwitches,
                double totalDamageDealt,
                double totalDamageTaken,
                int totalDeaths,
                int totalZombieKills,
                double duelActiveSeconds,
                double siegeActiveSeconds,
                String reason,
                Map<UUID, PlayerMetrics> playerMetrics) {
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
            this.plannedDurationSec = plannedDurationSec;
            this.maxOnline = maxOnline;
            this.dayTransitions = dayTransitions;
            this.nightTransitions = nightTransitions;
            this.phaseSwitches = phaseSwitches;
            this.totalDamageDealt = totalDamageDealt;
            this.totalDamageTaken = totalDamageTaken;
            this.totalDeaths = totalDeaths;
            this.totalZombieKills = totalZombieKills;
            this.duelActiveSeconds = duelActiveSeconds;
            this.siegeActiveSeconds = siegeActiveSeconds;
            this.reason = reason;
            this.playerMetrics = playerMetrics;
        }

        public long getStartedAtMs() {
            return startedAtMs;
        }

        public long getEndedAtMs() {
            return endedAtMs;
        }

        public int getPlannedDurationSec() {
            return plannedDurationSec;
        }

        public int getMaxOnline() {
            return maxOnline;
        }

        public int getDayTransitions() {
            return dayTransitions;
        }

        public int getNightTransitions() {
            return nightTransitions;
        }

        public int getPhaseSwitches() {
            return phaseSwitches;
        }

        public double getTotalDamageDealt() {
            return totalDamageDealt;
        }

        public double getTotalDamageTaken() {
            return totalDamageTaken;
        }

        public int getTotalDeaths() {
            return totalDeaths;
        }

        public int getTotalZombieKills() {
            return totalZombieKills;
        }

        public double getDuelActiveSeconds() {
            return duelActiveSeconds;
        }

        public double getSiegeActiveSeconds() {
            return siegeActiveSeconds;
        }

        public String getReason() {
            return reason;
        }

        public Map<UUID, PlayerMetrics> getPlayerMetrics() {
            return playerMetrics;
        }

        public int getElapsedSeconds() {
            return (int) Math.max(1L, (endedAtMs - startedAtMs) / 1000L);
        }
    }

    private static final class Session {
        private final long startedAtMs = System.currentTimeMillis();
        private final int plannedDurationSec;
        private final String startedBy;

        private int maxOnline;
        private int dayTransitions;
        private int nightTransitions;
        private int phaseSwitches;

        private double totalDamageDealt;
        private double totalDamageTaken;
        private int totalDeaths;
        private int totalZombieKills;

        private long duelActiveTicks;
        private long siegeActiveTicks;

        private PhaseManager.Phase lastPhase;
        private final Map<UUID, PlayerMetrics> perPlayer = new HashMap<>();

        private Session(int plannedDurationSec, String startedBy, PhaseManager.Phase currentPhase) {
            this.plannedDurationSec = plannedDurationSec;
            this.startedBy = startedBy;
            this.lastPhase = currentPhase;
            this.maxOnline = Bukkit.getOnlinePlayers().size();
        }

        private PlayerMetrics metrics(UUID uuid) {
            return perPlayer.computeIfAbsent(uuid, PlayerMetrics::new);
        }
    }

    private final DeadCyclePlugin plugin;
    private Session active;
    private BenchmarkReport lastReport;

    private BukkitTask tickTask;
    private BukkitTask autoStopTask;

    public BenchmarkManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return active != null;
    }

    public String currentStartedBy() {
        return active == null ? null : active.startedBy;
    }

    public synchronized boolean start(int durationSec, String startedBy) {
        if (active != null) {
            return false;
        }

        int safeDuration = Math.max(30, Math.min(3600, durationSec));
        PhaseManager.Phase currentPhase = plugin.phase() != null ? plugin.phase().getPhase() : null;
        active = new Session(safeDuration, startedBy, currentPhase);

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        autoStopTask = Bukkit.getScheduler().runTaskLater(plugin, () -> stop("time_limit"), safeDuration * 20L);
        return true;
    }

    public synchronized BenchmarkReport stop(String reason) {
        if (active == null) {
            return null;
        }

        cancelTask(tickTask);
        cancelTask(autoStopTask);

        long endedAt = System.currentTimeMillis();
        Session s = active;
        active = null;

        Map<UUID, PlayerMetrics> copy = new HashMap<>();
        for (Map.Entry<UUID, PlayerMetrics> entry : s.perPlayer.entrySet()) {
            PlayerMetrics m = entry.getValue();
            PlayerMetrics c = new PlayerMetrics(m.uuid);
            c.damageDealt = m.damageDealt;
            c.damageTaken = m.damageTaken;
            c.deaths = m.deaths;
            c.zombieKills = m.zombieKills;
            copy.put(entry.getKey(), c);
        }

        lastReport = new BenchmarkReport(
                s.startedAtMs,
                endedAt,
                s.plannedDurationSec,
                s.maxOnline,
                s.dayTransitions,
                s.nightTransitions,
                s.phaseSwitches,
                s.totalDamageDealt,
                s.totalDamageTaken,
                s.totalDeaths,
                s.totalZombieKills,
                s.duelActiveTicks / 20.0,
                s.siegeActiveTicks / 20.0,
                reason == null ? "manual" : reason,
                copy);

        return lastReport;
    }

    public synchronized void reset() {
        stop("reset");
        lastReport = null;
    }

    public synchronized BenchmarkReport getLastReport() {
        return lastReport;
    }

    public synchronized void shutdown() {
        stop("shutdown");
    }

    public synchronized List<String> buildReportLines(BenchmarkReport report) {
        List<String> lines = new ArrayList<>();
        if (report == null) {
            lines.add("§cNo benchmark report available.");
            return lines;
        }

        int elapsed = report.getElapsedSeconds();
        double dps = report.getTotalDamageDealt() / Math.max(1.0, elapsed);
        double dtps = report.getTotalDamageTaken() / Math.max(1.0, elapsed);

        lines.add("§6§l[Benchmark] §fDeadCycle stress report");
        lines.add("§7Reason: §f" + report.getReason()
                + " §7| Planned: §f" + report.getPlannedDurationSec() + "s"
                + " §7| Actual: §f" + elapsed + "s");

        lines.add("§7Phase switches: §f" + report.getPhaseSwitches()
                + " §7(day: §f" + report.getDayTransitions()
                + "§7, night: §f" + report.getNightTransitions() + "§7)");

        lines.add("§7Combat totals: §fdealt=" + format1(report.getTotalDamageDealt())
                + " §7(" + format1(dps) + "/s), taken=§f" + format1(report.getTotalDamageTaken())
                + " §7(" + format1(dtps) + "/s)");

        lines.add("§7Events: §fdeaths=" + report.getTotalDeaths()
                + " §7| zombie kills=§f" + report.getTotalZombieKills()
                + " §7| max online=§f" + report.getMaxOnline());

        lines.add("§7System uptime: §fduel=" + format1(report.getDuelActiveSeconds()) + "s"
                + " §7| siege=§f" + format1(report.getSiegeActiveSeconds()) + "s");

        List<PlayerMetrics> top = new ArrayList<>(report.getPlayerMetrics().values());
        top.sort(Comparator.comparingDouble(PlayerMetrics::getDamageDealt).reversed());

        if (!top.isEmpty()) {
            lines.add("§7Top damage players:");
            int limit = Math.min(3, top.size());
            for (int i = 0; i < limit; i++) {
                PlayerMetrics m = top.get(i);
                lines.add("§f" + (i + 1) + ") " + playerName(m.getUuid())
                        + " §7dealt=§f" + format1(m.getDamageDealt())
                        + " §7taken=§f" + format1(m.getDamageTaken())
                        + " §7deaths=§f" + m.getDeaths()
                        + " §7kills=§f" + m.getZombieKills());
            }
        }

        return lines;
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        Session s = active;
        if (s == null || event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        double damage = Math.max(0.0, event.getFinalDamage());
        s.totalDamageTaken += damage;
        s.metrics(player.getUniqueId()).damageTaken += damage;
    }

    @EventHandler
    public void onPlayerDealsDamage(EntityDamageByEntityEvent event) {
        Session s = active;
        if (s == null || event.isCancelled()) {
            return;
        }

        Player player = resolveDamager(event.getDamager());
        if (player == null) {
            return;
        }

        double damage = Math.max(0.0, event.getFinalDamage());
        s.totalDamageDealt += damage;
        s.metrics(player.getUniqueId()).damageDealt += damage;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Session s = active;
        if (s == null) {
            return;
        }

        s.totalDeaths++;
        s.metrics(event.getEntity().getUniqueId()).deaths++;
    }

    @EventHandler
    public void onZombieDeath(EntityDeathEvent event) {
        Session s = active;
        if (s == null) {
            return;
        }

        if (!(event.getEntity() instanceof Zombie)) {
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }

        s.totalZombieKills++;
        s.metrics(killer.getUniqueId()).zombieKills++;
    }

    private void tick() {
        Session s = active;
        if (s == null) {
            return;
        }

        s.maxOnline = Math.max(s.maxOnline, Bukkit.getOnlinePlayers().size());

        if (plugin.phase() != null) {
            PhaseManager.Phase now = plugin.phase().getPhase();
            if (s.lastPhase != null && now != null && now != s.lastPhase) {
                s.phaseSwitches++;
                if (now == PhaseManager.Phase.DAY) {
                    s.dayTransitions++;
                } else if (now == PhaseManager.Phase.NIGHT) {
                    s.nightTransitions++;
                }
            }
            if (now != null) {
                s.lastPhase = now;
            }
        }

        if (plugin.bossDuel() != null && plugin.bossDuel().isDuelActive()) {
            s.duelActiveTicks += 20L;
        }

        if (plugin.siege() != null && plugin.siege().isRunning()) {
            s.siegeActiveTicks += 20L;
        }
    }

    private Player resolveDamager(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }

        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }

        return null;
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private String playerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() != null ? offline.getName() : uuid.toString();
    }

    private String format1(double value) {
        return String.format(Locale.US, "%.1f", value);
    }
}
