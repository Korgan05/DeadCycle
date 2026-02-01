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

    // hp для поврежденных блоков (которые еще существуют)
    private final Map<Long, Integer> hp = new HashMap<>();
    private final Map<Long, Integer> maxHp = new HashMap<>();

    // НОВОЕ: сломанные блоки (AIR), которые можно восстановить ремонтом
    private final Map<Long, BrokenInfo> broken = new HashMap<>();

    public BlockHealthManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    private static class BrokenInfo {
        final Material original;
        final int maxHp;

        BrokenInfo(Material original, int maxHp) {
            this.original = original;
            this.maxHp = maxHp;
        }
    }

    private long key(Block b) {
        int x = b.getX();
        int y = b.getY();
        int z = b.getZ();
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) y & 0xFFFL);
    }

    public int getMaxHp(Material m) {
        int base = plugin.getConfig().getInt("blocks_hp." + m.name(), 0);
        if (base <= 0) return 0;

        double mult = plugin.upgrades().wallHpMultiplier();
        return (int) Math.round(base * mult);
    }

    public boolean isBreakable(Material m) {
        boolean whitelistOnly = plugin.getConfig().getBoolean("siege.whitelist_only", true);
        int mhp = getMaxHp(m);
        if (whitelistOnly) return mhp > 0;
        return m.isBlock() && m.isSolid();
    }

    // ===== RepairGUI stats =====
    public int getDamagedBlocksCount() {
        // поврежденные + полностью сломанные (AIR)
        return hp.size() + broken.size();
    }

    public int getTotalMissingHp() {
        int sum = 0;

        // поврежденные блоки: недостающее hp
        for (Map.Entry<Long, Integer> e : hp.entrySet()) {
            long k = e.getKey();
            int cur = e.getValue();
            int mx = maxHp.getOrDefault(k, cur);
            sum += Math.max(0, mx - cur);
        }

        // сломанные блоки: считаем как полный maxHp
        for (BrokenInfo bi : broken.values()) {
            sum += Math.max(0, bi.maxHp);
        }

        return sum;
    }
    // ===========================

    public void damage(Block b, int amount) {
        if (b == null) return;

        Material m = b.getType();
        if (m == Material.AIR) return;

        int mhp = getMaxHp(m);
        if (mhp <= 0) return;

        long k = key(b);

        int curMax = maxHp.getOrDefault(k, mhp);
        maxHp.putIfAbsent(k, curMax);

        int cur = hp.getOrDefault(k, curMax);
        cur -= Math.max(1, amount);

        if (cur <= 0) {
            // фиксируем, что блок сломан — его можно восстановить
            broken.put(k, new BrokenInfo(m, curMax));

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

        long k = key(b);

        // НОВОЕ: если блок AIR, но он в списке сломанных — восстанавливаем
        if (b.getType() == Material.AIR) {
            BrokenInfo bi = broken.get(k);
            if (bi == null) return;

            // восстановили блок полностью
            b.setType(bi.original, false);
            broken.remove(k);

            // после восстановления — трещин нет
            clearCracks(b);
            return;
        }

        Material m = b.getType();
        int mhp = getMaxHp(m);
        if (mhp <= 0) return;

        int curMax = maxHp.getOrDefault(k, mhp);
        maxHp.putIfAbsent(k, curMax);

        int cur = hp.getOrDefault(k, curMax);

        cur += Math.max(1, amount);
        if (cur >= curMax) cur = curMax;

        hp.put(k, cur);
        showCracks(b, cur, curMax);

        if (cur == curMax) {
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
        float progress = 1.0f - (cur / (float) max);
        progress = Math.max(0f, Math.min(1f, progress));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;
            if (p.getWorld() != b.getWorld()) continue;
            if (p.getLocation().distanceSquared(b.getLocation()) > 35 * 35) continue;

            p.sendBlockDamage(b.getLocation(), progress);
        }
    }

    private void clearCracks(Block b) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;
            if (p.getWorld() != b.getWorld()) continue;
            if (p.getLocation().distanceSquared(b.getLocation()) > 35 * 35) continue;

            p.sendBlockDamage(b.getLocation(), 0f);
        }
    }
}
