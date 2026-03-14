package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;

/**
 * Слушатель для защиты предметов скиллов.
 * Предотвращает выброс, крафт и другие манипуляции с защищёнными предметами.
 */
public class SkillItemProtectionListener implements Listener {

    private final DeadCyclePlugin plugin;

    public SkillItemProtectionListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Блокировать выброс защищённых предметов.
     */
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();

        if (plugin.kit().isProtectedSkillItem(item)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cЭтот предмет нельзя выбросить!");
        }
    }

    /**
     * Блокировать потерю прочности у защищённых предметов скиллов.
     */
    @EventHandler
    public void onPlayerItemDamage(PlayerItemDamageEvent e) {
        if (plugin.kit().isProtectedSkillItem(e.getItem())) {
            e.setCancelled(true);
        }
    }

    /**
     * Блокировать бросок трезубца, если это трезубец-скилл Гарпунера.
     */
    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Trident trident))
            return;
        if (!(e.getEntity().getShooter() instanceof Player player))
            return;

        ItemStack launched = trident.getItemStack();
        if (launched != null && plugin.kit().isProtectedSkillItem(launched)) {
            e.setCancelled(true);
            player.sendMessage("§cТрезубец-скилл нельзя бросать как обычное оружие.");
            return;
        }

        PlayerInventory inv = player.getInventory();
        ItemStack main = inv.getItemInMainHand();
        if (main == null)
            return;
        if (main.getType() != org.bukkit.Material.TRIDENT)
            return;
        if (!plugin.kit().isProtectedSkillItem(main))
            return;

        e.setCancelled(true);
        player.sendMessage("§cТрезубец-скилл нельзя бросать как обычное оружие.");
    }

    /**
     * Блокировать крафт с защищёнными предметами.
     */
    @EventHandler
    public void onCraftItem(CraftItemEvent e) {
        // Проверяем все ингредиенты в рецепте
        for (ItemStack ingredient : e.getInventory().getMatrix()) {
            if (ingredient != null && plugin.kit().isProtectedSkillItem(ingredient)) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    p.sendMessage("§cФункция скилла нужна для боевых действий. Крафт недоступен!");
                }
                return;
            }
        }
    }

    /**
     * Блокировать использование в других инвентарях (печь, автомат и т.д.).
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        ItemStack cursor = e.getCursor();
        ItemStack clicked = e.getCurrentItem();

        // Проверяем предмет в курсоре
        if (cursor != null && plugin.kit().isProtectedSkillItem(cursor)) {
            // Запретить перемещение в печь, автомат и прочее
            InventoryType type = e.getInventory().getType();
            if (type == InventoryType.FURNACE || type == InventoryType.HOPPER ||
                    type == InventoryType.DISPENSER || type == InventoryType.DROPPER ||
                    type == InventoryType.BREWING || type == InventoryType.ANVIL ||
                    type == InventoryType.BEACON) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    p.sendMessage("§cЭтот предмет нельзя использовать здесь!");
                }
            }
        }

        // Проверяем щёлкаемый предмет
        if (clicked != null && plugin.kit().isProtectedSkillItem(clicked)) {
            InventoryType type = e.getInventory().getType();
            if (type == InventoryType.FURNACE || type == InventoryType.HOPPER ||
                    type == InventoryType.DISPENSER || type == InventoryType.DROPPER) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    p.sendMessage("§cЭтот предмет нельзя использовать здесь!");
                }
            }
        }
    }
}
