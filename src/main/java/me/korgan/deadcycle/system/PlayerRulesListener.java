package me.korgan.deadcycle.system;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.PlayerInventory;

public class PlayerRulesListener implements Listener {

    private final DeadCyclePlugin plugin;

    public PlayerRulesListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    // после смерти вещи НЕ выпадают
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();

        // По просьбе: вещи пропадают после смерти и не падают.
        e.setDroppedExp(0);
        e.setKeepLevel(true);
        e.setKeepInventory(true);
        e.getDrops().clear();

        // По просьбе: после возрождения обязателен повторный выбор кита.
        try {
            plugin.progress().setKitChoiceRequired(p.getUniqueId(), true);
        } catch (Throwable ignored) {
        }

        // Стираем инвентарь/броню/оффхенд, чтобы ничего не сохранялось.
        Bukkit.getScheduler().runTask(plugin, () -> clearAll(p));
    }

    private void clearAll(Player p) {
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

    // респавн на базе (если база включена)
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (plugin.base() != null && plugin.base().isEnabled()) {
            Location spawn = getBaseSpawn();
            if (spawn != null) {
                e.setRespawnLocation(spawn);
            }
        }

        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p == null || !p.isOnline())
                return;

            // гарантированно чистый инвентарь после смерти
            clearAll(p);

            // Если игрок "мертв до дня" (spectator ночью) — меню откроется при дневном
            // revive.
            boolean waiting = false;
            try {
                waiting = plugin.deathSpectator() != null
                        && plugin.deathSpectator().isWaitingForDay(p.getUniqueId());
            } catch (Throwable ignored) {
            }
            if (waiting)
                return;

            boolean mustChoose = false;
            try {
                mustChoose = plugin.progress() != null
                        && plugin.progress().isKitChoiceRequired(p.getUniqueId());
            } catch (Throwable ignored) {
            }
            if (!mustChoose)
                return;

            if (p.getGameMode() == GameMode.SPECTATOR)
                return;

            plugin.kitMenu().open(p);
        }, 2L);
    }

    // при заходе — телепорт на базу (если включена)
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        if (mustWipeInventoryAfterReset(p)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline())
                    return;

                clearAll(p);
                markInventoryWipedForReset(p);

                if (plugin.mana() != null) {
                    int max = plugin.mana().getMaxXp(p.getUniqueId());
                    plugin.mana().setCurrentXp(p, max);
                }
            });
        }

        if (!plugin.base().isEnabled())
            return;
        Location spawn = getBaseSpawn();
        if (spawn == null)
            return;

        // чуть позже, чтобы игрок успел прогрузиться
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline())
                p.teleport(spawn);
        }, 10L);
    }

    private boolean mustWipeInventoryAfterReset(Player p) {
        if (p == null || plugin.playerData() == null)
            return false;

        int resetVersion = Math.max(0, plugin.getConfig().getInt("runtime.full_reset_version", 0));
        if (resetVersion <= 0)
            return false;

        int wipedVersion = plugin.playerData().getInt(
                p.getUniqueId(),
                "meta.last_inventory_wipe_reset_version",
                0);

        return wipedVersion < resetVersion;
    }

    private void markInventoryWipedForReset(Player p) {
        if (p == null || plugin.playerData() == null)
            return;

        int resetVersion = Math.max(0, plugin.getConfig().getInt("runtime.full_reset_version", 0));
        plugin.playerData().setInt(p.getUniqueId(), "meta.last_inventory_wipe_reset_version", resetVersion);
        plugin.playerData().save();
    }

    private Location getBaseSpawn() {
        Location c = plugin.base().getCenter();
        if (c == null)
            return null;

        // спавним на 1 блок выше центра + корректная высота
        Location spawn = c.clone();

        // поднимаем на безопасную высоту
        // (если центр в земле, будет поднято)
        int y = spawn.getWorld().getHighestBlockYAt(spawn) + 1;
        spawn.setY(y);

        // чуть сместим в центр блока
        spawn.setX(Math.floor(spawn.getX()) + 0.5);
        spawn.setZ(Math.floor(spawn.getZ()) + 0.5);

        return spawn;
    }

    // когда игрок ест гнилую плоть (выпадает с зомби) — выдаем опыт
    @EventHandler
    public void onPlayerConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();

        // Проверяем, что это гнилая плоть
        if (e.getItem().getType() != Material.ROTTEN_FLESH)
            return;

        // Выдаем опыт (работает для всех китов)
        try {
            int xp = plugin.getConfig().getInt("economy.rotten_flesh_xp", 5);
            p.setLevel(p.getLevel() + xp);
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerVsPlayerDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim))
            return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (e.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker == null)
            return;
        if (attacker.getUniqueId().equals(victim.getUniqueId()))
            return;

        e.setCancelled(true);
    }
}
