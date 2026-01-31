package me.korgan.deadcycle.base;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class ResourceDepositListener implements Listener {

    private final DeadCyclePlugin plugin;
    private final Map<UUID, Boolean> wasOnBase = new EnumMap<>(UUID.class); // простая защита от спама

    public ResourceDepositListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        if (!plugin.base().isEnabled()) return;

        Player p = e.getPlayer();

        boolean before = plugin.base().isOnBase(e.getFrom());
        boolean now = plugin.base().isOnBase(e.getTo());

        // Срабатывает только в момент входа в радиус
        if (before || !now) return;

        // Только майнер получает деньги (можно расширить потом)
        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.MINER) {
            // но ресурсы можно и не принимать от других — тут оставлю “не принимаем”
            return;
        }

        depositMiner(p);
    }

    private void depositMiner(Player p) {
        BaseResourceManager br = plugin.baseResources();

        int totalPoints = 0;

        // сколько очков по категориям (чтобы красиво написать)
        int stonePts = 0, coalPts = 0, ironPts = 0, diamondPts = 0;

        // перебираем инвентарь и забираем нужные предметы
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;

            Material m = it.getType();
            int per = br.pointsPer(m);
            if (per <= 0) continue;

            int amount = it.getAmount();
            int points = per * amount;
            totalPoints += points;

            BaseResourceManager.ResourceType t = br.typeOf(m);
            if (t != null) {
                br.addPoints(t, points);
                if (t == BaseResourceManager.ResourceType.STONE) stonePts += points;
                if (t == BaseResourceManager.ResourceType.COAL) coalPts += points;
                if (t == BaseResourceManager.ResourceType.IRON) ironPts += points;
                if (t == BaseResourceManager.ResourceType.DIAMOND) diamondPts += points;
            }

            // удаляем предметы из инвентаря (сданы)
            p.getInventory().setItem(i, null);
        }

        if (totalPoints <= 0) return;

        // деньги
        double money = totalPoints * br.moneyPerPoint();
        plugin.economy().addMoney(p.getUniqueId(), money);

        // сообщение
        p.sendMessage(ChatColor.GREEN + "Ты сдал ресурсы на базу: " + ChatColor.WHITE + "+" + totalPoints + " очков базы");
        p.sendMessage(ChatColor.YELLOW + "Награда: " + ChatColor.WHITE + "+" + formatMoney(money) + "$");

        // можно детальнее:
        String detail = "";
        if (stonePts > 0) detail += ChatColor.GRAY + "Камень +" + stonePts + "  ";
        if (coalPts > 0) detail += ChatColor.GRAY + "Уголь +" + coalPts + "  ";
        if (ironPts > 0) detail += ChatColor.GRAY + "Железо +" + ironPts + "  ";
        if (diamondPts > 0) detail += ChatColor.GRAY + "Алмазы +" + diamondPts + "  ";
        if (!detail.isEmpty()) p.sendMessage(detail);
    }

    private String formatMoney(double d) {
        if (d == (long) d) return String.valueOf((long) d);
        return String.format(java.util.Locale.US, "%.1f", d);
    }
}
