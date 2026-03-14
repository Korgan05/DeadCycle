package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class KitMenu implements Listener {

    private final DeadCyclePlugin plugin;
    private final Map<UUID, Long> lastOpenAt = new HashMap<>();
    private static final int[] KIT_SLOTS = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25 };
    private static final List<KitManager.Kit> KIT_ORDER = List.of(
            KitManager.Kit.FIGHTER,
            KitManager.Kit.MINER,
            KitManager.Kit.BUILDER,
            KitManager.Kit.ARCHER,
            KitManager.Kit.BERSERK,
            KitManager.Kit.GRAVITATOR,
            KitManager.Kit.DUELIST,
            KitManager.Kit.CLONER,
            KitManager.Kit.SUMMONER,
            KitManager.Kit.PING,
            KitManager.Kit.HARPOONER,
            KitManager.Kit.CYBORG,
            KitManager.Kit.MEDIC,
            KitManager.Kit.EXORCIST);

    public KitMenu(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        if (p == null || !p.isOnline())
            return;

        String currentTitle = null;
        try {
            currentTitle = ChatColor.stripColor(p.getOpenInventory().getTitle());
        } catch (Throwable ignored) {
        }
        if ("Выбор кита".equalsIgnoreCase(currentTitle))
            return;

        long now = System.currentTimeMillis();
        Long last = lastOpenAt.get(p.getUniqueId());
        if (last != null && (now - last) < 500L)
            return;
        lastOpenAt.put(p.getUniqueId(), now);

        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_GREEN + "Выбор кита");
        fillFrame(inv);

        KitManager.Kit activeKit = plugin.kit().getKit(p.getUniqueId());
        int activeLevel = plugin.progress().getKitLevel(p.getUniqueId(), activeKit);

        inv.setItem(4, item(Material.NETHER_STAR,
                ChatColor.GOLD + "Текущий профиль",
                ChatColor.GRAY + "Активный кит: " + ChatColor.WHITE + plugin.kit().getKitDisplayName(activeKit),
                ChatColor.GRAY + "Уровень: " + ChatColor.AQUA + activeLevel,
                ChatColor.DARK_GRAY + "Выбери другой кит ниже"));

        for (int i = 0; i < KIT_ORDER.size() && i < KIT_SLOTS.length; i++) {
            KitManager.Kit kit = KIT_ORDER.get(i);
            inv.setItem(KIT_SLOTS[i], buildKitCard(p, kit, kit == activeKit));
        }

        inv.setItem(49, item(Material.BARRIER,
                ChatColor.RED + "Закрыть",
                ChatColor.DARK_GRAY + "ESC тоже работает"));

        p.openInventory(inv);
    }

    private ItemStack buildKitCard(Player p, KitManager.Kit kit, boolean active) {
        int level = plugin.progress().getKitLevel(p.getUniqueId(), kit);
        List<String> lore = new java.util.ArrayList<>();

        lore.add(ChatColor.GRAY + roleText(kit));
        lore.add(ChatColor.GRAY + "Уровень кита: " + ChatColor.AQUA + level);

        List<String> path = plugin.kit().getKitSkillOrder(kit);
        if (path.isEmpty()) {
            lore.add(ChatColor.DARK_GRAY + "Пассивная роль без предметов-активаторов");
        } else {
            lore.add(ChatColor.GRAY + "Путь навыков:");
            for (int i = 0; i < Math.min(path.size(), 3); i++) {
                String skillId = path.get(i);
                int unlock = plugin.kit().getSkillUnlockLevel(kit, skillId);
                lore.add(ChatColor.DARK_GRAY + "- ур. " + unlock + ": "
                        + ChatColor.WHITE + plugin.kit().getSkillDisplayName(skillId));
            }
        }

        if (active) {
            lore.add(ChatColor.GREEN + "Сейчас выбран");
        } else {
            lore.add(ChatColor.YELLOW + "Нажми, чтобы выбрать");
        }

        ItemStack it = item(iconForKit(kit),
                kitColor(kit) + plugin.kit().getKitDisplayName(kit),
                lore.toArray(new String[0]));
        if (active && it.hasItemMeta()) {
            ItemMeta meta = it.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    private String roleText(KitManager.Kit kit) {
        return switch (kit) {
            case FIGHTER -> "Ближний бой и высокая выживаемость";
            case MINER -> "Фарм ресурсов, ускоренная добыча";
            case BUILDER -> "Ремонт и апгрейд обороны базы";
            case ARCHER -> "Дальний бой, контроль дистанции";
            case BERSERK -> "Агрессия на низком HP, добивания";
            case GRAVITATOR -> "Контроль толпы через гравитацию";
            case DUELIST -> "Сильные 1v1 размены и контр-стойка";
            case CLONER -> "Клоны и смена режима поведения";
            case SUMMONER -> "Призывы и микро-менеджмент армии";
            case PING -> "Мобильность, рывки и темп";
            case HARPOONER -> "Зацеп цели и насильный вход в бой";
            case CYBORG -> "Вертикальная мобильность и ударные приземления";
            case MEDIC -> "Поддержка, исцеление и снятие дебаффов";
            case EXORCIST -> "Контроль нежити и очищающий урон по волнам";
        };
    }

    private Material iconForKit(KitManager.Kit kit) {
        return switch (kit) {
            case FIGHTER -> Material.IRON_SWORD;
            case MINER -> Material.IRON_PICKAXE;
            case BUILDER -> Material.SMITHING_TABLE;
            case ARCHER -> Material.BOW;
            case BERSERK -> Material.NETHERITE_AXE;
            case GRAVITATOR -> Material.HEAVY_CORE;
            case DUELIST -> Material.NETHER_STAR;
            case CLONER -> Material.ENDER_EYE;
            case SUMMONER -> Material.TOTEM_OF_UNDYING;
            case PING -> Material.ENDER_PEARL;
            case HARPOONER -> Material.TRIDENT;
            case CYBORG -> Material.BREEZE_ROD;
            case MEDIC -> Material.GOLDEN_APPLE;
            case EXORCIST -> Material.SOUL_LANTERN;
        };
    }

    private ChatColor kitColor(KitManager.Kit kit) {
        return switch (kit) {
            case FIGHTER -> ChatColor.RED;
            case MINER -> ChatColor.GOLD;
            case BUILDER -> ChatColor.GREEN;
            case ARCHER -> ChatColor.AQUA;
            case BERSERK -> ChatColor.DARK_RED;
            case GRAVITATOR -> ChatColor.DARK_PURPLE;
            case DUELIST -> ChatColor.LIGHT_PURPLE;
            case CLONER -> ChatColor.BLUE;
            case SUMMONER -> ChatColor.DARK_AQUA;
            case PING -> ChatColor.BLUE;
            case HARPOONER -> ChatColor.DARK_AQUA;
            case CYBORG -> ChatColor.YELLOW;
            case MEDIC -> ChatColor.GREEN;
            case EXORCIST -> ChatColor.AQUA;
        };
    }

    private KitManager.Kit kitBySlot(int slot) {
        for (int i = 0; i < KIT_SLOTS.length && i < KIT_ORDER.size(); i++) {
            if (KIT_SLOTS[i] == slot)
                return KIT_ORDER.get(i);
        }
        return null;
    }

    private void fillFrame(Inventory inv) {
        ItemStack frame = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        int size = inv.getSize();
        int rows = size / 9;

        for (int x = 0; x < 9; x++) {
            inv.setItem(x, frame);
            inv.setItem(size - 9 + x, frame);
        }

        for (int y = 1; y < rows - 1; y++) {
            inv.setItem(y * 9, frame);
            inv.setItem(y * 9 + 8, frame);
        }
    }

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore != null && lore.length > 0)
                im.setLore(Arrays.asList(lore));
            it.setItemMeta(im);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;
        if (e.getView().getTitle() == null)
            return;

        String title = ChatColor.stripColor(e.getView().getTitle());
        if (!"Выбор кита".equalsIgnoreCase(title))
            return;

        e.setCancelled(true);

        if (e.getCurrentItem() == null)
            return;

        int slot = e.getRawSlot();

        if (slot == 49) {
            p.closeInventory();
            return;
        }

        KitManager.Kit chosen = kitBySlot(slot);
        if (chosen == null)
            return;

        plugin.kit().giveKit(p, chosen);
        plugin.progress().setKitChoiceRequired(p.getUniqueId(), false);
        p.sendMessage(ChatColor.GREEN + "Ты выбрал кит: " + kitColor(chosen) + plugin.kit().getKitDisplayName(chosen));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        p.closeInventory();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p))
            return;
        if (e.getView().getTitle() == null)
            return;

        String title = ChatColor.stripColor(e.getView().getTitle());
        if (!"Выбор кита".equalsIgnoreCase(title))
            return;

        if (!plugin.progress().isKitChoiceRequired(p.getUniqueId()))
            return;

        long now = System.currentTimeMillis();
        Long last = lastOpenAt.get(p.getUniqueId());
        if (last != null && (now - last) < 300L)
            return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline())
                return;
            if (p.getGameMode() == GameMode.SPECTATOR)
                return;
            open(p);
        }, 2L);
    }
}
