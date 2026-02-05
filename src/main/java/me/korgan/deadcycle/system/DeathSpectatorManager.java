package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.phase.PhaseManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
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

    public DeathSpectatorManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
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
        if (phase.getPhase() != PhaseManager.Phase.NIGHT)
            return;

        waitingForDayRespawn.add(p.getUniqueId());
        startClearZombieTargetsTaskIfNeeded();

        // Сообщение игроку: он умер и возродится днём.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline())
                return;
            try {
                p.sendMessage("§cТы умер. §7До конца ночи ты будешь в режиме наблюдателя.");
                p.sendMessage("§aТы возродишься, когда наступит день.");
            } catch (Throwable ignored) {
            }
        }, 1L);

        // Авто-респавн, чтобы игрок сразу мог наблюдать.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline())
                return;
            tryForceRespawn(p);
        }, 2L);
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
            } catch (Throwable ignored) {
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
            } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }

        try {
            p.sendMessage("§aНаступил день. Ты возродился!");
        } catch (Throwable ignored) {
        }

        try {
            p.setFireTicks(0);
            p.setFoodLevel(20);
            p.setSaturation(5.0f);
            p.setHealth(Math.max(1.0, p.getMaxHealth()));
        } catch (Throwable ignored) {
        }

        Location spawn = getSafeSpawn();
        if (spawn != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    try {
                        p.teleport(spawn);
                    } catch (Throwable ignored) {
                    }
                }
            });
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
                    } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }

        // Paper может иметь прямой Player#respawn().
        try {
            p.getClass().getMethod("respawn").invoke(p);
        } catch (Throwable ignored) {
        }
    }
}
