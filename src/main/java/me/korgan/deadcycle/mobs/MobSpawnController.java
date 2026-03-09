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
    private final NamespacedKey miniBossKey;
    private final NamespacedKey cloneKey;
    private final NamespacedKey summonerKey;

    public MobSpawnController(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.zombieKey = plugin.zombie().zombieMarkKey();
        this.bossKey = plugin.bossDuel().bossMarkKey();
        this.minionKey = plugin.bossDuel().minionMarkKey();
        this.miniBossKey = plugin.miniBoss().miniBossMarkKey();
        this.cloneKey = plugin.cloneKit().cloneMarkKey();
        this.summonerKey = plugin.summonerKit().summonMarkKey();
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        Entity ent = e.getEntity();

        Byte cloneMarkAny = ent.getPersistentDataContainer().get(cloneKey, PersistentDataType.BYTE);
        if (cloneMarkAny != null && cloneMarkAny == (byte) 1)
            return;

        Byte summonerMarkAny = ent.getPersistentDataContainer().get(summonerKey, PersistentDataType.BYTE);
        if (summonerMarkAny != null && summonerMarkAny == (byte) 1)
            return;

        if (plugin.cloneKit().isCloneSpawning() && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM)
            return;
        if (plugin.summonerKit().isSummonSpawning() && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM)
            return;

        // Разрешаем только наших зомби
        if (ent.getType() == EntityType.ZOMBIE) {
            Byte mark = ent.getPersistentDataContainer().get(zombieKey, PersistentDataType.BYTE);

            Byte bossMark = ent.getPersistentDataContainer().get(bossKey, PersistentDataType.BYTE);
            if (bossMark != null && bossMark == (byte) 1)
                return;

            Byte minionMark = ent.getPersistentDataContainer().get(minionKey, PersistentDataType.BYTE);
            if (minionMark != null && minionMark == (byte) 1)
                return;

            Byte miniBossMark = ent.getPersistentDataContainer().get(miniBossKey, PersistentDataType.BYTE);
            if (miniBossMark != null && miniBossMark == (byte) 1)
                return;

            Byte cloneMark = ent.getPersistentDataContainer().get(cloneKey, PersistentDataType.BYTE);
            if (cloneMark != null && cloneMark == (byte) 1)
                return;

            Byte summonerMark = ent.getPersistentDataContainer().get(summonerKey, PersistentDataType.BYTE);
            if (summonerMark != null && summonerMark == (byte) 1)
                return;

            // Если успели пометить — разрешаем
            if (mark != null && mark == (byte) 1)
                return;

            // На всякий случай: если это момент нашего спавна — тоже разрешаем
            if (plugin.zombie().isPluginSpawning() && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM)
                return;

            if (plugin.bossDuel().isBossSpawning() && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM)
                return;

            if (plugin.miniBoss().isMiniBossSpawning() && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM)
                return;
        }

        // Всё остальное — запрещаем
        e.setCancelled(true);
    }
}
