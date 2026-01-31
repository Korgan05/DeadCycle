package me.korgan.deadcycle.player;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public class ActionBarHUD {

    private final DeadCyclePlugin plugin;
    private final ProgressManager progress;
    private BukkitTask task;

    public ActionBarHUD(DeadCyclePlugin plugin, ProgressManager progress) {
        this.plugin = plugin;
        this.progress = progress;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                long money = plugin.econ().getMoney(p.getUniqueId());

                KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());
                String kitName = (kit == null) ? "-" : kit.name();

                int minerLvl = progress.getMinerLevel(p.getUniqueId());
                int minerExp = progress.getMinerExp(p.getUniqueId());
                int minerNeed = progress.needMinerExp(p.getUniqueId());

                // эффекты китов (например Haste) поддерживаем живыми
                progress.applyKitEffects(p);

                String msg = "§6💰 " + money + " §7| §bKit: §f" + kitName
                        + " §7| §a⛏ Miner: §f" + minerLvl + " §7(" + minerExp + "/" + minerNeed + ")";

                p.sendActionBar(Component.text(msg.replace('§', '\u00A7')));
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
