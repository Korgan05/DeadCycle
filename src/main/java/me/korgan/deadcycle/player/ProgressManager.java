package me.korgan.deadcycle.player;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

public class ProgressManager {

    private final DeadCyclePlugin plugin;
    private final PlayerDataStore store;

    public ProgressManager(DeadCyclePlugin plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // ======================
    //  ОБЩИЙ УРОВЕНЬ ИГРОКА
    // ======================
    public int getPlayerLevel(UUID uuid) {
        return store.getInt(uuid, "player.level", 1);
    }

    public int getPlayerExp(UUID uuid) {
        return store.getInt(uuid, "player.exp", 0);
    }

    public int needPlayerExp(UUID uuid) {
        int lvl = getPlayerLevel(uuid);
        // простая формула
        return 100 + (lvl - 1) * 80;
    }

    // пока просто база — будем накидывать опыт в будущем (за волны/победы и т.д.)
    public void addPlayerExp(Player p, int amount) {
        UUID uuid = p.getUniqueId();
        int lvl = getPlayerLevel(uuid);
        int exp = getPlayerExp(uuid);

        exp += Math.max(1, amount);

        while (true) {
            int need = needPlayerExp(uuid);
            if (exp < need) break;
            exp -= need;
            lvl++;
            store.setInt(uuid, "player.level", lvl);
        }

        store.setInt(uuid, "player.exp", exp);
        store.save();
    }

    // ======================
    //  УРОВЕНЬ КИТА MINER
    // ======================
    public int getMinerLevel(UUID uuid) {
        return store.getInt(uuid, "miner.level", 1);
    }

    public int getMinerExp(UUID uuid) {
        return store.getInt(uuid, "miner.exp", 0);
    }

    public int needMinerExp(UUID uuid) {
        int lvl = getMinerLevel(uuid);
        int base = plugin.getConfig().getInt("miner_progress.level_xp_base", 80);
        int add  = plugin.getConfig().getInt("miner_progress.level_xp_add_per_level", 40);
        return base + (lvl - 1) * add;
    }

    public void addMinerExp(Player p, int amount) {
        if (!plugin.getConfig().getBoolean("miner_progress.enabled", true)) return;

        UUID uuid = p.getUniqueId();
        int maxLevel = plugin.getConfig().getInt("miner_progress.max_level", 10);

        int lvl = getMinerLevel(uuid);
        int exp = getMinerExp(uuid);

        if (lvl >= maxLevel) return;

        exp += Math.max(1, amount);

        while (lvl < maxLevel) {
            int need = needMinerExp(uuid);
            if (exp < need) break;
            exp -= need;
            lvl++;
            store.setInt(uuid, "miner.level", lvl);
        }

        store.setInt(uuid, "miner.exp", exp);
        store.save();

        applyKitEffects(p);
    }

    // ======================
    //  УНИВЕРСАЛЬНЫЙ ЛВЛ КИТА
    // (пока реальный лвл есть только у MINER; остальным вернём 1)
    // ======================
    public int getKitLevel(UUID uuid, KitManager.Kit kit) {
        if (kit == null) return 0;
        if (kit == KitManager.Kit.MINER) return getMinerLevel(uuid);
        return 1;
    }

    // ======================
    //  ЭФФЕКТЫ ОТ КИТОВ
    // ======================
    public void applyKitEffects(Player p) {
        KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
        if (kit == null) return;

        // Майнер: Haste от уровня
        if (kit == KitManager.Kit.MINER) {
            int lvl = getMinerLevel(p.getUniqueId());

            // каждые 2 уровня +1 Haste: 1-2=Haste I, 3-4=Haste II, ...
            int cap = plugin.getConfig().getInt("miner_progress.haste_max_level", 4);
            int amp = Math.min(cap, Math.max(0, (lvl - 1) / 2));

            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 20 * 70, amp, true, false, true));
        }
    }
    public int getKitExp(UUID uuid, KitManager.Kit kit) {
        if (kit == null) return 0;
        if (kit == KitManager.Kit.MINER) return getMinerExp(uuid);
        return 0;
    }

    public int getKitNeedExp(UUID uuid, KitManager.Kit kit) {
        if (kit == null) return 0;
        if (kit == KitManager.Kit.MINER) return needMinerExp(uuid);
        return 0;
    }

}

