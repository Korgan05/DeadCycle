package me.korgan.deadcycle.regen;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RegenMiningListener implements Listener {

    private final DeadCyclePlugin plugin;

    // чтобы не ставить 10 таймеров на один и тот же блок
    private final Map<String, Material> pendingRestore = new HashMap<>();

    public RegenMiningListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (e.isCancelled()) return;

        Player p = e.getPlayer();
        Block b = e.getBlock();

        if (p.getGameMode() == GameMode.CREATIVE) return;

        // опыт за копание — только майнеру
        if (plugin.kit().getKit(p.getUniqueId()) == KitManager.Kit.MINER) {
            int exp = plugin.getConfig().getInt("miner_progress.exp_per_block", 2);
            plugin.progress().addMinerExp(p, exp);
        }

        // regen mining
        if (!plugin.getConfig().getBoolean("regen_mining.enabled", true)) return;

        // если включено only_outside_base — внутри базы НЕ трогаем (там и так ломать запрещено)
        boolean onlyOutside = plugin.getConfig().getBoolean("regen_mining.only_outside_base", true);
        if (onlyOutside && plugin.base().isEnabled() && plugin.base().isOnBase(b.getLocation())) {
            return;
        }

        Material original = b.getType();

        // какие блоки регеним
        Material reward = rewardFor(original);
        if (reward == null) return; // не камень/не нужная руда

        // отменяем обычный дроп ваниллы
        e.setDropItems(false);
        e.setExpToDrop(0);

        // выдаем 1 предмет игроку (как ты хотел)
        p.getInventory().addItem(new ItemStack(reward, 1));

        // меняем блок на булыжник
        b.setType(Material.COBBLESTONE, false);

        // ставим таймер на восстановление
        int seconds = plugin.getConfig().getInt("regen_mining.restore_seconds", 90);
        long ticks = Math.max(20L, seconds * 20L);

        String key = key(b.getLocation());
        // если уже есть таймер на этот блок — не создаём новый
        if (pendingRestore.containsKey(key)) return;

        pendingRestore.put(key, original);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                Material old = pendingRestore.remove(key);
                if (old == null) return;

                Block bb = b.getWorld().getBlockAt(b.getLocation());
                // восстанавливаем только если блок всё ещё булыжник (не трогаем, если кто-то изменил)
                if (bb.getType() == Material.COBBLESTONE) {
                    bb.setType(old, false);
                }
            } catch (Throwable ignored) {
                pendingRestore.remove(key);
            }
        }, ticks);
    }

    private Material rewardFor(Material original) {
        // камень -> булыжник
        if (original == Material.STONE) return Material.COBBLESTONE;

        // угольная руда -> уголь
        if (original == Material.COAL_ORE || original == Material.DEEPSLATE_COAL_ORE) return Material.COAL;

        // железная руда -> железо (ИНГОТ, как ты просил)
        if (original == Material.IRON_ORE || original == Material.DEEPSLATE_IRON_ORE) return Material.IRON_INGOT;

        // золотая руда -> золото (ИНГОТ)
        if (original == Material.GOLD_ORE || original == Material.DEEPSLATE_GOLD_ORE) return Material.GOLD_INGOT;

        // алмазная руда -> алмаз
        if (original == Material.DIAMOND_ORE || original == Material.DEEPSLATE_DIAMOND_ORE) return Material.DIAMOND;

        return null;
    }

    private String key(Location loc) {
        UUID w = loc.getWorld().getUID();
        return w + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
