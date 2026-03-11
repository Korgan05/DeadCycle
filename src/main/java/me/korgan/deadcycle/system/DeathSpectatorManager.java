package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.phase.PhaseManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DeathSpectatorManager implements Listener {

    private final DeadCyclePlugin plugin;
    private final Set<UUID> waitingForDayRespawn = ConcurrentHashMap.newKeySet();
    private BukkitTask clearZombieTargetsTask;
    private BukkitTask checkAllDeadTask;
    private boolean isFullGameReset = false;

    public DeathSpectatorManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Помечает, что произойдёт полный рестарт игры (все игроки умерли).
     * При этом скиллы НЕ должны выдаваться при возрождении.
     */
    public void setFullGameResetFlag(boolean reset) {
        this.isFullGameReset = reset;
    }

    public boolean isWaitingForDay(UUID uuid) {
        if (uuid == null)
            return false;
        return waitingForDayRespawn.contains(uuid);
    }

    public void reviveAllAtDayStart() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (waitingForDayRespawn.contains(p.getUniqueId())) {
                reviveNowOrAfterRespawn(p);
            }
        }
        // После того как все игроки возрождены - сбрасываем флаг
        isFullGameReset = false;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p == null)
            return;

        PhaseManager phase = plugin.phase();
        if (phase == null)
            return;

        // По просьбе: смерть во время ночи -> наблюдатель до конца ночи.
        if (phase.getPhase() == PhaseManager.Phase.NIGHT) {
            waitingForDayRespawn.add(p.getUniqueId());
            startClearZombieTargetsTaskIfNeeded();

            // Сообщение игроку: он умер и возродится днём.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline())
                    return;
                try {
                    p.sendMessage("§cТы умер. §7До конца ночи ты будешь в режиме наблюдателя.");
                    p.sendMessage("§aТы возродишься, когда наступит день.");
                } catch (Throwable t) {
                    logSuppressed("send death spectator message", t);
                }
            }, 1L);

            // Авто-респавн, чтобы игрок сразу мог наблюдать.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline())
                    return;
                tryForceRespawn(p);
            }, 2L);
        }

        // Проверяем, умерли ли все игроки (в любое время - день или ночь)
        scheduleCheckIfAllPlayersDead();
    }

    private void scheduleCheckIfAllPlayersDead() {
        if (checkAllDeadTask != null)
            return;
        checkAllDeadTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            checkAllDeadTask = null;
            checkIfAllPlayersDead();
        }, 3L);
    }

    private void checkIfAllPlayersDead() {
        if (plugin.phase() == null)
            return;
        if (plugin.phase().isResetInProgress())
            return;

        // Считаем живых игроков (не spectator и не dead)
        int aliveCount = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null)
                continue;
            // Игрок считается живым, если он не в spectator и не мёртв
            if (p.getGameMode() != GameMode.SPECTATOR && !p.isDead()) {
                aliveCount++;
            }
        }

        // Если на сервере есть игроки, но все мертвы/spectator - сброс игры
        if (aliveCount == 0 && !Bukkit.getOnlinePlayers().isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.phase() == null)
                    return;
                if (plugin.phase().isResetInProgress())
                    return;
                plugin.phase().reset();
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onZombieTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getEntity() instanceof Zombie))
            return;
        LivingEntity target = e.getTarget();
        if (!(target instanceof Player p))
            return;

        // Зомби не должны видеть игроков, которые "мертвы до дня".
        if (!waitingForDayRespawn.contains(p.getUniqueId()))
            return;

        if (p.getGameMode() != GameMode.SPECTATOR)
            return;

        e.setCancelled(true);
        try {
            if (e.getEntity() instanceof Mob m)
                m.setTarget(null);
        } catch (Throwable t) {
            logSuppressed("clear zombie target (living)", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onZombieTargetGeneric(EntityTargetEvent e) {
        if (!(e.getEntity() instanceof Zombie))
            return;
        if (!(e.getTarget() instanceof Player p))
            return;

        if (!waitingForDayRespawn.contains(p.getUniqueId()))
            return;

        if (p.getGameMode() != GameMode.SPECTATOR)
            return;

        e.setCancelled(true);
        try {
            if (e.getEntity() instanceof Mob m)
                m.setTarget(null);
        } catch (Throwable t) {
            logSuppressed("clear zombie target (generic)", t);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (p == null)
            return;

        if (!waitingForDayRespawn.contains(p.getUniqueId()))
            return;

        Location spawn = getSafeSpawn();
        if (spawn != null) {
            e.setRespawnLocation(spawn);
        }

        // После респавна переводим в наблюдателя.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline())
                return;
            try {
                p.setGameMode(GameMode.SPECTATOR);
            } catch (Throwable t) {
                logSuppressed("set spectator on respawn", t);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p == null)
            return;

        if (!waitingForDayRespawn.contains(p.getUniqueId()))
            return;

        PhaseManager phase = plugin.phase();
        if (phase != null && phase.getPhase() == PhaseManager.Phase.DAY) {
            reviveNowOrAfterRespawn(p);
            return;
        }

        // Если всё ещё ночь — держим spectator.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline())
                return;
            try {
                p.setGameMode(GameMode.SPECTATOR);
            } catch (Throwable t) {
                logSuppressed("set spectator on join", t);
            }
        });
    }

    private void reviveNowOrAfterRespawn(Player p) {
        if (p == null)
            return;

        if (p.isDead()) {
            tryForceRespawn(p);
            Bukkit.getScheduler().runTaskLater(plugin, () -> reviveNowOrAfterRespawn(p), 2L);
            return;
        }

        waitingForDayRespawn.remove(p.getUniqueId());
        stopClearZombieTargetsTaskIfPossible();

        try {
            p.setGameMode(GameMode.SURVIVAL);
        } catch (Throwable t) {
            logSuppressed("set survival on revive", t);
        }

        try {
            p.sendMessage("§aНаступил день. Ты возродился!");
        } catch (Throwable t) {
            logSuppressed("send revive message", t);
        }

        try {
            p.setFireTicks(0);
            p.setFoodLevel(20);
            p.setSaturation(5.0f);
            double maxHealth = 20.0;
            if (p.getAttribute(Attribute.MAX_HEALTH) != null) {
                maxHealth = p.getAttribute(Attribute.MAX_HEALTH).getValue();
            }
            p.setHealth(Math.max(1.0, maxHealth));
        } catch (Throwable t) {
            logSuppressed("restore player vitals", t);
        }

        Location spawn = getSafeSpawn();
        if (spawn != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    try {
                        p.teleport(spawn);
                    } catch (Throwable t) {
                        logSuppressed("teleport revived player", t);
                    }
                }
            });
        }

        // После возрождения днём — выдаём разблокированные скиллы (если не полный
        // рестарт)
        if (!isFullGameReset && plugin.specialSkills() != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline())
                    return;
                plugin.specialSkills().giveUnlockedSkillsToPlayer(p);
            }, 5L);
        }

        // После возрождения днём — обязательный повторный выбор кита (если помечен).
        if (plugin.progress() != null && plugin.progress().isKitChoiceRequired(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline())
                    return;
                if (p.getGameMode() == GameMode.SPECTATOR)
                    return;
                plugin.kitMenu().open(p);
            }, 5L);
        }
    }

    private void startClearZombieTargetsTaskIfNeeded() {
        if (clearZombieTargetsTask != null)
            return;
        clearZombieTargetsTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (waitingForDayRespawn.isEmpty()) {
                stopClearZombieTargetsTaskIfPossible();
                return;
            }

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null || !p.isOnline())
                    continue;
                if (p.getGameMode() != GameMode.SPECTATOR)
                    continue;
                if (!waitingForDayRespawn.contains(p.getUniqueId()))
                    continue;

                Location at = p.getLocation();
                World w = at.getWorld();
                if (w == null)
                    continue;

                // Сбрасываем цель у зомби рядом. Это решает случай, когда цель была выставлена
                // раньше и событие не сработало.
                for (var ent : w.getNearbyEntities(at, 64, 64, 64)) {
                    if (!(ent instanceof Zombie z))
                        continue;
                    try {
                        if (z.getTarget() != null && z.getTarget().getUniqueId().equals(p.getUniqueId())) {
                            z.setTarget(null);
                        }
                    } catch (Throwable t) {
                        logSuppressed("periodic zombie target clear", t);
                    }
                }
            }
        }, 10L, 10L);
    }

    private void stopClearZombieTargetsTaskIfPossible() {
        if (clearZombieTargetsTask == null)
            return;
        if (!waitingForDayRespawn.isEmpty())
            return;
        clearZombieTargetsTask.cancel();
        clearZombieTargetsTask = null;
    }

    private Location getSafeSpawn() {
        try {
            if (plugin.base() != null && plugin.base().isEnabled()) {
                Location c = plugin.base().getCenter();
                if (c != null && c.getWorld() != null) {
                    Location spawn = c.clone();
                    int y = spawn.getWorld().getHighestBlockYAt(spawn) + 1;
                    spawn.setY(y);
                    spawn.setX(Math.floor(spawn.getX()) + 0.5);
                    spawn.setZ(Math.floor(spawn.getZ()) + 0.5);
                    return spawn;
                }
            }
        } catch (Throwable t) {
            logSuppressed("resolve safe spawn", t);
        }

        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return (w == null) ? null : w.getSpawnLocation();
    }

    private void tryForceRespawn(Player p) {
        if (p == null || !p.isOnline())
            return;

        // В некоторых API-версиях может не быть Player.Spigot#respawn().
        // Используем рефлексию, чтобы сборка не падала.
        try {
            Object spigot = p.spigot();
            spigot.getClass().getMethod("respawn").invoke(spigot);
            return;
        } catch (Throwable t) {
            logSuppressed("force respawn via spigot", t);
        }

        // Paper может иметь прямой Player#respawn().
        try {
            p.getClass().getMethod("respawn").invoke(p);
        } catch (Throwable t) {
            logSuppressed("force respawn via direct method", t);
        }
    }

    private void logSuppressed(String context, Throwable t) {
        if (t == null)
            return;
        plugin.getLogger().fine("[DeathSpectator] " + context + ": "
                + t.getClass().getSimpleName() + " - " + t.getMessage());
    }
}
