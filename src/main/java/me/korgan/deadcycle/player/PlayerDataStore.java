package me.korgan.deadcycle.player;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class PlayerDataStore {

    private final DeadCyclePlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    public PlayerDataStore(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public int getInt(UUID uuid, String path, int def) {
        return data.getInt(uuid + "." + path, def);
    }

    public void setInt(UUID uuid, String path, int val) {
        data.set(uuid + "." + path, val);
    }

    public String getString(UUID uuid, String path, String def) {
        return data.getString(uuid + "." + path, def);
    }

    public void setString(UUID uuid, String path, String val) {
        data.set(uuid + "." + path, val);
    }

    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save players.yml: " + e.getMessage());
        }
    }
}
