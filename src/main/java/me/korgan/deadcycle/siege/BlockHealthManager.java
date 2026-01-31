package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class BlockHealthManager {

    private final DeadCyclePlugin plugin;

    private final Map<Long, Integer> hp = new HashMap<>();
    private final Map<Long, Integer> maxHp = new HashMap<>();

    public BlockHealthManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        // ничего хранить не нужно — hp в памяти
    }

    private long key(Block b) {
        int x = b.getX();
        int y = b.getY();
        int z = b.getZ();
        // простая упаковка координат в long
        long k = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
        return k;
    }

    public int getMaxHp(Material m) {
        return plugin.getConfig().getInt("blocks_hp." + m.name(), 0);
    }

    public boolean isBreakable(Material m) {
        boolean whitelistOnly = plugin.getConfig().getBoolean("siege.whitelist_only", true);
        int mhp = getMaxHp(m);
        if (whitelistOnly) return mhp > 0;
        // если не whitelist-only, ломаем всё кроме “не ломаемых”
        return m.isBlock() && m.isSolid();
    }

    public void damage(Block b, int amount) {
        if (b == null) return;
        if (b.getType() == Material.AIR) return;

        Material m = b.getType();
        int mhp = getMaxHp(m);
        if (mhp <= 0) return;

        long k = key(b);

        int curMax = maxHp.getOrDefault(k, mhp);
        maxHp.putIfAbsent(k, curMax);

        int cur = hp.getOrDefault(k, curMax);
        cur -= Math.max(1, amount);

        if (cur <= 0) {
            breakBlockNoDrops(b);
            hp.remove(k);
            maxHp.remove(k);
            clearCracks(b);
            return;
        }

        hp.put(k, cur);
        showCracks(b, cur, curMax);
    }

    public void repair(Block b, int amount) {
        if (b == null) return;
        if (b.getType() == Material.AIR) return;

        Material m = b.getType();
        int mhp = getMaxHp(m);
        if (mhp <= 0) return;

        long k = key(b);

        int curMax = maxHp.getOrDefault(k, mhp);
        maxHp.putIfAbsent(k, curMax);

        int cur = hp.getOrDefault(k, curMax);
        cur += Math.max(1, amount);
        if (cur >= curMax) {
            cur = curMax;
        }

        hp.put(k, cur);
        showCracks(b, cur, curMax);
        if (cur == curMax) {
            // полностью починили — убираем трещины
            hp.remove(k);
            maxHp.remove(k);
            clearCracks(b);
        }
    }

    private void breakBlockNoDrops(Block b) {
        boolean noDrops = plugin.getConfig().getBoolean("siege.break_no_drops", true);
        if (noDrops) {
            b.setType(Material.AIR, false);
        } else {
            b.breakNaturally();
        }
    }

    private void showCracks(Block b, int cur, int max) {
        float progress = 1.0f - (cur / (float) max); // 0..1
        progress = Math.max(0f, Math.min(1f, progress));

        // показываем трещины игрокам рядом
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;
            if (p.getWorld() != b.getWorld()) continue;
            if (p.getLocation().distanceSquared(b.getLocation()) > (35 * 35)) continue;

            p.sendBlockDamage(b.getLocation(), progress);
        }
    }

    private void clearCracks(Block b) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;
            if (p.getWorld() != b.getWorld()) continue;
            if (p.getLocation().distanceSquared(b.getLocation()) > (35 * 35)) continue;

            p.sendBlockDamage(b.getLocation(), 0f);
        }
    }
}
