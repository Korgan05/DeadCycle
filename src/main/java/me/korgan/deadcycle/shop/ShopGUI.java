package me.korgan.deadcycle.shop;

import me.korgan.deadcycle.DeadCyclePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.*;

public class ShopGUI implements Listener {

    private final DeadCyclePlugin plugin;

    private static final String TITLE_PREFIX = "§aDeadCycle §7• §fМагазин";
    private static final int INV_SIZE = 54;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final int SLOT_PREV = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 53;

    private static final int SLOT_BALANCE = 7;
    private static final int SLOT_CLOSE = 8;

    private final NamespacedKey baseScrollKey;
    private final NamespacedKey offerKey;

    private final Map<UUID, ViewState> viewByPlayer = new HashMap<>();

    private enum Category {
        COMBAT("§cБой", Material.IRON_SWORD),
        ARMOR("§bБроня", Material.IRON_CHESTPLATE),
        TOOLS("§eИнструменты", Material.IRON_PICKAXE),
        BLOCKS("§6Блоки", Material.BRICKS),
        FOOD("§aЕда", Material.COOKED_BEEF),
        SELL("§2Продать", Material.EMERALD),
        SPECIAL("§dОсобое", Material.ENDER_PEARL);

        final String display;
        final Material icon;

        Category(String display, Material icon) {
            this.display = display;
            this.icon = icon;
        }
    }

    private enum OfferType {
        BUY, SELL
    }

    private record Offer(
            OfferType type,
            Category category,
            String key,
            Material icon,
            int amount,
            long defaultPrice,
            String displayName,
            List<String> extraLore) {
    }

    private record ViewState(Category category, int page) {
    }

    private final List<Offer> offers = new ArrayList<>();

    public ShopGUI(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        this.baseScrollKey = new NamespacedKey(plugin, "base_scroll");
        this.offerKey = new NamespacedKey(plugin, "shop_offer");
        seedOffers();
    }

    public void open(Player p) {
        ViewState state = viewByPlayer.getOrDefault(p.getUniqueId(), new ViewState(Category.COMBAT, 0));
        open(p, state.category(), state.page());
    }

    private void open(Player p, Category category, int page) {
        int safePage = Math.max(0, page);
        viewByPlayer.put(p.getUniqueId(), new ViewState(category, safePage));

        String title = TITLE_PREFIX + " §7(" + category.display + "§7)";
        Inventory inv = Bukkit.createInventory(null, INV_SIZE, LEGACY.deserialize(title));

        decorate(inv, p, category, safePage);
        renderOffers(inv, p, category, safePage);

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        String t = LEGACY.serialize(e.getView().title());
        if (!t.startsWith(TITLE_PREFIX))
            return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p))
            return;
        int slot = e.getRawSlot();
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR)
            return;

        if (slot == SLOT_CLOSE) {
            p.closeInventory();
            return;
        }

        UUID uuid = p.getUniqueId();
        ViewState state = viewByPlayer.getOrDefault(uuid, new ViewState(Category.COMBAT, 0));

        // категории
        if (slot >= 0 && slot <= 6) {
            Category cat = switch (slot) {
                case 0 -> Category.COMBAT;
                case 1 -> Category.ARMOR;
                case 2 -> Category.TOOLS;
                case 3 -> Category.BLOCKS;
                case 4 -> Category.FOOD;
                case 5 -> Category.SELL;
                case 6 -> Category.SPECIAL;
                default -> state.category();
            };
            open(p, cat, 0);
            return;
        }

        // пагинация
        if (slot == SLOT_PREV) {
            open(p, state.category(), Math.max(0, state.page() - 1));
            return;
        }
        if (slot == SLOT_NEXT) {
            open(p, state.category(), state.page() + 1);
            return;
        }

        // обработка оффера через PDC
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null)
            return;
        String token = meta.getPersistentDataContainer().get(offerKey, PersistentDataType.STRING);
        if (token == null || token.isBlank())
            return;

        Offer offer = findOfferByToken(token);
        if (offer == null)
            return;

        int multiplier = (e.isShiftClick() ? 4 : 1);

        if (offer.type() == OfferType.BUY) {
            long unitPrice = getBuyPrice(offer.key(), offer.defaultPrice());
            if (unitPrice <= 0) {
                p.sendMessage("§cЭтот товар отключён.");
                return;
            }

            long total = unitPrice * (long) multiplier;
            if (!plugin.econ().has(p, total)) {
                p.sendMessage("§cНедостаточно денег.");
                return;
            }

            ItemStack give;
            if ("BASE_SCROLL".equalsIgnoreCase(offer.key())) {
                give = createTaggedScroll(offer.amount() * multiplier);
            } else if ("POTION_HEALING".equalsIgnoreCase(offer.key())) {
                give = createHealingPotion(offer.amount() * multiplier);
            } else {
                give = new ItemStack(offer.icon(), offer.amount() * multiplier);
            }

            plugin.econ().take(p, total);
            Map<Integer, ItemStack> left = p.getInventory().addItem(give);
            if (!left.isEmpty()) {
                // если не влезло — возвращаем деньги и не теряем предмет
                plugin.econ().give(p, total);
                p.sendMessage("§cНет места в инвентаре.");
                return;
            }

            p.sendMessage("§bКуплено: §f" + stripColor(offer.displayName()) + " §7за §e" + total);
            open(p, state.category(), state.page());
            return;
        }

        // SELL
        if (offer.type() == OfferType.SELL) {
            long price = getSellPrice(offer.icon(), offer.defaultPrice());
            if (price <= 0) {
                p.sendMessage("§cПродажа этого предмета отключена.");
                return;
            }

            int removed;
            if (e.isShiftClick()) {
                removed = removeAll(p, offer.icon());
            } else {
                removed = removeAmount(p, offer.icon(), 1);
            }

            if (removed <= 0) {
                p.sendMessage("§cУ тебя нет этого предмета.");
                return;
            }

            long total = price * (long) removed;
            plugin.econ().give(p, total);
            p.sendMessage("§aПродано: §f" + nice(offer.icon()) + " x" + removed + " §7за §e" + total);
            open(p, state.category(), state.page());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        viewByPlayer.remove(e.getPlayer().getUniqueId());
    }

    private ItemStack createTaggedScroll(int amount) {
        ItemStack it = new ItemStack(Material.PAPER, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(baseScrollKey, PersistentDataType.BYTE, (byte) 1);
            meta.displayName(LEGACY.deserialize("§dСвиток: телепорт на базу"));
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isBaseScroll(ItemStack it) {
        if (it == null || it.getType() != Material.PAPER)
            return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return false;
        Byte v = meta.getPersistentDataContainer().get(baseScrollKey, PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public void consumeOne(Player p, ItemStack inHand) {
        if (inHand.getAmount() <= 1)
            p.getInventory().setItemInMainHand(null);
        else
            inHand.setAmount(inHand.getAmount() - 1);
    }

    // ===== helpers =====
    private ItemStack named(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            it.setItemMeta(meta);
        }
        return it;
    }

    private void decorate(Inventory inv, Player p, Category category, int page) {
        // фон
        ItemStack bg = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < INV_SIZE; i++) {
            inv.setItem(i, bg);
        }

        // категории (0..6)
        setCategoryButton(inv, 0, Category.COMBAT, category == Category.COMBAT);
        setCategoryButton(inv, 1, Category.ARMOR, category == Category.ARMOR);
        setCategoryButton(inv, 2, Category.TOOLS, category == Category.TOOLS);
        setCategoryButton(inv, 3, Category.BLOCKS, category == Category.BLOCKS);
        setCategoryButton(inv, 4, Category.FOOD, category == Category.FOOD);
        setCategoryButton(inv, 5, Category.SELL, category == Category.SELL);
        setCategoryButton(inv, 6, Category.SPECIAL, category == Category.SPECIAL);

        // баланс + закрыть
        inv.setItem(SLOT_BALANCE, named(Material.EMERALD, "§eБаланс: §6" + plugin.econ().getMoney(p.getUniqueId())));
        inv.setItem(SLOT_CLOSE, named(Material.BARRIER, "§cЗакрыть"));

        // навигация
        inv.setItem(SLOT_PREV, named(Material.ARROW, "§f← Назад"));
        inv.setItem(SLOT_NEXT, named(Material.ARROW, "§fВперёд →"));
        inv.setItem(SLOT_INFO, infoItem(category, page));
    }

    private void setCategoryButton(Inventory inv, int slot, Category cat, boolean selected) {
        ItemStack it = new ItemStack(cat.icon);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize((selected ? "§a▶ " : "§7") + cat.display));
            meta.lore(List.of(LEGACY.deserialize("§7Нажми, чтобы открыть раздел")));
            it.setItemMeta(meta);
        }
        inv.setItem(slot, it);
    }

    private ItemStack infoItem(Category category, int page) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§fПодсказка"));
            meta.lore(List.of(
                    LEGACY.deserialize("§7ЛКМ — купить/продать"),
                    LEGACY.deserialize("§7Shift+ЛКМ — x4 покупка / продать всё"),
                    LEGACY.deserialize("§7Раздел: " + category.display),
                    LEGACY.deserialize("§7Страница: §f" + (page + 1))));
            it.setItemMeta(meta);
        }
        return it;
    }

    private void renderOffers(Inventory inv, Player p, Category category, int page) {
        List<Offer> list = offers.stream().filter(o -> o.category() == category).toList();
        int perPage = 36;
        int start = page * perPage;

        // чистим зону товаров (9..44)
        for (int i = 9; i <= 44; i++)
            inv.setItem(i, null);

        if (start >= list.size() && page > 0) {
            // если вышли за предел — откатимся
            open(p, category, 0);
            return;
        }

        int idx = 0;
        for (int i = start; i < list.size() && idx < perPage; i++, idx++) {
            Offer offer = list.get(i);
            int slot = 9 + idx;

            ItemStack it = buildOfferItem(offer);
            inv.setItem(slot, it);
        }
    }

    private ItemStack buildOfferItem(Offer offer) {
        long price = (offer.type() == OfferType.BUY)
                ? getBuyPrice(offer.key(), offer.defaultPrice())
                : getSellPrice(offer.icon(), offer.defaultPrice());

        ItemStack it;
        if (offer.type() == OfferType.BUY && "POTION_HEALING".equalsIgnoreCase(offer.key())) {
            it = createHealingPotion(1);
        } else if (offer.type() == OfferType.BUY && "BASE_SCROLL".equalsIgnoreCase(offer.key())) {
            it = createTaggedScroll(1);
        } else {
            it = new ItemStack(offer.icon(), 1);
        }

        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(offer.displayName()));

            List<String> lore = new ArrayList<>();
            if (offer.type() == OfferType.BUY) {
                lore.add("§7Цена: §e" + price);
                lore.add("§7Вы получите: §fx" + offer.amount());
                lore.add(" ");
                lore.add("§fЛКМ §7— купить");
                lore.add("§fShift+ЛКМ §7— купить x4");
            } else {
                lore.add("§7Цена за 1: §e" + price);
                lore.add(" ");
                lore.add("§fЛКМ §7— продать 1");
                lore.add("§fShift+ЛКМ §7— продать всё");
            }

            if (offer.extraLore() != null && !offer.extraLore().isEmpty()) {
                lore.add(" ");
                lore.addAll(offer.extraLore());
            }

            meta.lore(lore.stream().map(LEGACY::deserialize).toList());
            meta.getPersistentDataContainer().set(offerKey, PersistentDataType.STRING, tokenForOffer(offer));
            it.setItemMeta(meta);
        }
        return it;
    }

    private String tokenForOffer(Offer offer) {
        return offer.type().name() + ":" + offer.key();
    }

    private Offer findOfferByToken(String token) {
        for (Offer o : offers) {
            if (tokenForOffer(o).equalsIgnoreCase(token))
                return o;
        }
        return null;
    }

    private long getBuyPrice(String key, long def) {
        String path = "shop.buy." + key;
        if (plugin.getConfig().contains(path))
            return plugin.getConfig().getLong(path);

        // совместимость со старым ключом
        if ("POTION_HEALING".equalsIgnoreCase(key) && plugin.getConfig().contains("shop.buy.POTION")) {
            return plugin.getConfig().getLong("shop.buy.POTION");
        }

        return def;
    }

    private long getSellPrice(Material material, long def) {
        String path = "shop.sell." + material.name();
        if (plugin.getConfig().contains(path))
            return plugin.getConfig().getLong(path);
        return def;
    }

    private ItemStack createHealingPotion(int amount) {
        ItemStack it = new ItemStack(Material.POTION, Math.max(1, amount));
        ItemMeta raw = it.getItemMeta();
        if (raw instanceof PotionMeta meta) {
            meta.setBasePotionType(PotionType.HEALING);
            meta.displayName(LEGACY.deserialize("§dЗелье лечения"));
            it.setItemMeta(meta);
        }
        return it;
    }

    private int removeAll(Player p, Material m) {
        int total = 0;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null || it.getType() != m)
                continue;
            total += it.getAmount();
            p.getInventory().setItem(i, null);
        }
        return total;
    }

    private int removeAmount(Player p, Material m, int amount) {
        int need = Math.max(0, amount);
        int removed = 0;
        for (int i = 0; i < p.getInventory().getSize() && need > 0; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it == null || it.getType() != m)
                continue;

            int take = Math.min(need, it.getAmount());
            need -= take;
            removed += take;
            int left = it.getAmount() - take;
            if (left <= 0)
                p.getInventory().setItem(i, null);
            else
                it.setAmount(left);
        }
        return removed;
    }

    private String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String stripColor(String s) {
        if (s == null)
            return "";
        return s.replaceAll("§.", "");
    }

    private void seedOffers() {
        // COMBAT
        offers.add(new Offer(OfferType.BUY, Category.COMBAT, "STONE_SWORD", Material.STONE_SWORD, 1, 25,
                "§cКаменный меч", List.of("§7Быстрое и дешёвое оружие")));
        offers.add(new Offer(OfferType.BUY, Category.COMBAT, "IRON_SWORD", Material.IRON_SWORD, 1, 120,
                "§cЖелезный меч", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.COMBAT, "SHIELD", Material.SHIELD, 1, 80,
                "§cЩит", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.COMBAT, "BOW", Material.BOW, 1, 150,
                "§cЛук", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.COMBAT, "ARROW_16", Material.ARROW, 16, 25,
                "§cСтрелы §7(x16)", List.of()));

        // ARMOR
        offers.add(new Offer(OfferType.BUY, Category.ARMOR, "LEATHER_HELMET", Material.LEATHER_HELMET, 1, 25,
                "§bКожаный шлем", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.ARMOR, "LEATHER_CHESTPLATE", Material.LEATHER_CHESTPLATE, 1, 45,
                "§bКожаная кираса", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.ARMOR, "LEATHER_LEGGINGS", Material.LEATHER_LEGGINGS, 1, 40,
                "§bКожаные поножи", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.ARMOR, "LEATHER_BOOTS", Material.LEATHER_BOOTS, 1, 25,
                "§bКожаные ботинки", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.ARMOR, "IRON_HELMET", Material.IRON_HELMET, 1, 160,
                "§bЖелезный шлем", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.ARMOR, "IRON_CHESTPLATE", Material.IRON_CHESTPLATE, 1, 250,
                "§bЖелезная кираса", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.ARMOR, "IRON_LEGGINGS", Material.IRON_LEGGINGS, 1, 220,
                "§bЖелезные поножи", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.ARMOR, "IRON_BOOTS", Material.IRON_BOOTS, 1, 140,
                "§bЖелезные ботинки", List.of()));

        // TOOLS
        offers.add(new Offer(OfferType.BUY, Category.TOOLS, "IRON_PICKAXE", Material.IRON_PICKAXE, 1, 180,
                "§eЖелезная кирка", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.TOOLS, "IRON_AXE", Material.IRON_AXE, 1, 140,
                "§eЖелезный топор", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.TOOLS, "IRON_SHOVEL", Material.IRON_SHOVEL, 1, 60,
                "§eЖелезная лопата", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.TOOLS, "SHEARS", Material.SHEARS, 1, 45,
                "§eНожницы", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.TOOLS, "TORCH_16", Material.TORCH, 16, 20,
                "§eФакелы §7(x16)", List.of()));

        // BLOCKS
        offers.add(new Offer(OfferType.BUY, Category.BLOCKS, "COBBLESTONE_64", Material.COBBLESTONE, 64, 30,
                "§6Булыжник §7(x64)", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.BLOCKS, "OAK_PLANKS_64", Material.OAK_PLANKS, 64, 35,
                "§6Доски дуба §7(x64)", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.BLOCKS, "SPRUCE_PLANKS_64", Material.SPRUCE_PLANKS, 64, 35,
                "§6Доски ели §7(x64)", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.BLOCKS, "STONE_BRICKS_64", Material.STONE_BRICKS, 64, 55,
                "§6Каменные кирпичи §7(x64)", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.BLOCKS, "GLASS_16", Material.GLASS, 16, 25,
                "§6Стекло §7(x16)", List.of()));

        // FOOD
        offers.add(new Offer(OfferType.BUY, Category.FOOD, "BREAD_16", Material.BREAD, 16, 22,
                "§aХлеб §7(x16)", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.FOOD, "COOKED_BEEF_16", Material.COOKED_BEEF, 16, 35,
                "§aСтейк §7(x16)", List.of()));
        offers.add(new Offer(OfferType.BUY, Category.FOOD, "GOLDEN_APPLE", Material.GOLDEN_APPLE, 1, 180,
                "§aЗолотое яблоко", List.of()));

        // SPECIAL
        offers.add(new Offer(OfferType.BUY, Category.SPECIAL, "POTION_HEALING", Material.POTION, 1, 220,
                "§dЗелье лечения", List.of("§7Мгновенно лечит")));
        offers.add(new Offer(OfferType.BUY, Category.SPECIAL, "ENDER_PEARL_4", Material.ENDER_PEARL, 4, 120,
                "§dЖемчуг эндера §7(x4)", List.of("§7Полезно для спасения")));
        offers.add(new Offer(OfferType.BUY, Category.SPECIAL, "BASE_SCROLL", Material.PAPER, 1, 800,
                "§dСвиток телепорта на базу", List.of("§7ПКМ — телепорт на базу")));

        // SELL (консервативные дефолты; можно переопределить в config.yml)
        offers.add(new Offer(OfferType.SELL, Category.SELL, "COBBLESTONE", Material.COBBLESTONE, 1, 1,
                "§2Продать: §fБулыжник", List.of()));
        offers.add(new Offer(OfferType.SELL, Category.SELL, "COAL", Material.COAL, 1, 4,
                "§2Продать: §fУголь", List.of()));
        offers.add(new Offer(OfferType.SELL, Category.SELL, "IRON_INGOT", Material.IRON_INGOT, 1, 10,
                "§2Продать: §fЖелезо", List.of()));
        offers.add(new Offer(OfferType.SELL, Category.SELL, "GOLD_INGOT", Material.GOLD_INGOT, 1, 14,
                "§2Продать: §fЗолото", List.of()));
        offers.add(new Offer(OfferType.SELL, Category.SELL, "DIAMOND", Material.DIAMOND, 1, 30,
                "§2Продать: §fАлмаз", List.of()));
    }
}
