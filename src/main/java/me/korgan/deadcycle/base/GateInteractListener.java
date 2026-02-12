package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

public class GateInteractListener implements Listener {

    private final DeadCyclePlugin plugin;
    private final GateManager gates;

    public GateInteractListener(DeadCyclePlugin plugin, GateManager gates) {
        this.plugin = plugin;
        this.gates = gates;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        Player p = e.getPlayer();
        if (!plugin.base().isEnabled())
            return;
        Block clicked = e.getClickedBlock();
        if (clicked == null)
            return;

        // only react to signs whose first line equals [Gate]
        var state = clicked.getState();
        if (!(state instanceof Sign))
            return;
        Sign sign = (Sign) state;
        String l0 = sign.getLine(0);
        if (l0 == null || !l0.trim().equalsIgnoreCase("[Gate]"))
            return;

        var loc = clicked.getLocation();
        // find nearest gate within radius (configurable)
        int radius = plugin.getConfig().getInt("gates.sign_search_radius", 6);
        var g = gates.findNearestGate(loc, radius);
        if (g == null) {
            p.sendMessage("§eРядом нет отмеченных ворот.");
            return;
        }

        // require player to be on base
        if (!plugin.base().isOnBase(p.getLocation())) {
            p.sendMessage("§cВы не на базе — нельзя управлять воротами.");
            return;
        }

        if (!gates.canToggle(p.getUniqueId())) {
            p.sendMessage("§eПодождите немного перед повторным переключением ворот.");
            return;
        }

        gates.toggleGate(g);
        p.sendMessage(g.isOpen() ? "§aВорота открыты." : "§cВорота закрыты.");
    }
}
