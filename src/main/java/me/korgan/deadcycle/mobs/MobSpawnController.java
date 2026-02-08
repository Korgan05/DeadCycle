package me.korgan.deadcycle.mobs;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

public class MobSpawnController implements Listener {

    private final DeadCyclePlugin plugin;
    private final NamespacedKey zombieKey;
    private final NamespacedKey bossKey;
    private final NamespacedKey minionKey;

    public MobSpawnController(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.zombieKey = plugin.zombie().zombieMarkKey();
        this.bossKey = plugin.bossDuel().bossMarkKey();
        this.minionKey = plugin.bossDuel().minionMarkKey();
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        Entity ent = e.getEntity();

        // Разрешаем только наших зомби
        if (ent.getType() == EntityType.ZOMBIE) {
            Byte mark = ent.getPersistentDataContainer().get(zombieKey, PersistentDataType.BYTE);

            Byte bossMark = ent.getPersistentDataContainer().get(bossKey, PersistentDataType.BYTE);
            if (bossMark != null && bossMark == (byte) 1) return;

            Byte minionMark = ent.getPersistentDataContainer().get(minionKey, PersistentDataType.BYTE);
            if (minionMark != null && minionMark == (byte) 1) return;

            // Если успели пометить — разрешаем
            if (mark != null && mark == (byte) 1) return;

            // На всякий случай: если это момент нашего спавна — тоже разрешаем
            if (plugin.zombie().isPluginSpawning() && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;

            if (plugin.bossDuel().isBossSpawning() && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        }

        // Всё остальное — запрещаем
        e.setCancelled(true);
    }
}
