package me.korgan.deadcycle.shop;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class BaseScrollListener implements Listener {

    private final DeadCyclePlugin plugin;

    public BaseScrollListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getItem() == null) return;

        Player p = e.getPlayer();
        ItemStack it = e.getItem();

        if (!plugin.shopGui().isBaseScroll(it)) return;

        e.setCancelled(true);

        if (!plugin.base().isEnabled()) {
            p.sendMessage("§cБаза не настроена.");
            return;
        }

        Location center = plugin.base().getCenter();
        if (center == null || center.getWorld() == null) {
            p.sendMessage("§cБаза не настроена.");
            return;
        }

        Location tp = center.clone();
        int y = tp.getWorld().getHighestBlockYAt(tp) + 1;
        tp.setY(y);

        p.teleport(tp);
        p.sendMessage("§dТелепортировано на базу.");

        plugin.shopGui().consumeOne(p, it);
    }
}
