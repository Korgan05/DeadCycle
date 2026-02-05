package me.korgan.deadcycle;

import me.korgan.deadcycle.base.*;
import me.korgan.deadcycle.econ.*;
import me.korgan.deadcycle.kit.*;
import me.korgan.deadcycle.mobs.*;
import me.korgan.deadcycle.phase.*;
import me.korgan.deadcycle.player.*;
import me.korgan.deadcycle.regen.*;
import me.korgan.deadcycle.scoreboard.*;
import me.korgan.deadcycle.shop.*;
import me.korgan.deadcycle.siege.*;
import me.korgan.deadcycle.system.*;
import me.korgan.deadcycle.zombies.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class DeadCyclePlugin extends JavaPlugin {

    private BaseManager base;
    private BaseResourceManager baseResources;

    private EconomyManager econ;

    private PlayerDataStore playerData;
    private ProgressManager progress;

    private KitManager kit;
    private KitMenu kitMenu;

    private BlockHealthManager blockHealth;
    private SiegeManager siege;
    private ZombieWaveManager zombie;
    private PhaseManager phase;

    private DeathSpectatorManager deathSpectator;

    private RepairGUI repairGui;
    private BaseGUI baseGui;
    private BaseUpgradeGUI baseUpgradeGui;
    private WallUpgradeGUI wallUpgradeGui;

    private ShopGUI shopGui;

    private BaseScoreboard scoreboard;
    private BukkitTask scoreboardTask;
    private BukkitTask siegeTickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        base = new BaseManager(this);
        baseResources = new BaseResourceManager(this);

        econ = new EconomyManager(this);

        playerData = new PlayerDataStore(this);
        progress = new ProgressManager(this, playerData);

        kit = new KitManager(this);
        kitMenu = new KitMenu(this);

        blockHealth = new BlockHealthManager(this);
        blockHealth.startVisuals();

        siege = new SiegeManager(this, blockHealth);
        zombie = new ZombieWaveManager(this);
        phase = new PhaseManager(this, siege);

        deathSpectator = new DeathSpectatorManager(this);

        repairGui = new RepairGUI(this, blockHealth);
        baseGui = new BaseGUI(this);
        baseUpgradeGui = new BaseUpgradeGUI(this);
        wallUpgradeGui = new WallUpgradeGUI(this);

        shopGui = new ShopGUI(this);

        scoreboard = new BaseScoreboard(this);

        // listeners
        Bukkit.getPluginManager().registerEvents(new GameRulesController(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerRulesListener(this), this);
        Bukkit.getPluginManager().registerEvents(deathSpectator, this);
        Bukkit.getPluginManager().registerEvents(new TemporaryBlocksListener(this), this);

        Bukkit.getPluginManager().registerEvents(econ, this);
        Bukkit.getPluginManager().registerEvents(new MobSpawnController(this), this);

        Bukkit.getPluginManager().registerEvents(new BaseBuildProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ResourceDepositListener(this), this);

        if (getConfig().getBoolean("regen_mining.enabled", true)) {
            Bukkit.getPluginManager().registerEvents(new RegenMiningListener(this), this);
        }

        Bukkit.getPluginManager().registerEvents(kit, this);
        Bukkit.getPluginManager().registerEvents(kitMenu, this);
        Bukkit.getPluginManager().registerEvents(new BerserkListener(this), this);

        Bukkit.getPluginManager().registerEvents(repairGui, this);
        Bukkit.getPluginManager().registerEvents(baseGui, this);
        Bukkit.getPluginManager().registerEvents(baseUpgradeGui, this);
        Bukkit.getPluginManager().registerEvents(wallUpgradeGui, this);

        Bukkit.getPluginManager().registerEvents(shopGui, this);
        Bukkit.getPluginManager().registerEvents(new BaseScrollListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BuilderToolListener(this), this);

        // commands
        if (getCommand("dc") != null)
            getCommand("dc").setExecutor(new DcCommand(this));
        if (getCommand("shop") != null)
            getCommand("shop").setExecutor(new ShopCommand(this));
        if (getCommand("kit") != null)
            getCommand("kit").setExecutor(new KitCommand(this));
        if (getCommand("money") != null)
            getCommand("money").setExecutor(new MoneyCommand(this));
        if (getCommand("repair") != null)
            getCommand("repair").setExecutor(new RepairCommand(this, repairGui));
        if (getCommand("base") != null)
            getCommand("base").setExecutor(new BaseCommand(this));

        // periodic scoreboard
        scoreboardTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                scoreboard.updateAll();
            } catch (Throwable t) {
                getLogger().warning("Scoreboard update failed: " + t.getMessage());
            }
        }, 40L, 40L);

        // siege ticking (делает осаду реально рабочей)
        long interval = Math.max(1L, getConfig().getLong("siege.tick_interval", 10L));
        siegeTickTask = Bukkit.getScheduler().runTaskTimer(this, this::tickSiege, 20L, interval);

        if (getConfig().getBoolean("phase.autostart", true)) {
            phase.start();
        }

        getLogger().info("DeadCycle enabled.");
    }

    @Override
    public void onDisable() {
        if (siegeTickTask != null) {
            siegeTickTask.cancel();
            siegeTickTask = null;
        }
        if (scoreboardTask != null) {
            scoreboardTask.cancel();
            scoreboardTask = null;
        }

        if (phase != null)
            phase.stop();
        if (blockHealth != null)
            blockHealth.stopVisuals();

        if (baseResources != null)
            baseResources.save();
        if (econ != null)
            econ.save();
        if (playerData != null)
            playerData.save();
    }

    private void tickSiege() {
        if (!getConfig().getBoolean("siege.enabled", true))
            return;
        if (phase == null || phase.getPhase() != PhaseManager.Phase.NIGHT)
            return;
        if (base == null || !base.isEnabled())
            return;

        // если ночь уже идёт и игроки пришли на базу позже — запускаем осаду позднее
        if (siege != null)
            siege.ensureNightRunning(phase.getDayCount());
        if (siege == null || !siege.isRunning())
            return;

        var center = base.getCenter();
        if (center == null || center.getWorld() == null)
            return;

        int extra = getConfig().getInt("siege.extra_radius", 8);
        int r = Math.max(1, base.getRadius() + extra);

        int day = phase.getDayCount();
        double dmgBase = getConfig().getDouble("siege.block_damage_base", 2.0);
        double dmgPerDay = getConfig().getDouble("siege.block_damage_per_day", 0.3);
        int damage = Math.max(1, (int) Math.round(dmgBase + day * dmgPerDay));

        var key = zombie.zombieMarkKey();

        for (var ent : center.getWorld().getNearbyEntities(center, r, r, r)) {
            if (!(ent instanceof org.bukkit.entity.Zombie z))
                continue;
            var mark = z.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.BYTE);
            if (mark == null || mark != (byte) 1)
                continue;
            siege.tickZombie(z, damage);
        }
    }

    // ===== getters =====

    public BaseManager base() {
        return base;
    }

    public BaseResourceManager baseResources() {
        return baseResources;
    }

    public EconomyManager econ() {
        return econ;
    }

    public ProgressManager progress() {
        return progress;
    }

    public KitManager kit() {
        return kit;
    }

    public KitMenu kitMenu() {
        return kitMenu;
    }

    public BlockHealthManager blocks() {
        return blockHealth;
    }

    public SiegeManager siege() {
        return siege;
    }

    public ZombieWaveManager zombie() {
        return zombie;
    }

    public PhaseManager phase() {
        return phase;
    }

    public DeathSpectatorManager deathSpectator() {
        return deathSpectator;
    }

    public RepairGUI repairGui() {
        return repairGui;
    }

    public BaseGUI baseGui() {
        return baseGui;
    }

    public BaseUpgradeGUI baseUpgradeGui() {
        return baseUpgradeGui;
    }

    public WallUpgradeGUI wallUpgradeGui() {
        return wallUpgradeGui;
    }

    public ShopGUI shopGui() {
        return shopGui;
    }
}
