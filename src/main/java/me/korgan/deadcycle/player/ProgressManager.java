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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class ProgressManager {

    private final DeadCyclePlugin plugin;
    private final PlayerDataStore store;
    private final Map<UUID, PlayerProgressSnapshot> pendingPlayerProgress = new HashMap<>();

    private static final class PlayerProgressSnapshot {
        final int level;
        final int exp;

        private PlayerProgressSnapshot(int level, int exp) {
            this.level = Math.max(1, level);
            this.exp = Math.max(0, exp);
        }
    }

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

    public boolean isKitChoiceRequired(UUID uuid) {
        if (uuid == null)
            return false;
        return store.getInt(uuid, "kit.must_choose", 0) == 1;
    }

    public void setKitChoiceRequired(UUID uuid, boolean required) {
        if (uuid == null)
            return;
        store.setInt(uuid, "kit.must_choose", required ? 1 : 0);
        store.save();
    }

    // =========================
    // PLAYER LEVEL (общий)
    // =========================

    public int getPlayerLevel(UUID uuid) {
        return store.getInt(uuid, "player.level", 1);
    }

    public int getPlayerExp(UUID uuid) {
        return store.getInt(uuid, "player.exp", 0);
    }

    public int getPlayerNeedExp(UUID uuid) {
        int lvl = getPlayerLevel(uuid);
        return calcNeed(lvl, "player_progress.level_xp_base", "player_progress.level_xp_add_per_level");
    }

    public void addPlayerExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getPlayerExp(uuid) + add;
        int lvl = getPlayerLevel(uuid);
        int need = getPlayerNeedExp(uuid);

        int max = plugin.getConfig().getInt("player_progress.max_level", 50);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "player_progress.level_xp_base", "player_progress.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Уровень игрока повышен! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "player.level", lvl);
        store.setInt(uuid, "player.exp", exp);
    }

    public void preparePlayerProgressForReset() {
        pendingPlayerProgress.clear();

        for (UUID uuid : store.getKnownPlayerIds()) {
            int lvl = store.getInt(uuid, "player.level", 1);
            int exp = store.getInt(uuid, "player.exp", 0);
            pendingPlayerProgress.put(uuid, new PlayerProgressSnapshot(lvl, exp));
        }

        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            pendingPlayerProgress.put(uuid, new PlayerProgressSnapshot(getPlayerLevel(uuid), getPlayerExp(uuid)));
        }
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
    // ARCHER
    // =========================
    public int getArcherLevel(UUID uuid) {
        return store.getInt(uuid, "archer.level", 1);
    }

    public int getArcherExp(UUID uuid) {
        return store.getInt(uuid, "archer.exp", 0);
    }

    public int getArcherNeedExp(UUID uuid) {
        int lvl = getArcherLevel(uuid);
        return calcNeed(lvl, "kit_xp.archer.level_xp_base", "kit_xp.archer.level_xp_add_per_level");
    }

    public void addArcherExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getArcherExp(uuid) + add;
        int lvl = getArcherLevel(uuid);
        int need = getArcherNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.archer.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.archer.level_xp_base", "kit_xp.archer.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Лучник повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "archer.level", lvl);
        store.setInt(uuid, "archer.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // GRAVITATOR
    // =========================
    public int getGravitatorLevel(UUID uuid) {
        return store.getInt(uuid, "gravitator.level", 1);
    }

    public int getGravitatorExp(UUID uuid) {
        return store.getInt(uuid, "gravitator.exp", 0);
    }

    public int getGravitatorNeedExp(UUID uuid) {
        int lvl = getGravitatorLevel(uuid);
        return calcNeed(lvl, "kit_xp.gravitator.level_xp_base", "kit_xp.gravitator.level_xp_add_per_level");
    }

    public void addGravitatorExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getGravitatorExp(uuid) + add;
        int lvl = getGravitatorLevel(uuid);
        int need = getGravitatorNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.gravitator.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.gravitator.level_xp_base", "kit_xp.gravitator.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Гравитатор повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "gravitator.level", lvl);
        store.setInt(uuid, "gravitator.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // DUELIST
    // =========================
    public int getDuelistLevel(UUID uuid) {
        return store.getInt(uuid, "duelist.level", 1);
    }

    public int getDuelistExp(UUID uuid) {
        return store.getInt(uuid, "duelist.exp", 0);
    }

    public int getDuelistNeedExp(UUID uuid) {
        int lvl = getDuelistLevel(uuid);
        return calcNeed(lvl, "kit_xp.duelist.level_xp_base", "kit_xp.duelist.level_xp_add_per_level");
    }

    public void addDuelistExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getDuelistExp(uuid) + add;
        int lvl = getDuelistLevel(uuid);
        int need = getDuelistNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.duelist.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.duelist.level_xp_base", "kit_xp.duelist.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Ритуалист повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "duelist.level", lvl);
        store.setInt(uuid, "duelist.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // CLONER
    // =========================
    public int getClonerLevel(UUID uuid) {
        return store.getInt(uuid, "cloner.level", 1);
    }

    public int getClonerExp(UUID uuid) {
        return store.getInt(uuid, "cloner.exp", 0);
    }

    public int getClonerNeedExp(UUID uuid) {
        int lvl = getClonerLevel(uuid);
        return calcNeed(lvl, "kit_xp.cloner.level_xp_base", "kit_xp.cloner.level_xp_add_per_level");
    }

    public void addClonerExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getClonerExp(uuid) + add;
        int lvl = getClonerLevel(uuid);
        int need = getClonerNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.cloner.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.cloner.level_xp_base", "kit_xp.cloner.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Клонер повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "cloner.level", lvl);
        store.setInt(uuid, "cloner.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // SUMMONER
    // =========================
    public int getSummonerLevel(UUID uuid) {
        return store.getInt(uuid, "summoner.level", 1);
    }

    public int getSummonerExp(UUID uuid) {
        return store.getInt(uuid, "summoner.exp", 0);
    }

    public int getSummonerNeedExp(UUID uuid) {
        int lvl = getSummonerLevel(uuid);
        return calcNeed(lvl, "kit_xp.summoner.level_xp_base", "kit_xp.summoner.level_xp_add_per_level");
    }

    public void addSummonerExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getSummonerExp(uuid) + add;
        int lvl = getSummonerLevel(uuid);
        int need = getSummonerNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.summoner.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.summoner.level_xp_base", "kit_xp.summoner.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Призыватель повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "summoner.level", lvl);
        store.setInt(uuid, "summoner.exp", exp);

        applyKitEffects(p);
        if (plugin.summonerKit() != null && plugin.kit().getKit(uuid) == KitManager.Kit.SUMMONER) {
            plugin.summonerKit().syncSkillItems(p);
        }
    }

    // =========================
    // PING
    // =========================
    public int getPingLevel(UUID uuid) {
        return store.getInt(uuid, "ping.level", 1);
    }

    public int getPingExp(UUID uuid) {
        return store.getInt(uuid, "ping.exp", 0);
    }

    public int getPingNeedExp(UUID uuid) {
        int lvl = getPingLevel(uuid);
        return calcNeed(lvl, "kit_xp.ping.level_xp_base", "kit_xp.ping.level_xp_add_per_level");
    }

    public void addPingExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getPingExp(uuid) + add;
        int lvl = getPingLevel(uuid);
        int need = getPingNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.ping.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.ping.level_xp_base", "kit_xp.ping.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Пинг повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "ping.level", lvl);
        store.setInt(uuid, "ping.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // HARPOONER
    // =========================
    public int getHarpoonerLevel(UUID uuid) {
        return store.getInt(uuid, "harpooner.level", 1);
    }

    public int getHarpoonerExp(UUID uuid) {
        return store.getInt(uuid, "harpooner.exp", 0);
    }

    public int getHarpoonerNeedExp(UUID uuid) {
        int lvl = getHarpoonerLevel(uuid);
        return calcNeed(lvl, "kit_xp.harpooner.level_xp_base", "kit_xp.harpooner.level_xp_add_per_level");
    }

    public void addHarpoonerExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getHarpoonerExp(uuid) + add;
        int lvl = getHarpoonerLevel(uuid);
        int need = getHarpoonerNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.harpooner.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.harpooner.level_xp_base", "kit_xp.harpooner.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Гарпунер повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "harpooner.level", lvl);
        store.setInt(uuid, "harpooner.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // CYBORG
    // =========================
    public int getCyborgLevel(UUID uuid) {
        return store.getInt(uuid, "cyborg.level", 1);
    }

    public int getCyborgExp(UUID uuid) {
        return store.getInt(uuid, "cyborg.exp", 0);
    }

    public int getCyborgNeedExp(UUID uuid) {
        int lvl = getCyborgLevel(uuid);
        return calcNeed(lvl, "kit_xp.cyborg.level_xp_base", "kit_xp.cyborg.level_xp_add_per_level");
    }

    public void addCyborgExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getCyborgExp(uuid) + add;
        int lvl = getCyborgLevel(uuid);
        int need = getCyborgNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.cyborg.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.cyborg.level_xp_base", "kit_xp.cyborg.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Киборг повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "cyborg.level", lvl);
        store.setInt(uuid, "cyborg.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // MEDIC
    // =========================
    public int getMedicLevel(UUID uuid) {
        return store.getInt(uuid, "medic.level", 1);
    }

    public int getMedicExp(UUID uuid) {
        return store.getInt(uuid, "medic.exp", 0);
    }

    public int getMedicNeedExp(UUID uuid) {
        int lvl = getMedicLevel(uuid);
        return calcNeed(lvl, "kit_xp.medic.level_xp_base", "kit_xp.medic.level_xp_add_per_level");
    }

    public void addMedicExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getMedicExp(uuid) + add;
        int lvl = getMedicLevel(uuid);
        int need = getMedicNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.medic.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.medic.level_xp_base", "kit_xp.medic.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Медик повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "medic.level", lvl);
        store.setInt(uuid, "medic.exp", exp);

        applyKitEffects(p);
    }

    // =========================
    // EXORCIST
    // =========================
    public int getExorcistLevel(UUID uuid) {
        return store.getInt(uuid, "exorcist.level", 1);
    }

    public int getExorcistExp(UUID uuid) {
        return store.getInt(uuid, "exorcist.exp", 0);
    }

    public int getExorcistNeedExp(UUID uuid) {
        int lvl = getExorcistLevel(uuid);
        return calcNeed(lvl, "kit_xp.exorcist.level_xp_base", "kit_xp.exorcist.level_xp_add_per_level");
    }

    public void addExorcistExp(Player p, int add) {
        UUID uuid = p.getUniqueId();
        int exp = getExorcistExp(uuid) + add;
        int lvl = getExorcistLevel(uuid);
        int need = getExorcistNeedExp(uuid);

        int max = plugin.getConfig().getInt("kit_xp.exorcist.max_level", 10);

        while (exp >= need && lvl < max) {
            exp -= need;
            lvl++;
            need = calcNeed(lvl, "kit_xp.exorcist.level_xp_base", "kit_xp.exorcist.level_xp_add_per_level");
            p.sendMessage(ChatColor.GREEN + "Экзорцист повысил уровень! Теперь: " + ChatColor.WHITE + lvl);
        }

        store.setInt(uuid, "exorcist.level", lvl);
        store.setInt(uuid, "exorcist.exp", exp);

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
            case ARCHER -> getArcherLevel(uuid);
            case GRAVITATOR -> getGravitatorLevel(uuid);
            case DUELIST -> getDuelistLevel(uuid);
            case CLONER -> getClonerLevel(uuid);
            case SUMMONER -> getSummonerLevel(uuid);
            case PING -> getPingLevel(uuid);
            case HARPOONER -> getHarpoonerLevel(uuid);
            case CYBORG -> getCyborgLevel(uuid);
            case MEDIC -> getMedicLevel(uuid);
            case EXORCIST -> getExorcistLevel(uuid);
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
            case ARCHER -> getArcherExp(uuid);
            case GRAVITATOR -> getGravitatorExp(uuid);
            case DUELIST -> getDuelistExp(uuid);
            case CLONER -> getClonerExp(uuid);
            case SUMMONER -> getSummonerExp(uuid);
            case PING -> getPingExp(uuid);
            case HARPOONER -> getHarpoonerExp(uuid);
            case CYBORG -> getCyborgExp(uuid);
            case MEDIC -> getMedicExp(uuid);
            case EXORCIST -> getExorcistExp(uuid);
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
            case ARCHER -> getArcherNeedExp(uuid);
            case GRAVITATOR -> getGravitatorNeedExp(uuid);
            case DUELIST -> getDuelistNeedExp(uuid);
            case CLONER -> getClonerNeedExp(uuid);
            case SUMMONER -> getSummonerNeedExp(uuid);
            case PING -> getPingNeedExp(uuid);
            case HARPOONER -> getHarpoonerNeedExp(uuid);
            case CYBORG -> getCyborgNeedExp(uuid);
            case MEDIC -> getMedicNeedExp(uuid);
            case EXORCIST -> getExorcistNeedExp(uuid);
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

    public void setKitLevel(UUID uuid, KitManager.Kit kit, int level) {
        if (kit == null || uuid == null)
            return;
        int lvl = Math.max(1, level);
        switch (kit) {
            case MINER -> store.setInt(uuid, "miner.level", lvl);
            case FIGHTER -> store.setInt(uuid, "fighter.level", lvl);
            case BUILDER -> store.setInt(uuid, "builder.level", lvl);
            case BERSERK -> store.setInt(uuid, "berserk.level", lvl);
            case ARCHER -> store.setInt(uuid, "archer.level", lvl);
            case GRAVITATOR -> store.setInt(uuid, "gravitator.level", lvl);
            case DUELIST -> store.setInt(uuid, "duelist.level", lvl);
            case CLONER -> store.setInt(uuid, "cloner.level", lvl);
            case SUMMONER -> store.setInt(uuid, "summoner.level", lvl);
            case PING -> store.setInt(uuid, "ping.level", lvl);
            case HARPOONER -> store.setInt(uuid, "harpooner.level", lvl);
            case CYBORG -> store.setInt(uuid, "cyborg.level", lvl);
            case MEDIC -> store.setInt(uuid, "medic.level", lvl);
            case EXORCIST -> store.setInt(uuid, "exorcist.level", lvl);
        }
        store.save();

        Player online = org.bukkit.Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            applyKitEffects(online);
        }
    }// =========================
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
            if (!enabled) {
                plugin.kit().syncSkillItemsForCurrentKit(p);
                return;
            }

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

        plugin.kit().syncSkillItemsForCurrentKit(p);
    }

    private PotionEffectType strengthType() {
        // Paper 1.21+ использует STRENGTH
        return PotionEffectType.STRENGTH;
    }

    private Attribute maxHealthAttribute() {
        // В новых версиях это "max_health", в старых встречалось "generic_max_health".
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        if (a != null)
            return a;
        return Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic_max_health"));
    }

    /**
     * Полный сброс прогресса всех китов (уровни и опыт) для всех игроков.
     * Вызывается при ресете игры (когда все игроки умирают).
     */
    public void resetAll() {
        for (Map.Entry<UUID, PlayerProgressSnapshot> entry : pendingPlayerProgress.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerProgressSnapshot snapshot = entry.getValue();
            store.setInt(uuid, "player.level", snapshot.level);
            store.setInt(uuid, "player.exp", snapshot.exp);
        }

        // Очищаем все данные китов в PlayerDataStore
        // Данные хранятся как: UUID.miner.level, UUID.fighter.exp и т.д.
        // PlayerDataStore.clearAll() уже вызывается в PhaseManager.reset(),
        // поэтому здесь просто обнуляем для онлайн-игроков на случай если они уже
        // загружены

        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();

            // Сбрасываем все уровни китов до 1
            store.setInt(uuid, "miner.level", 1);
            store.setInt(uuid, "fighter.level", 1);
            store.setInt(uuid, "builder.level", 1);
            store.setInt(uuid, "berserk.level", 1);
            store.setInt(uuid, "archer.level", 1);
            store.setInt(uuid, "gravitator.level", 1);
            store.setInt(uuid, "duelist.level", 1);
            store.setInt(uuid, "cloner.level", 1);
            store.setInt(uuid, "summoner.level", 1);
            store.setInt(uuid, "ping.level", 1);
            store.setInt(uuid, "harpooner.level", 1);
            store.setInt(uuid, "cyborg.level", 1);
            store.setInt(uuid, "medic.level", 1);
            store.setInt(uuid, "exorcist.level", 1);

            // Сбрасываем весь опыт до 0
            store.setInt(uuid, "miner.exp", 0);
            store.setInt(uuid, "fighter.exp", 0);
            store.setInt(uuid, "builder.exp", 0);
            store.setInt(uuid, "berserk.exp", 0);
            store.setInt(uuid, "archer.exp", 0);
            store.setInt(uuid, "gravitator.exp", 0);
            store.setInt(uuid, "duelist.exp", 0);
            store.setInt(uuid, "cloner.exp", 0);
            store.setInt(uuid, "summoner.exp", 0);
            store.setInt(uuid, "ping.exp", 0);
            store.setInt(uuid, "harpooner.exp", 0);
            store.setInt(uuid, "cyborg.exp", 0);
            store.setInt(uuid, "medic.exp", 0);
            store.setInt(uuid, "exorcist.exp", 0);

            // Помечаем что нужно заново выбрать кит
            store.setInt(uuid, "kit.must_choose", 1);
        }

        pendingPlayerProgress.clear();
        store.save();
    }
}
