package me.korgan.deadcycle.econ;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class EconomyManager implements Listener {

    private final DeadCyclePlugin plugin;
    private final File file;
    private final YamlConfiguration data;

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
        if (killer == null) return;

        if (e.getEntityType().name().equalsIgnoreCase("ZOMBIE")) {
            long reward = plugin.getConfig().getLong("economy.kill_zombie_reward", 5);
            give(killer, reward);
        }
    }
}
