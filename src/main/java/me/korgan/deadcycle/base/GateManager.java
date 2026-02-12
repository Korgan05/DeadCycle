package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GateManager {

    private final DeadCyclePlugin plugin;
    private final List<Gate> gates = new ArrayList<>();

    private final File file;
    private FileConfiguration cfg;

    private final java.util.Map<UUID, Long> toggleCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    public GateManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "gates.yml");
        load();
    }

    public List<Gate> getGates() {
        return gates;
    }

    public void load() {
        if (!file.exists()) {
            cfg = YamlConfiguration.loadConfiguration(file);
            cfg.set("gates", new ArrayList<>());
            save();
            return;
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        gates.clear();
        if (!cfg.isSet("gates"))
            return;

        for (Object o : cfg.getList("gates")) {
            if (!(o instanceof org.bukkit.configuration.ConfigurationSection))
                continue;
        }

        var list = cfg.getList("gates");
        if (list == null)
            return;

        for (int i = 0; i < list.size(); i++) {
            Object raw = list.get(i);
            if (!(raw instanceof java.util.Map))
                continue;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) raw;
            String world = (String) m.get("world");
            if (world == null)
                continue;
            Gate g = new Gate(world);
            Object blocksObj = m.get("blocks");
            if (blocksObj instanceof List) {
                for (Object bo : (List<?>) blocksObj) {
                    if (!(bo instanceof java.util.Map))
                        continue;
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> bm = (java.util.Map<String, Object>) bo;
                    Number x = (Number) bm.get("x");
                    Number y = (Number) bm.get("y");
                    Number z = (Number) bm.get("z");
                    if (x == null || y == null || z == null)
                        continue;
                    g.addBlock(x.intValue(), y.intValue(), z.intValue());
                }
            }
            Object open = m.get("open");
            if (open instanceof Boolean)
                g.setOpen((Boolean) open);
            gates.add(g);
        }
    }

    /**
     * Find nearest gate which has any block within maxDistance blocks of loc.
     */
    public Gate findNearestGate(Location loc, int maxDistance) {
        if (loc == null)
            return null;
        double md2 = (double) maxDistance * maxDistance;
        Gate best = null;
        double bestD = Double.MAX_VALUE;
        for (Gate g : gates) {
            for (int[] a : g.getBlocks()) {
                double dx = (a[0] + 0.5) - loc.getX();
                double dy = (a[1] + 0.5) - loc.getY();
                double dz = (a[2] + 0.5) - loc.getZ();
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 <= md2 && d2 < bestD) {
                    bestD = d2;
                    best = g;
                }
            }
        }
        return best;
    }

    public void save() {
        if (cfg == null)
            cfg = YamlConfiguration.loadConfiguration(file);
        List<Object> out = new ArrayList<>();
        for (Gate g : gates) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("world", g.getWorldName());
            List<Object> bl = new ArrayList<>();
            for (int[] a : g.getBlocks()) {
                java.util.Map<String, Integer> bm = new java.util.LinkedHashMap<>();
                bm.put("x", a[0]);
                bm.put("y", a[1]);
                bm.put("z", a[2]);
                bl.add(bm);
            }
            m.put("blocks", bl);
            m.put("open", g.isOpen());
            out.add(m);
        }
        cfg.set("gates", out);
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save gates.yml: " + e.getMessage());
        }
    }

    public void addGate(Gate g) {
        gates.add(g);
        save();
    }

    public void removeGate(Gate g) {
        gates.remove(g);
        save();
    }

    public Gate findGateContaining(Location loc) {
        if (loc == null)
            return null;
        for (Gate g : gates) {
            if (g.contains(loc))
                return g;
        }
        return null;
    }

    public void openAll() {
        for (Gate g : gates) {
            openGate(g);
        }
        save();
    }

    public void closeAll() {
        for (Gate g : gates) {
            closeGate(g);
        }
        save();
    }

    // Scan base ring and remove fence blocks (day open)
    private void removeFencesOnBase() {
        if (!plugin.base().isEnabled())
            return;
        var center = plugin.base().getCenter();
        if (center == null || center.getWorld() == null)
            return;

        World w = center.getWorld();
        int radius = plugin.base().getRadius();
        int band = 2;
        int rMin = Math.max(1, radius - band);
        int rMax = radius + band;

        int yMin = center.getBlockY() + plugin.getConfig().getInt("wall_upgrade.scan_y_min_offset", -5);
        int yMax = center.getBlockY() + plugin.getConfig().getInt("wall_upgrade.scan_y_max_offset", 15);

        int rMaxSq = rMax * rMax;

        for (int x = center.getBlockX() - rMax; x <= center.getBlockX() + rMax; x++) {
            for (int z = center.getBlockZ() - rMax; z <= center.getBlockZ() + rMax; z++) {
                double dx = (x + 0.5) - center.getX();
                double dz = (z + 0.5) - center.getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 < (double) rMin * rMin || d2 > (double) rMax * rMax)
                    continue;

                for (int y = yMin; y <= yMax; y++) {
                    var b = w.getBlockAt(x, y, z);
                    var m = b.getType();
                    String name = m.name();
                    if (name.contains("_FENCE") || name.endsWith("_FENCE") || m == Material.OAK_FENCE) {
                        b.setType(Material.AIR, false);
                        plugin.blocks().clearStateAt(b.getLocation());
                    }
                }
            }
        }
    }

    // Scan base ring and restore fence/wall material on night
    private void restoreWallsOnBase() {
        if (!plugin.base().isEnabled())
            return;
        var center = plugin.base().getCenter();
        if (center == null || center.getWorld() == null)
            return;

        World w = center.getWorld();
        int radius = plugin.base().getRadius();
        int band = 2;
        int rMin = Math.max(1, radius - band);
        int rMax = radius + band;

        int yMin = center.getBlockY() + plugin.getConfig().getInt("wall_upgrade.scan_y_min_offset", -5);
        int yMax = center.getBlockY() + plugin.getConfig().getInt("wall_upgrade.scan_y_max_offset", 15);

        int level = plugin.getConfig().getInt("base.wall_level", 1);
        Material mat = resolveWallMaterial(level);

        for (int x = center.getBlockX() - rMax; x <= center.getBlockX() + rMax; x++) {
            for (int z = center.getBlockZ() - rMax; z <= center.getBlockZ() + rMax; z++) {
                double dx = (x + 0.5) - center.getX();
                double dz = (z + 0.5) - center.getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 < (double) rMin * rMin || d2 > (double) rMax * rMax)
                    continue;

                for (int y = yMin; y <= yMax; y++) {
                    var b = w.getBlockAt(x, y, z);
                    if (b.getType() == Material.AIR) {
                        b.setType(mat, false);
                        plugin.blocks().clearStateAt(b.getLocation());
                    }
                }
            }
        }
    }

    public void toggleGate(Gate g) {
        if (g == null)
            return;
        if (g.isOpen())
            closeGate(g);
        else
            openGate(g);
        save();
    }

    public void openGate(Gate g) {
        if (g == null)
            return;
        World w = Bukkit.getWorld(g.getWorldName());
        if (w == null)
            return;
        for (int[] a : g.getBlocks()) {
            org.bukkit.block.Block b = w.getBlockAt(a[0], a[1], a[2]);
            b.setType(Material.AIR, false);
            plugin.blocks().clearStateAt(b.getLocation());
        }
        g.setOpen(true);
    }

    public void closeGate(Gate g) {
        if (g == null)
            return;
        World w = Bukkit.getWorld(g.getWorldName());
        if (w == null)
            return;
        int level = plugin.getConfig().getInt("base.wall_level", 1);
        Material mat = resolveWallMaterial(level);
        for (int[] a : g.getBlocks()) {
            org.bukkit.block.Block b = w.getBlockAt(a[0], a[1], a[2]);
            b.setType(mat, false);
            plugin.blocks().clearStateAt(b.getLocation());
        }
        g.setOpen(false);
    }

    private Material resolveWallMaterial(int level) {
        String key = "wall_upgrade.levels.l" + level;
        String raw = plugin.getConfig().getString(key);
        Material mat = (raw == null) ? null : Material.matchMaterial(raw);
        if (mat != null)
            return mat;
        return switch (level) {
            case 1 -> Material.OAK_PLANKS;
            case 2 -> Material.SPRUCE_PLANKS;
            case 3 -> Material.COBBLESTONE;
            case 4 -> Material.STONE;
            case 5 -> Material.STONE_BRICKS;
            default -> Material.OAK_PLANKS;
        };
    }

    public boolean canToggle(UUID player) {
        if (player == null)
            return false;
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("gates.toggle_cooldown_seconds", 5L) * 1000L;
        Long prev = toggleCooldown.get(player);
        if (prev == null || now - prev >= cd) {
            toggleCooldown.put(player, now);
            return true;
        }
        return false;
    }

    public void onDayStart() {
        if (!plugin.getConfig().getBoolean("gates.enabled", true))
            return;
        openAll();
        // also remove fences that form the visible walls on the base ring
        removeFencesOnBase();
    }

    public void onNightStart() {
        if (!plugin.getConfig().getBoolean("gates.enabled", true))
            return;
        closeAll();
        // restore walls/fences around base
        restoreWallsOnBase();
    }
}
