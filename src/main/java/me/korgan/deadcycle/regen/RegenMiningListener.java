package me.korgan.deadcycle.regen;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RegenMiningListener implements Listener {

    private final DeadCyclePlugin plugin;

    // ключ: worldUUID + packed xyz
    private final Map<String, Material> regenCobble = new HashMap<>();

    public RegenMiningListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    private String key(World w, int x, int y, int z) {
        return w.getUID() + ":" + x + ":" + y + ":" + z;
    }

    private boolean isRegenCobble(Block b) {
        return regenCobble.containsKey(key(b.getWorld(), b.getX(), b.getY(), b.getZ()));
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();

        if (p.getGameMode() == GameMode.CREATIVE) return;

        // запрет ломать блоки НА БАЗЕ
        if (plugin.base().isEnabled() && plugin.base().isOnBase(b.getLocation())) {
            // админам можно
            if (!p.hasPermission("deadcycle.admin")) {
                e.setCancelled(true);
            }
            return;
        }

        // НОВОЕ: запрет ломать булыжник, который появился из регена
        if (b.getType() == Material.COBBLESTONE && isRegenCobble(b)) {
            e.setCancelled(true);
            return;
        }

        Material type = b.getType();
        Material drop = null;

        // ЧТО ДАЕМ В РУКИ
        if (type == Material.STONE) {
            drop = Material.COBBLESTONE;
        } else if (type == Material.COAL_ORE) {
            drop = Material.COAL;
        } else if (type == Material.IRON_ORE) {
            drop = Material.IRON_INGOT;
        } else if (type == Material.DIAMOND_ORE) {
            drop = Material.DIAMOND;
        } else {
            return; // остальные блоки пусть ломаются ванильно
        }

        // отменяем ванильное ломание (чтобы блок не исчезал)
        e.setCancelled(true);

        // даем дроп
        p.getInventory().addItem(new ItemStack(drop, 1));

        // ставим булыжник на место
        b.setType(Material.COBBLESTONE, false);

        // помечаем этот булыжник как "временный", чтобы его нельзя было ломать
        String k = key(b.getWorld(), b.getX(), b.getY(), b.getZ());
        regenCobble.put(k, type); // запомним оригинал (STONE/ORE)

        // опыт майнеру
        if (plugin.kit().getKit(p.getUniqueId()) == KitManager.Kit.MINER) {
            int exp = plugin.getConfig().getInt("miner_progress.exp_per_block", 2);
            plugin.progress().addMinerExp(p, exp);
        }

        // износ инструмента вручную
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool != null && tool.getType() != Material.AIR && tool.getItemMeta() instanceof Damageable dmg) {
            dmg.setDamage(dmg.getDamage() + 1);
            tool.setItemMeta(dmg);
        }

        // восстановление обратно
        int restoreSeconds = plugin.getConfig().getInt("regen_mining.restore_seconds", 90);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // если блок все еще булыжник — восстановим руду/камень
            if (b.getType() == Material.COBBLESTONE) {
                Material original = regenCobble.get(k);
                if (original != null) {
                    b.setType(original, false);
                }
            }
            regenCobble.remove(k);
        }, restoreSeconds * 20L);
    }
}
