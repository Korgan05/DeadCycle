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
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RegenMiningListener implements Listener {

    private final DeadCyclePlugin plugin;

    private record Pos(UUID worldId, int x, int y, int z) {
        int chunkX() {
            return x >> 4;
        }

        int chunkZ() {
            return z >> 4;
        }
    }

    private record RegenEntry(Material original, long restoreAtMillis) {
    }

    private final Map<Pos, RegenEntry> regenCobble = new HashMap<>();

    public RegenMiningListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isRegenCobble(Block b) {
        return regenCobble.containsKey(new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()));
    }

    private boolean tryRestore(Pos pos) {
        RegenEntry entry = regenCobble.get(pos);
        if (entry == null)
            return false;

        World w = Bukkit.getWorld(pos.worldId());
        if (w == null) {
            regenCobble.remove(pos);
            return false;
        }

        // Если чанк не загружен — дождёмся ChunkLoadEvent
        if (!w.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
            return false;
        }

        Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
        Material type = b.getType();

        // если игрок/мир заменил блок — больше не трогаем
        if (type != Material.COBBLESTONE) {
            regenCobble.remove(pos);
            return false;
        }

        if (System.currentTimeMillis() < entry.restoreAtMillis()) {
            return false;
        }

        b.setType(entry.original(), false);
        regenCobble.remove(pos);
        return true;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();

        if (p.getGameMode() == GameMode.CREATIVE)
            return;

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
        Collection<ItemStack> customDrops = null;

        // ЧТО ДАЕМ В РУКИ
        if (type == Material.STONE) {
            drop = Material.COBBLESTONE;
        } else if (type == Material.ANDESITE) {
            drop = Material.ANDESITE;
        } else if (type == Material.GRANITE) {
            drop = Material.GRANITE;
        } else if (type == Material.DIORITE) {
            drop = Material.DIORITE;
        } else if (type == Material.GRAVEL) {
            // Сохраняем ванильные дропы (гравий/кремень, зависит от удачи/инструмента)
            ItemStack tool = p.getInventory().getItemInMainHand();
            try {
                customDrops = b.getDrops(tool, p);
            } catch (Throwable ignored) {
                customDrops = null;
            }
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
        if (customDrops != null) {
            boolean gaveAny = false;
            for (ItemStack it : customDrops) {
                if (it == null || it.getType() == Material.AIR || it.getAmount() <= 0)
                    continue;
                p.getInventory().addItem(it);
                gaveAny = true;
            }
            if (!gaveAny) {
                // fallback
                p.getInventory().addItem(new ItemStack(Material.GRAVEL, 1));
            }
        } else {
            p.getInventory().addItem(new ItemStack(drop, 1));
        }

        // ставим булыжник на место
        b.setType(Material.COBBLESTONE, false);

        // помечаем этот булыжник как "временный", чтобы его нельзя было ломать
        int restoreSeconds = plugin.getConfig().getInt("regen_mining.restore_seconds", 90);
        long restoreAt = System.currentTimeMillis() + (restoreSeconds * 1000L);

        Pos pos = new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        regenCobble.put(pos, new RegenEntry(type, restoreAt));

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

        // восстановление обратно (если чанк выгрузится — восстановим при следующей
        // загрузке)
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryRestore(pos), restoreSeconds * 20L);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        UUID worldId = e.getWorld().getUID();
        int cx = e.getChunk().getX();
        int cz = e.getChunk().getZ();

        // простая фильтрация: восстанавливаем только позиции из этого чанка
        for (Pos pos : new java.util.ArrayList<>(regenCobble.keySet())) {
            if (!pos.worldId().equals(worldId))
                continue;
            if (pos.chunkX() != cx || pos.chunkZ() != cz)
                continue;
            tryRestore(pos);
        }
    }
}
