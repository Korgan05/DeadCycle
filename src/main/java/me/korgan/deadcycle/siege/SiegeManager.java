package me.korgan.deadcycle.siege;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Zombie;

public class SiegeManager {

    private final DeadCyclePlugin plugin;
    private final BlockHealthManager blocks;

    private boolean running = false;

    public SiegeManager(DeadCyclePlugin plugin, BlockHealthManager blocks) {
        this.plugin = plugin;
        this.blocks = blocks;
    }

    public void onNightStart(int dayCount) {
        if (!plugin.getConfig().getBoolean("siege.enabled", true))
            return;

        int startDay = plugin.getConfig().getInt("siege.start_day", 3);
        if (dayCount < startDay)
            return;

        // важно: раньше если в момент старта ночи никого на базе — осада не стартовала
        // вообще
        if (!plugin.base().hasAnyOnBase())
            return;

        running = true;

        plugin.getServer().broadcastMessage(ChatColor.DARK_RED + "⚔ Осада началась! Зомби ломают стены...");
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        onNightEnd();
    }

    /**
     * ✅ Фикс: если ночь уже идёт и игроки пришли на базу позже — запускаем осаду
     * позднее.
     */
    public void ensureNightRunning(int dayCount) {
        if (running)
            return;

        if (!plugin.getConfig().getBoolean("siege.enabled", true))
            return;
        if (!plugin.getConfig().getBoolean("siege.only_at_night", true))
            return;

        int startDay = plugin.getConfig().getInt("siege.start_day", 3);
        if (dayCount < startDay)
            return;

        if (!plugin.base().hasAnyOnBase())
            return;

        onNightStart(dayCount);
    }

    public void onNightEnd() {
        if (!running)
            return;
        running = false;
        plugin.getServer().broadcastMessage(ChatColor.GREEN + "Осада закончилась.");
    }

    public void tickZombie(Zombie z, int damage) {
        if (!running)
            return;

        boolean whitelistOnly = plugin.getConfig().getBoolean("siege.whitelist_only", true);

        // зомби ломает блок перед собой
        Block target = getFrontSolidBlock(z, whitelistOnly);
        if (target == null)
            return;

        Material m = target.getType();
        if (whitelistOnly && !blocks.isBreakable(m))
            return;

        blocks.damage(target, damage);
    }

    private Block getFrontSolidBlock(Zombie z, boolean whitelistOnly) {
        // Идея: зомби часто упирается в стену и ломает только нижние блоки.
        // Сканируем вертикальную колонну перед ним (ноги/голова/чуть выше).
        var base = z.getLocation().clone();
        var dir = base.getDirection().normalize();

        // шаг вперёд (по XZ)
        base.add(dir.getX() * 1.0, 0, dir.getZ() * 1.0);

        // Проверяем несколько Y уровней: 0 (ноги), +1, +2.
        for (int yOff = 0; yOff <= 2; yOff++) {
            Block b = base.clone().add(0, yOff, 0).getBlock();
            Material t = b.getType();
            if (t.isAir()) continue;
            if (!t.isSolid()) continue;
            if (whitelistOnly && !blocks.isBreakable(t)) continue;
            return b;
        }

        // Если прямо перед ним воздух, попробуем "подпрыгнуть" на пол-блока вперёд
        // (иногда помогает на углах/ступеньках).
        var alt = z.getLocation().clone();
        alt.add(dir.getX() * 1.5, 0, dir.getZ() * 1.5);
        for (int yOff = 0; yOff <= 2; yOff++) {
            Block b = alt.clone().add(0, yOff, 0).getBlock();
            Material t = b.getType();
            if (t.isAir()) continue;
            if (!t.isSolid()) continue;
            if (whitelistOnly && !blocks.isBreakable(t)) continue;
            return b;
        }

        return null;
    }
}
