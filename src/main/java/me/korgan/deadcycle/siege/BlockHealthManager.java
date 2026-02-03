package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BlockHealthManager {

    private final DeadCyclePlugin plugin;

    // HP по позиции блока (только для ломаемых блоков базы)
    private final Map<BlockPos, Integer> hp = new ConcurrentHashMap<>();

    // Сломанные блоки: храним какой материал был там ДО слома
    private final Map<BlockPos, Material> broken = new ConcurrentHashMap<>();

    // Блоки, которые сейчас "чинятся" (для белых искр)
    private final Map<BlockPos, Long> repairingUntil = new ConcurrentHashMap<>();

    private BukkitTask particleTask;

    // Для совместимости с RepairGUI (там есть эти вызовы)
    private final Set<UUID> activeRepairers = ConcurrentHashMap.newKeySet();
    private volatile boolean globalRepairMode = false;

    public BlockHealthManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------
    // Public API (для других классов)
    // -------------------------

    public void startVisuals() {
        stopVisuals();

        long periodTicks = 8L; // часто, чтобы искры выглядели живыми
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.base().isEnabled())
                return;

            World w = Bukkit.getWorld(plugin.base().getWorldName());
            if (w == null)
                return;

            Location center = plugin.base().getCenter();
            int radius = plugin.base().getRadius();

            boolean someoneNear = false;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getWorld().equals(w))
                    continue;
                if (p.getLocation().distanceSquared(center) <= (radius + 10.0) * (radius + 10.0)) {
                    someoneNear = true;
                    break;
                }
            }
            if (!someoneNear)
                return;

            // 1) сломанные = красные искры
            for (Map.Entry<BlockPos, Material> e : broken.entrySet()) {
                BlockPos pos = e.getKey();
                Location at = pos.toLocation(w).add(0.5, 0.85, 0.5);

                w.spawnParticle(
                        Particle.DUST, at,
                        8,
                        0.15, 0.25, 0.15,
                        0,
                        new Particle.DustOptions(Color.RED, 1.55f));
            }

            // 2) поврежденные = оранжевые искры
            for (Map.Entry<BlockPos, Integer> e : hp.entrySet()) {
                BlockPos pos = e.getKey();
                if (broken.containsKey(pos))
                    continue; // если уже сломан — красный приоритет

                int cur = e.getValue();
                int max = getMaxHp(pos.toLocation(w).getBlock().getType());
                if (max <= 0)
                    continue;
                if (cur >= max)
                    continue; // полностью целый

                Location at = pos.toLocation(w).add(0.5, 0.85, 0.5);
                w.spawnParticle(
                        Particle.DUST, at,
                        5,
                        0.15, 0.22, 0.15,
                        0,
                        new Particle.DustOptions(Color.ORANGE, 1.25f));
            }

            // 3) чинящиеся = белые искры
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<BlockPos, Long>> it = repairingUntil.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Long> e = it.next();
                if (e.getValue() < now) {
                    it.remove();
                    continue;
                }

                BlockPos pos = e.getKey();
                Location at = pos.toLocation(w).add(0.5, 0.85, 0.5);

                w.spawnParticle(
                        Particle.DUST, at,
                        4,
                        0.12, 0.18, 0.12,
                        0,
                        new Particle.DustOptions(Color.WHITE, 1.10f));
                w.spawnParticle(Particle.CRIT, at, 1, 0.05, 0.08, 0.05, 0.02);
            }

        }, 0L, periodTicks);
    }

    public void stopVisuals() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
    }

    /** Можно ли этому блоку наносить урон в осаде */
    public boolean isBreakable(Material m) {
        if (m == null)
            return false;
        // whitelist_only включён — ломаем только то, что есть в blocks_hp
        return getMaxHp(m) > 0;
    }

    /** Макс HP блока из конфига */
    public int getMaxHp(Material m) {
        if (m == null)
            return 0;

        String path = "blocks_hp." + m.name();
        int direct = plugin.getConfig().getInt(path, 0);
        if (direct > 0)
            return direct;

        // Если ключ явно задан (в т.ч. 0) — уважаем конфиг.
        if (plugin.getConfig().isSet(path))
            return direct;

        // Fallback для частого кейса: стены апгрейдятся в STONE, а в blocks_hp
        // есть только COBBLESTONE, из-за чего зомби перестают ломать.
        if (m == Material.STONE) {
            String cobblePath = "blocks_hp.COBBLESTONE";
            int cobble = plugin.getConfig().getInt(cobblePath, 0);
            if (cobble > 0)
                return cobble;
            if (plugin.getConfig().isSet(cobblePath))
                return cobble;
        }

        // Безопасные дефолты, если blocks_hp вообще не настроен.
        return switch (m) {
            case OAK_PLANKS, SPRUCE_PLANKS -> 30;
            case COBBLESTONE, STONE -> 60;
            default -> 0;
        };
    }

    /** Текущий HP блока (если не было — считается полный) */
    public int getHp(Block b) {
        if (b == null)
            return 0;
        int max = getMaxHp(b.getType());
        if (max <= 0)
            return 0;

        BlockPos pos = BlockPos.of(b.getLocation());
        return hp.getOrDefault(pos, max);
    }

    /**
     * Нанести урон блоку. Если сломался — превращаем в "искру" (AIR), запоминаем
     * исходный материал.
     */
    public void damage(Block b, int dmg) {
        if (b == null)
            return;
        if (!plugin.base().isEnabled())
            return;
        if (!plugin.base().isOnBase(b.getLocation()))
            return;

        Material type = b.getType();
        int max = getMaxHp(type);
        if (max <= 0)
            return;

        BlockPos pos = BlockPos.of(b.getLocation());

        // если уже сломан — не бьём
        if (broken.containsKey(pos))
            return;

        int cur = hp.getOrDefault(pos, max);
        cur -= Math.max(1, dmg);
        if (cur <= 0) {
            // сломался
            broken.put(pos, type);
            hp.remove(pos);

            // превращаем в AIR (место "красной искры")
            b.setType(Material.AIR, false);

            // отмечаем как "чинится" чуть-чуть (чтобы сразу видно было эффект)
            markRepairing(pos, 400);
        } else {
            hp.put(pos, cur);
            markRepairing(pos, 250);
        }
    }

    /** Починить конкретный блок на amount HP, или восстановить сломанный блок */
    public int repair(Block b, int amount) {
        if (b == null)
            return 0;
        if (!plugin.base().isEnabled())
            return 0;
        if (!plugin.base().isOnBase(b.getLocation()))
            return 0;

        BlockPos pos = BlockPos.of(b.getLocation());

        // если сломан — восстанавливаем материал полностью
        if (broken.containsKey(pos)) {
            Material restore = broken.remove(pos);
            if (restore == null)
                return 0;

            // Если это участок стен базы — ставим материал текущего уровня стен.
            // Иначе (пол/декор) восстанавливаем исходный материал.
            Material wallMat = getWallMaterialForCurrentLevel();
            if (wallMat != null && isWallLocation(b.getLocation()) && isWallMaterial(restore)) {
                restore = wallMat;
            }

            b.setType(restore, false);

            int max = getMaxHp(restore);
            if (max > 0)
                hp.put(pos, max);

            markRepairing(pos, 900);
            return Math.max(1, max);
        }

        // если поврежден — добавляем HP
        int max = getMaxHp(b.getType());
        if (max <= 0)
            return 0;

        int cur = hp.getOrDefault(pos, max);
        if (cur >= max)
            return 0;

        int add = Math.max(1, amount);
        int newHp = Math.min(max, cur + add);
        hp.put(pos, newHp);

        markRepairing(pos, 650);
        return (newHp - cur);
    }

    private Material getWallMaterialForCurrentLevel() {
        int level = plugin.getConfig().getInt("base.wall_level", 1);
        return getWallMaterialForLevel(level);
    }

    private Material getWallMaterialForLevel(int level) {
        String key = "wall_upgrade.levels.l" + level;
        String raw = plugin.getConfig().getString(key);
        Material mat = (raw == null) ? null : Material.matchMaterial(raw);
        if (mat != null)
            return mat;

        // дефолты проекта
        return switch (level) {
            case 1 -> Material.OAK_PLANKS;
            case 2 -> Material.SPRUCE_PLANKS;
            case 3 -> Material.COBBLESTONE;
            case 4 -> Material.STONE;
            case 5 -> Material.STONE_BRICKS;
            default -> Material.OAK_PLANKS;
        };
    }

    private boolean isWallMaterial(Material m) {
        if (m == null)
            return false;
        int maxLevel = plugin.getConfig().getInt("wall_upgrade.max_level", 3);
        for (int lvl = 1; lvl <= maxLevel; lvl++) {
            if (getWallMaterialForLevel(lvl) == m)
                return true;
        }

        // Частый кейс: игроки используют STONE как каменный уровень
        return m == Material.STONE;
    }

    private boolean isWallLocation(Location loc) {
        if (loc == null)
            return false;
        if (plugin.base() == null || plugin.base().getCenter() == null)
            return false;

        Location c = plugin.base().getCenter();
        if (c.getWorld() == null || loc.getWorld() == null)
            return false;
        if (!c.getWorld().equals(loc.getWorld()))
            return false;

        int radius = plugin.base().getRadius();
        if (radius <= 0)
            return false;

        // Кольцо стен: примерно по границе базы
        double dx = (loc.getBlockX() + 0.5) - c.getX();
        double dz = (loc.getBlockZ() + 0.5) - c.getZ();
        double d2 = dx * dx + dz * dz;

        int band = 2;
        int rMin = Math.max(1, radius - band);
        int rMax = radius + band;
        if (d2 < (double) rMin * rMin || d2 > (double) rMax * rMax)
            return false;

        // Y ограничение как в прокачке стен (чтобы не трогать шахту/низ)
        int yMin = c.getBlockY() + plugin.getConfig().getInt("wall_upgrade.scan_y_min_offset", -5);
        int yMax = c.getBlockY() + plugin.getConfig().getInt("wall_upgrade.scan_y_max_offset", 15);
        int y = loc.getBlockY();
        return y >= yMin && y <= yMax;
    }

    /**
     * Приоритет: сломанные -> поврежденные. Возвращает сколько HP реально
     * восстановлено.
     */
    public int repairAnyOnBase(int amount, Location center, int radius) {
        if (center == null)
            return 0;
        World w = center.getWorld();
        if (w == null)
            return 0;

        // 1) сначала сломанные
        BlockPos brokenPos = findNearestBroken(center, radius);
        if (brokenPos != null) {
            Block b = w.getBlockAt(brokenPos.x, brokenPos.y, brokenPos.z);
            return repair(b, Math.max(1, amount));
        }

        // 2) потом поврежденные
        BlockPos damagedPos = findNearestDamaged(center, radius);
        if (damagedPos != null) {
            Block b = w.getBlockAt(damagedPos.x, damagedPos.y, damagedPos.z);
            return repair(b, Math.max(1, amount));
        }

        return 0;
    }

    // ✅ перегрузка под RepairGUI (оно вызывает так)
    public int repairAnyOnBase(Location center, int radius, int amount) {
        return repairAnyOnBase(amount, center, radius);
    }

    public int getBrokenCountOnBase() {
        return broken.size();
    }

    public int getBrokenCountOnBase(Location center, int radius) {
        if (center == null || center.getWorld() == null)
            return 0;
        int r2 = radius * radius;
        int c = 0;
        for (BlockPos pos : broken.keySet()) {
            if (!pos.isSameWorld(center.getWorld()))
                continue;
            if (pos.distanceSquared(center) <= r2)
                c++;
        }
        return c;
    }

    public int getDamagedCountOnBase() {
        // поврежденные — это те, у кого hp < max и не сломаны
        int c = 0;
        for (Map.Entry<BlockPos, Integer> e : hp.entrySet()) {
            if (broken.containsKey(e.getKey()))
                continue;
            BlockPos pos = e.getKey();
            World w = Bukkit.getWorld(plugin.base().getWorldName());
            if (w == null)
                continue;
            Material m = pos.toLocation(w).getBlock().getType();
            int max = getMaxHp(m);
            if (max <= 0)
                continue;
            if (e.getValue() < max)
                c++;
        }
        return c;
    }

    public int getDamagedCountOnBase(Location center, int radius) {
        if (center == null || center.getWorld() == null)
            return 0;
        int r2 = radius * radius;
        World w = center.getWorld();

        int c = 0;
        for (Map.Entry<BlockPos, Integer> e : hp.entrySet()) {
            BlockPos pos = e.getKey();
            if (!pos.isSameWorld(w))
                continue;
            if (pos.distanceSquared(center) > r2)
                continue;
            if (broken.containsKey(pos))
                continue;

            Material m = w.getBlockAt(pos.x, pos.y, pos.z).getType();
            int max = getMaxHp(m);
            if (max <= 0)
                continue;
            if (e.getValue() < max)
                c++;
        }
        return c;
    }

    /** Сколько HP не хватает суммарно (сломанные считаются как полный maxHP) */
    public int getTotalMissingHpOnBase() {
        if (!plugin.base().isEnabled())
            return 0;
        Location center = plugin.base().getCenter();
        return getTotalMissingHpOnBase(center, plugin.base().getRadius());
    }

    public int getTotalMissingHpOnBase(Location center, int radius) {
        if (center == null || center.getWorld() == null)
            return 0;

        World w = center.getWorld();
        int r2 = radius * radius;

        int missing = 0;

        // сломанные: считаем как maxHP
        for (Map.Entry<BlockPos, Material> e : broken.entrySet()) {
            BlockPos pos = e.getKey();
            if (!pos.isSameWorld(w))
                continue;
            if (pos.distanceSquared(center) > r2)
                continue;

            int max = getMaxHp(e.getValue());
            if (max > 0)
                missing += max;
        }

        // поврежденные: max - cur
        for (Map.Entry<BlockPos, Integer> e : hp.entrySet()) {
            BlockPos pos = e.getKey();
            if (!pos.isSameWorld(w))
                continue;
            if (pos.distanceSquared(center) > r2)
                continue;
            if (broken.containsKey(pos))
                continue;

            Material m = w.getBlockAt(pos.x, pos.y, pos.z).getType();
            int max = getMaxHp(m);
            if (max <= 0)
                continue;

            int cur = e.getValue();
            if (cur < max)
                missing += (max - cur);
        }

        return missing;
    }

    /**
     * Используется апгрейдом стен: очистить красно/оранжевое состояние на этой
     * клетке
     */
    public void clearStateAt(Location loc) {
        if (loc == null)
            return;
        BlockPos pos = BlockPos.of(loc);

        broken.remove(pos);
        hp.remove(pos);
        repairingUntil.remove(pos);
    }

    // -------- методы для совместимости с RepairGUI (чтоб не падало “cannot
    // resolve”) --------

    public void addActiveRepairer(UUID id) {
        if (id != null)
            activeRepairers.add(id);
    }

    public void removeActiveRepairer(UUID id) {
        if (id != null)
            activeRepairers.remove(id);
    }

    public boolean hasActiveRepairers() {
        return !activeRepairers.isEmpty();
    }

    public void setGlobalRepairMode(boolean enabled) {
        this.globalRepairMode = enabled;
    }

    public boolean isGlobalRepairMode() {
        return globalRepairMode;
    }

    // -------------------------
    // Internal helpers
    // -------------------------

    private void markRepairing(BlockPos pos, long ms) {
        repairingUntil.put(pos, System.currentTimeMillis() + ms);
    }

    private BlockPos findNearestBroken(Location center, int radius) {
        if (broken.isEmpty())
            return null;

        World w = center.getWorld();
        int r2 = radius * radius;

        BlockPos best = null;
        double bestD = Double.MAX_VALUE;

        for (BlockPos pos : broken.keySet()) {
            if (!pos.isSameWorld(w))
                continue;
            double d = pos.distanceSquared(center);
            if (d > r2)
                continue;
            if (d < bestD) {
                bestD = d;
                best = pos;
            }
        }
        return best;
    }

    private BlockPos findNearestDamaged(Location center, int radius) {
        if (hp.isEmpty())
            return null;

        World w = center.getWorld();
        int r2 = radius * radius;

        BlockPos best = null;
        double bestD = Double.MAX_VALUE;

        for (Map.Entry<BlockPos, Integer> e : hp.entrySet()) {
            BlockPos pos = e.getKey();
            if (!pos.isSameWorld(w))
                continue;
            if (broken.containsKey(pos))
                continue;

            double d = pos.distanceSquared(center);
            if (d > r2)
                continue;

            Material m = w.getBlockAt(pos.x, pos.y, pos.z).getType();
            int max = getMaxHp(m);
            if (max <= 0)
                continue;

            int cur = e.getValue();
            if (cur >= max)
                continue;

            if (d < bestD) {
                bestD = d;
                best = pos;
            }
        }
        return best;
    }

    // -------------------------
    // BlockPos
    // -------------------------
    private static final class BlockPos {
        final int x, y, z;
        final String world; // имя мира

        private BlockPos(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static BlockPos of(Location l) {
            return new BlockPos(l.getWorld() != null ? l.getWorld().getName() : "world",
                    l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }

        Location toLocation(World w) {
            return new Location(w, x, y, z);
        }

        boolean isSameWorld(World w) {
            return w != null && Objects.equals(world, w.getName());
        }

        double distanceSquared(Location center) {
            double dx = (x + 0.5) - center.getX();
            double dy = (y + 0.5) - center.getY();
            double dz = (z + 0.5) - center.getZ();
            return dx * dx + dy * dy + dz * dz;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof BlockPos other))
                return false;
            return x == other.x && y == other.y && z == other.z && Objects.equals(world, other.world);
        }

        @Override
        public int hashCode() {
            return Objects.hash(world, x, y, z);
        }
    }
}
