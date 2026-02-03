package me.korgan.deadcycle.player;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

public class ProgressManager {

    private final DeadCyclePlugin plugin;
    private final PlayerDataStore store;

    public ProgressManager(DeadCyclePlugin plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // =========================
    // KIT selection persistence
    // =========================

    public KitManager.Kit getSavedKit(UUID uuid) {
        String raw = store.getString(uuid, "kit.selected", KitManager.Kit.FIGHTER.name());
        if (raw == null || raw.isBlank())
            return KitManager.Kit.FIGHTER;
        try {
            return KitManager.Kit.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return KitManager.Kit.FIGHTER;
        }
    }

    public void saveKit(UUID uuid, KitManager.Kit kit) {
        if (uuid == null || kit == null)
            return;
        store.setString(uuid, "kit.selected", kit.name());
        store.save();
    }

    // =========================
    // MINER
    // =========================
    public int getMinerLevel(UUID uuid) {
        return store.getInt(uuid, "miner.level", 1);
    }

    public int getMinerExp(UUID uuid) {
        return store.getInt(uuid, "miner.exp", 0);
    }

    public int getMinerNeedExp(UUID uuid) {
        int lvl = getMinerLevel(uuid);
        return calcNeed(lvl, "miner_progress.level_xp_base", "miner_progress.level_xp_add_per_level");
    }

    public void addMinerExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getMinerExp(uuid) + add;
        int lvl = getMinerLevel(uuid);
        int need = getMinerNeedExp(uuid);

        int max = plugin.getConfig().getInt("miner_progress.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "miner_progress.level_xp_base", "miner_progress.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Шахтёр повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "miner.level", lvl);
        store.setInt(uuid, "miner.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // FIGHTER
    // =========================
    public int getFighterLevel(UUID uuid) {
        return store.getInt(uuid, "fighter.level", 1);
    }

    public int getFighterExp(UUID uuid) {
        return store.getInt(uuid, "fighter.exp", 0);
    }

    public int getFighterNeedExp(UUID uuid) {
        int lvl = getFighterLevel(uuid);
        return calcNeed(lvl, "kit_xp.fighter.level_xp_base", "kit_xp.fighter.level_xp_add_per_level");
    }

    public void addFighterExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getFighterExp(uuid) + add;
        int lvl = getFighterLevel(uuid);
        int need = getFighterNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.fighter.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.fighter.level_xp_base", "kit_xp.fighter.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Боец повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "fighter.level", lvl);
        store.setInt(uuid, "fighter.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // BUILDER
    // =========================
    public int getBuilderLevel(UUID uuid) {
        return store.getInt(uuid, "builder.level", 1);
    }

    public int getBuilderExp(UUID uuid) {
        return store.getInt(uuid, "builder.exp", 0);
    }

    public int getBuilderNeedExp(UUID uuid) {
        int lvl = getBuilderLevel(uuid);
        return calcNeed(lvl, "kit_xp.builder.level_xp_base", "kit_xp.builder.level_xp_add_per_level");
    }

    public void addBuilderExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getBuilderExp(uuid) + add;
        int lvl = getBuilderLevel(uuid);
        int need = getBuilderNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.builder.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.builder.level_xp_base", "kit_xp.builder.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Билдер повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "builder.level", lvl);
        store.setInt(uuid, "builder.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // BERSERK
    // =========================

    public int getBerserkLevel(UUID uuid) {
        return store.getInt(uuid, "berserk.level", 1);
    }

    public int getBerserkExp(UUID uuid) {
        return store.getInt(uuid, "berserk.exp", 0);
    }

    public int getBerserkNeedExp(UUID uuid) {
        int lvl = getBerserkLevel(uuid);
        return calcNeed(lvl, "kit_xp.berserk.level_xp_base", "kit_xp.berserk.level_xp_add_per_level");
    }

    public void addBerserkExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getBerserkExp(uuid) + add;
        int lvl = getBerserkLevel(uuid);
        int need = getBerserkNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.berserk.max_level", 5);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.berserk.level_xp_base", "kit_xp.berserk.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Берсерк повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "berserk.level", lvl);
        store.setInt(uuid, "berserk.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // GENERIC KIT access (для scoreboard/GUI)
    // =========================

    // ✅ Fix: убрали ссылку на ARCHER (чтобы не было cannot find symbol)
    public int getKitLevel(UUID uuid, KitManager.Kit kit) {
        if (kit == null)
            return 0;
        return switch (kit) {
            case MINER -> getMinerLevel(uuid);
            case FIGHTER -> getFighterLevel(uuid);
            case BUILDER -> getBuilderLevel(uuid);
            case BERSERK -> getBerserkLevel(uuid);
            default -> 0;
        };
    }

    public int getKitExp(UUID uuid, KitManager.Kit kit) {
        if (kit == null)
            return 0;
        return switch (kit) {
            case MINER -> getMinerExp(uuid);
            case FIGHTER -> getFighterExp(uuid);
            case BUILDER -> getBuilderExp(uuid);
            case BERSERK -> getBerserkExp(uuid);
            default -> 0;
        };
    }

    public int getKitNeedExp(UUID uuid, KitManager.Kit kit) {
        if (kit == null)
            return 0;
        return switch (kit) {
            case MINER -> getMinerNeedExp(uuid);
            case FIGHTER -> getFighterNeedExp(uuid);
            case BUILDER -> getBuilderNeedExp(uuid);
            case BERSERK -> getBerserkNeedExp(uuid);
            default -> 0;
        };
    }

    // ✅ Для твоего KitMenu, если где-то случайно передают Player вместо UUID
    public int getKitLevel(Player p, KitManager.Kit kit) {
        return getKitLevel(p.getUniqueId(), kit);
    }

    public int getKitExp(Player p, KitManager.Kit kit) {
        return getKitExp(p.getUniqueId(), kit);
    }

    public int getKitNeedExp(Player p, KitManager.Kit kit) {
        return getKitNeedExp(p.getUniqueId(), kit);
    }

    // =========================
    // helpers
    // =========================
    private int calcNeed(int lvl, String basePath, String addPath) {
        int base = plugin.getConfig().getInt(basePath, 80);
        int add = plugin.getConfig().getInt(addPath, 40);
        return base + (lvl - 1) * add;
    }

    /**
     * Применяет эффекты от кита/уровня. Сейчас: haste у шахтёра.
     */
    public void applyKitEffects(Player p) {
        KitManager.Kit kit = plugin.kit().getKit(p.getUniqueId());

        PotionEffectType strength = strengthType();
        Attribute maxHealthAttr = maxHealthAttribute();

        // Чистим только "не свойственные" эффекты, чтобы киты не тащили бафы друг
        // друга.
        if (kit != KitManager.Kit.MINER) {
            p.removePotionEffect(PotionEffectType.HASTE);
        }
        if (kit != KitManager.Kit.FIGHTER) {
            if (strength != null)
                p.removePotionEffect(strength);
            p.removePotionEffect(PotionEffectType.HEALTH_BOOST);
        }

        // ===== MINER: Haste =====
        if (kit == KitManager.Kit.MINER) {
            int lvl = getMinerLevel(p.getUniqueId());
            int max = plugin.getConfig().getInt("miner_progress.haste_max_level", 4);
            int hasteLevel = Math.min(Math.max(lvl, 1), max);

            int amp = Math.max(0, hasteLevel - 1);
            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 20 * 999999, amp, true, false, true));
        }

        // ===== FIGHTER: HP + Strength =====
        if (kit == KitManager.Kit.FIGHTER) {
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("kit_buffs.fighter");
            boolean enabled = sec == null || sec.getBoolean("enabled", true);
            if (!enabled)
                return;

            int lvl = getFighterLevel(p.getUniqueId());

            // HP (Health Boost): lvl2-4 -> I, lvl5-7 -> II, lvl8-10 -> III
            int hbStart = (sec == null) ? 2 : sec.getInt("health_boost_start_level", 2);
            int hbEvery = (sec == null) ? 3 : sec.getInt("health_boost_every_levels", 3);
            int hbMaxLevel = (sec == null) ? 3 : sec.getInt("health_boost_max_level", 3); // 1..n (не amp)

            int hbLevel = 0;
            if (lvl >= hbStart) {
                hbLevel = 1 + Math.max(0, (lvl - hbStart) / Math.max(1, hbEvery));
                hbLevel = Math.min(hbLevel, Math.max(1, hbMaxLevel));
            }
            if (hbLevel > 0) {
                int amp = hbLevel - 1;
                p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 20 * 999999, amp, true, false, true));

                // Визуально "хп не добавилось" часто потому, что max HP вырос,
                // а текущее HP осталось прежним. Подлечим до нового max.
                org.bukkit.attribute.AttributeInstance a = (maxHealthAttr == null) ? null
                        : p.getAttribute(maxHealthAttr);
                if (a != null) {
                    double max = a.getValue();
                    if (p.getHealth() < max) {
                        p.setHealth(Math.min(max, max));
                    }
                }
            }

            // Strength: по умолчанию lvl6+ -> I, lvl10 -> II
            int strL1 = (sec == null) ? 6 : sec.getInt("strength_level_1_start", 6);
            int strL2 = (sec == null) ? 10 : sec.getInt("strength_level_2_start", 10);

            int strLevel = 0;
            if (lvl >= strL2)
                strLevel = 2;
            else if (lvl >= strL1)
                strLevel = 1;

            if (strLevel > 0) {
                int amp = strLevel - 1;
                if (strength != null) {
                    p.addPotionEffect(new PotionEffect(strength, 20 * 999999, amp, true, false, true));
                }
            }
        }
    }

    private PotionEffectType strengthType() {
        // В новых версиях это "strength" (раньше в Bukkit встречалось как
        // INCREASE_DAMAGE).
        // Берём по key, чтобы не зависеть от наличия конкретного поля в API.
        return PotionEffectType.getByKey(NamespacedKey.minecraft("strength"));
    }

    private Attribute maxHealthAttribute() {
        // В новых версиях это "max_health", в старых встречалось "generic_max_health".
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        if (a != null)
            return a;
        return Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic_max_health"));
    }
}
