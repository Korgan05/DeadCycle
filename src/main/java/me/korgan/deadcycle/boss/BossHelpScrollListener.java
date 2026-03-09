package me.korgan.deadcycle.boss;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Обработка использования свитка призыва помощи в бою с боссом
 */
public class BossHelpScrollListener implements Listener {

    private final DeadCyclePlugin plugin;
    private final NamespacedKey bossHelpScrollKey;

    public BossHelpScrollListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.bossHelpScrollKey = new NamespacedKey(plugin, "boss_help_scroll");
    }

    public ItemStack createBossHelpScroll(int amount) {
        ItemStack it = new ItemStack(Material.PAPER, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(bossHelpScrollKey, PersistentDataType.BYTE, (byte) 1);
            meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize("§6Свиток призыва на помощь"));
            meta.lore(java.util.List.of(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                            .deserialize("§7ПКМ - призвать игрока на арену"),
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                            .deserialize("§7для помощи в бою с боссом")));
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isBossHelpScroll(ItemStack it) {
        if (it == null || it.getType() != Material.PAPER)
            return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return false;
        Byte v = meta.getPersistentDataContainer().get(bossHelpScrollKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getItem() == null)
            return;

        Player p = e.getPlayer();
        ItemStack it = e.getItem();

        if (!isBossHelpScroll(it))
            return;

        e.setCancelled(true);

        // Проверка: игрок должен быть в бою с боссом
        if (!plugin.bossDuel().isDuelActive() || !plugin.bossDuel().isDuelPlayer(p.getUniqueId())) {
            p.sendMessage("§cВы не находитесь в бою с боссом!");
            return;
        }

        // Проверка: союзник уже не призван
        if (plugin.bossDuel().hasAlly()) {
            p.sendMessage("§cСоюзник уже призван!");
            return;
        }

        // Найти ближайшего онлайн игрока (не сам игрок)
        Player nearestPlayer = null;
        double closestDistance = Double.MAX_VALUE;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(p.getUniqueId()))
                continue; // Пропустить себя
            if (online.isDead())
                continue;
            if (plugin.bossDuel().isDuelPlayer(online.getUniqueId()))
                continue; // Уже в бою

            double distance = online.getLocation().distance(p.getLocation());
            if (distance < closestDistance) {
                closestDistance = distance;
                nearestPlayer = online;
            }
        }

        if (nearestPlayer == null) {
            p.sendMessage("§cНет доступных игроков для призыва!");
            return;
        }

        // Призвать игрока через существующую механику
        boolean success = plugin.bossDuel().trySummonAlly(p, nearestPlayer);

        if (success) {
            // Удалить свиток только если призыв успешен
            consumeOne(p, it);
        }
    }

    private void consumeOne(Player p, ItemStack it) {
        if (it.getAmount() > 1) {
            it.setAmount(it.getAmount() - 1);
        } else {
            p.getInventory().remove(it);
        }
    }

    public void removeAllScrollsFromPlayers() {
        // Удалить свитки призыва у всех игроков при наступлении дня
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline())
                continue;
            for (ItemStack it : p.getInventory().getContents()) {
                if (isBossHelpScroll(it)) {
                    p.getInventory().remove(it);
                }
            }
        }
    }
}
