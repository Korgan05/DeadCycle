package me.korgan.deadcycle.econ;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class EconomyManager implements Listener {

    private static final class RewardWindowState {
        long windowStartedAtMs;
        long rewardedInWindow;

        RewardWindowState(long windowStartedAtMs) {
            this.windowStartedAtMs = windowStartedAtMs;
            this.rewardedInWindow = 0L;
        }
    }

    private final DeadCyclePlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, RewardWindowState> killRewardWindows = new HashMap<>();
    private final Map<UUID, Long> rewardCapNoticeUntil = new HashMap<>();

    public EconomyManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "economy.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public long getMoney(UUID uuid) {
        return data.getLong("money." + uuid, plugin.getConfig().getLong("economy.start_money", 0));
    }

    public void addMoney(UUID uuid, long amount) {
        long cur = getMoney(uuid);
        data.set("money." + uuid, Math.max(0, cur + amount));
    }

    public boolean has(Player p, long amount) {
        return getMoney(p.getUniqueId()) >= amount;
    }

    public void take(Player p, long amount) {
        addMoney(p.getUniqueId(), -amount);
        save();
    }

    public void give(Player p, long amount) {
        addMoney(p.getUniqueId(), amount);
        save();
    }

    public void clearAll() {
        data.set("money", null);
        killRewardWindows.clear();
        rewardCapNoticeUntil.clear();
        save();
    }

    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save economy.yml: " + e.getMessage());
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null)
            return;

        if (!e.getEntityType().name().equalsIgnoreCase("ZOMBIE"))
            return;

        Zombie zombie = (Zombie) e.getEntity();
        if (isMiniBoss(zombie) || isBoss(zombie))
            return;

        long reward = Math.max(0L, plugin.getConfig().getLong("economy.kill_zombie_reward", 5));
        long grantedReward = applyKillRewardCap(killer, reward);
        if (grantedReward > 0) {
            give(killer, grantedReward);
            killer.sendMessage("§aЗомби убит! +§6" + grantedReward + "$");
        }

        int playerExp = plugin.getConfig().getInt("player_progress.kill_exp.zombie", 2);
        if (playerExp > 0) {
            plugin.progress().addPlayerExp(killer, playerExp);
        }

        // XP бойцу за убийство
        KitManager.Kit kit = plugin.kit().getKit(killer.getUniqueId());
        if (kit == KitManager.Kit.FIGHTER) {
            int exp = plugin.getConfig().getInt("kit_xp.fighter.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addFighterExp(killer, exp);
        }

        // XP берсерку за убийство
        if (kit == KitManager.Kit.BERSERK) {
            int exp = plugin.getConfig().getInt("kit_xp.berserk.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addBerserkExp(killer, exp);
        }

        // XP лучнику за убийство
        if (kit == KitManager.Kit.ARCHER) {
            int exp = plugin.getConfig().getInt("kit_xp.archer.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addArcherExp(killer, exp);
        }

        // XP гравитатору за убийство
        if (kit == KitManager.Kit.GRAVITATOR) {
            int exp = plugin.getConfig().getInt("kit_xp.gravitator.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addGravitatorExp(killer, exp);
        }

        // XP ритуалисту за убийство
        if (kit == KitManager.Kit.DUELIST) {
            int exp = plugin.getConfig().getInt("kit_xp.duelist.exp_per_zombie", 1);
            if (exp > 0)
                plugin.progress().addDuelistExp(killer, exp);
        }

        // XP клонеру за убийство (дополнительно к XP за убийства клонов)
        if (kit == KitManager.Kit.CLONER) {
            int exp = plugin.getConfig().getInt("kit_xp.cloner.exp_per_zombie", 1);
            if (exp > 0)
                plugin.progress().addClonerExp(killer, exp);
        }

        // XP призывателю за прямое убийство
        if (kit == KitManager.Kit.SUMMONER) {
            int exp = plugin.getConfig().getInt("kit_xp.summoner.exp_per_zombie", 1);
            if (exp > 0)
                plugin.progress().addSummonerExp(killer, exp);
        }

        // XP пингу за убийство
        if (kit == KitManager.Kit.PING) {
            int exp = plugin.getConfig().getInt("kit_xp.ping.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addPingExp(killer, exp);
        }

        // XP гарпунеру за убийство
        if (kit == KitManager.Kit.HARPOONER) {
            int exp = plugin.getConfig().getInt("kit_xp.harpooner.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addHarpoonerExp(killer, exp);
        }

        // XP киборгу за убийство
        if (kit == KitManager.Kit.CYBORG) {
            int exp = plugin.getConfig().getInt("kit_xp.cyborg.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addCyborgExp(killer, exp);
        }

        // XP медику за убийство
        if (kit == KitManager.Kit.MEDIC) {
            int exp = plugin.getConfig().getInt("kit_xp.medic.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addMedicExp(killer, exp);
        }

        // XP экзорцисту за убийство
        if (kit == KitManager.Kit.EXORCIST) {
            int exp = plugin.getConfig().getInt("kit_xp.exorcist.exp_per_zombie", 2);
            if (exp > 0)
                plugin.progress().addExorcistExp(killer, exp);
        }
    }

    private long applyKillRewardCap(Player killer, long baseReward) {
        if (baseReward <= 0L)
            return 0L;

        long capPerWindow = plugin.getConfig().getLong("economy.kill_reward_cap.max_reward_in_window", 0L);
        if (capPerWindow <= 0L)
            return baseReward;

        long windowSeconds = Math.max(5L, plugin.getConfig().getLong("economy.kill_reward_cap.window_seconds", 60L));
        long windowMs = windowSeconds * 1000L;
        long now = System.currentTimeMillis();
        UUID playerId = killer.getUniqueId();

        RewardWindowState state = killRewardWindows.computeIfAbsent(playerId, ignored -> new RewardWindowState(now));
        if (now - state.windowStartedAtMs >= windowMs) {
            state.windowStartedAtMs = now;
            state.rewardedInWindow = 0L;
        }

        long remaining = capPerWindow - state.rewardedInWindow;
        if (remaining <= 0L) {
            maybeNotifyRewardCap(killer, capPerWindow, windowSeconds, now);
            cleanupOldRewardWindows(now, windowMs * 3L);
            return 0L;
        }

        long granted = Math.min(baseReward, remaining);
        state.rewardedInWindow += granted;

        if (granted < baseReward) {
            maybeNotifyRewardCap(killer, capPerWindow, windowSeconds, now);
        }

        cleanupOldRewardWindows(now, windowMs * 3L);
        return granted;
    }

    private void maybeNotifyRewardCap(Player killer, long capPerWindow, long windowSeconds, long now) {
        UUID playerId = killer.getUniqueId();
        long noticeUntil = rewardCapNoticeUntil.getOrDefault(playerId, 0L);
        if (now < noticeUntil)
            return;

        rewardCapNoticeUntil.put(playerId, now + 5000L);
        killer.sendMessage("§7[Экономика] Лимит наград: §f" + capPerWindow + "$§7/" + windowSeconds + "с.");
    }

    private void cleanupOldRewardWindows(long now, long staleAfterMs) {
        if (killRewardWindows.isEmpty())
            return;

        Iterator<Map.Entry<UUID, RewardWindowState>> iterator = killRewardWindows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RewardWindowState> entry = iterator.next();
            if (now - entry.getValue().windowStartedAtMs > staleAfterMs) {
                iterator.remove();
                rewardCapNoticeUntil.remove(entry.getKey());
            }
        }
    }

    private boolean isMiniBoss(Zombie zombie) {
        return plugin.miniBoss() != null
                && zombie.getPersistentDataContainer().has(plugin.miniBoss().miniBossMarkKey(),
                        PersistentDataType.BYTE);
    }

    private boolean isBoss(Zombie zombie) {
        return plugin.bossDuel() != null
                && zombie.getPersistentDataContainer().has(plugin.bossDuel().bossMarkKey(), PersistentDataType.BYTE);
    }
}
