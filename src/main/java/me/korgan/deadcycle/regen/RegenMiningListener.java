package me.korgan.deadcycle.regen;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.player.ProgressManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class RegenMiningListener implements Listener {

    private final DeadCyclePlugin plugin;
    private final ProgressManager progress;

    private final Map<BlockKey, Material> restoreMap = new HashMap<>();

    private static final Set<Material> STONES = EnumSet.of(
            Material.STONE, Material.DEEPSLATE,
            Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.TUFF, Material.CALCITE
    );

    private static final Set<Material> ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,

            // оставим на будущее/для новых версий (не мешает компиляции на 1.21)
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE
    );

    public RegenMiningListener(DeadCyclePlugin plugin, ProgressManager progress) {
        this.plugin = plugin;
        this.progress = progress;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!plugin.getConfig().getBoolean("regen_mining.enabled", true)) return;

        Player p = e.getPlayer();
        Block b = e.getBlock();
        Material type = b.getType();

        if (p.getGameMode() == GameMode.CREATIVE) return;

        BlockKey key = BlockKey.of(b);

        // если это “временный булыжник” — не даём фармить
        if (type == Material.COBBLESTONE && restoreMap.containsKey(key)) {
            e.setCancelled(true);
            p.playSound(p.getLocation(), Sound.BLOCK_STONE_HIT, 0.4f, 0.8f);
            return;
        }

        boolean isStone = STONES.contains(type);
        boolean isOre = ORES.contains(type);
        if (!isStone && !isOre) return;

        // только вне базы (если включено)
        boolean onlyOutside = plugin.getConfig().getBoolean("regen_mining.only_outside_base", true);
        if (onlyOutside && plugin.base().isEnabled() && plugin.base().isOnBase(b.getLocation())) {
            return;
        }

        if (restoreMap.containsKey(key)) {
            e.setCancelled(true);
            return;
        }

        // КЛЮЧ: отменяем ванильное ломание, чтобы блок не стал AIR
        e.setCancelled(true);

        // Выдача лута:
        if (isStone) {
            // камень -> булыжник
            give(p, Material.COBBLESTONE, 1);
        } else {
            // руда -> классический дроп (только 4 нужные + fallback)
            ItemStack drop = oreDrop(type);
            if (drop != null) {
                p.getInventory().addItem(drop);
            } else {
                // если какая-то руда не обработана — пока выдадим булыжник (или можешь поменять на что хочешь)
                give(p, Material.COBBLESTONE, 1);
            }
        }

        // меняем блок на булыжник и планируем восстановление
        restoreMap.put(key, type);
        b.setType(Material.COBBLESTONE, false);
        p.playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, 0.6f, 1.1f);

        // опыт майнеру только за копание
        if (plugin.getConfig().getBoolean("miner_progress.enabled", true)) {
            KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
            if (kit == KitManager.Kit.MINER) {
                int exp = plugin.getConfig().getInt("miner_progress.exp_per_block", 2);
                progress.addMinerExp(p, exp);
            }
        }

        int seconds = plugin.getConfig().getInt("regen_mining.restore_seconds", 90);
        long delay = Math.max(20L, seconds * 20L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (b.getType() != Material.COBBLESTONE) {
                restoreMap.remove(key);
                return;
            }
            Material original = restoreMap.remove(key);
            if (original != null) b.setType(original, false);
        }, delay);
    }

    private ItemStack oreDrop(Material ore) {
        // УГОЛЬ
        if (ore == Material.COAL_ORE || ore == Material.DEEPSLATE_COAL_ORE) {
            return new ItemStack(Material.COAL, 1);
        }
        // ЖЕЛЕЗО
        if (ore == Material.IRON_ORE || ore == Material.DEEPSLATE_IRON_ORE) {
            return new ItemStack(Material.IRON_INGOT, 1);
        }
        // ЗОЛОТО
        if (ore == Material.GOLD_ORE || ore == Material.DEEPSLATE_GOLD_ORE) {
            return new ItemStack(Material.GOLD_INGOT, 1);
        }
        // АЛМАЗ
        if (ore == Material.DIAMOND_ORE || ore == Material.DEEPSLATE_DIAMOND_ORE) {
            return new ItemStack(Material.DIAMOND, 1);
        }

        // медь не надо → ничего
        if (ore == Material.COPPER_ORE || ore == Material.DEEPSLATE_COPPER_ORE) {
            return null;
        }

        // остальное пока не задано
        return null;
    }

    private void give(Player p, Material mat, int amount) {
        p.getInventory().addItem(new ItemStack(mat, amount));
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block b) {
            return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }
}
