package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

public class ResourceDepositListener implements Listener {

    private final DeadCyclePlugin plugin;

    public ResourceDepositListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null)
            return;
        if (!plugin.base().isEnabled())
            return;

        Player p = e.getPlayer();

        boolean before = plugin.base().isOnBase(e.getFrom());
        boolean now = plugin.base().isOnBase(e.getTo());

        // Срабатывает только в момент входа в радиус базы
        if (before || !now)
            return;

        depositMiner(p);
    }

    private void depositMiner(Player p) {
        BaseResourceManager br = plugin.baseResources();

        int totalPoints = 0;

        // очки по категориям
        int stonePts = 0, coalPts = 0, ironPts = 0, diamondPts = 0;

        // перебираем инвентарь и забираем нужные предметы
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null || it.getType() == Material.AIR)
                continue;

            Material m = it.getType();
            int per = br.pointsPer(m);
            if (per <= 0)
                continue;

            int amount = it.getAmount();
            int points = per * amount;
            totalPoints += points;

            BaseResourceManager.ResourceType t = br.typeOf(m);
            if (t != null) {
                br.addPoints(t, points);

                if (t == BaseResourceManager.ResourceType.STONE)
                    stonePts += points;
                if (t == BaseResourceManager.ResourceType.COAL)
                    coalPts += points;
                if (t == BaseResourceManager.ResourceType.IRON)
                    ironPts += points;
                if (t == BaseResourceManager.ResourceType.DIAMOND)
                    diamondPts += points;
            }

            // удаляем предметы из инвентаря (сданы)
            p.getInventory().setItem(i, null);
        }

        if (totalPoints <= 0)
            return;

        // деньги (long), округляем
        double moneyDouble = totalPoints * br.moneyPerPoint();
        long money = Math.round(moneyDouble);

        plugin.econ().give(p, money);

        // сообщения
        p.sendMessage(
                ChatColor.GREEN + "Ты сдал ресурсы на базу: " + ChatColor.WHITE + "+" + totalPoints + " очков базы");
        p.sendMessage(ChatColor.YELLOW + "Награда: " + ChatColor.WHITE + "+" + money + "$");

        // детализация
        StringBuilder detail = new StringBuilder();
        if (stonePts > 0)
            detail.append(ChatColor.GRAY).append("Камень +").append(stonePts).append("  ");
        if (coalPts > 0)
            detail.append(ChatColor.GRAY).append("Уголь +").append(coalPts).append("  ");
        if (ironPts > 0)
            detail.append(ChatColor.GRAY).append("Железо +").append(ironPts).append("  ");
        if (diamondPts > 0)
            detail.append(ChatColor.GRAY).append("Алмазы +").append(diamondPts).append("  ");
        if (!detail.isEmpty())
            p.sendMessage(detail.toString());
    }
}
