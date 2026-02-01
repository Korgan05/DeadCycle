package me.korgan.deadcycle;

import me.korgan.deadcycle.base.*;
import me.korgan.deadcycle.econ.EconomyManager;
import me.korgan.deadcycle.kit.*;
import me.korgan.deadcycle.mobs.MobSpawnController;
import me.korgan.deadcycle.phase.PhaseManager;
import me.korgan.deadcycle.player.*;
import me.korgan.deadcycle.regen.RegenMiningListener;
import me.korgan.deadcycle.shop.*;
import me.korgan.deadcycle.siege.*;
import me.korgan.deadcycle.system.*;
import me.korgan.deadcycle.zombies.ZombieWaveManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import me.korgan.deadcycle.econ.MoneyCommand;

public final class DeadCyclePlugin extends JavaPlugin {

    // ===== base =====
    private BaseManager base;
    private BaseResourceManager baseResources;
    private BaseUpgradeManager upgrades;

    // ===== player =====
    private PlayerDataStore playerStore;
    private ProgressManager progress;
    private ActionBarHUD actionBar;

    // ===== economy / kits / shop =====
    private EconomyManager econ;
    private KitManager kit;
    private KitMenu kitMenu;
    private ShopGUI shopGui;

    // ===== siege / mobs / phase =====
    private BlockHealthManager blockHealth;
    private SiegeManager siege;
    private ZombieWaveManager zombie;
    private PhaseManager phase;

    // ===== GUI =====
    private RepairGUI repairGui;
    private BaseGUI baseGui;
    private BaseUpgradeGUI baseUpgradeGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // ===== core =====
        base = new BaseManager(this);
        baseResources = new BaseResourceManager(this);
        upgrades = new BaseUpgradeManager(this);

        // ===== player =====
        playerStore = new PlayerDataStore(this);
        progress = new ProgressManager(this, playerStore);
        actionBar = new ActionBarHUD(this, progress);

        // ===== economy / kits / shop =====
        econ = new EconomyManager(this);
        kit = new KitManager(this);
        kitMenu = new KitMenu(this);
        shopGui = new ShopGUI(this);

        // ===== siege / mobs / phase =====
        blockHealth = new BlockHealthManager(this);
        siege = new SiegeManager(this, blockHealth);
        zombie = new ZombieWaveManager(this);
        phase = new PhaseManager(this, siege);

        // ===== GUI instances (ВАЖНО: именно так, без лишних аргументов) =====
        repairGui = new RepairGUI(this, blockHealth);
        baseGui = new BaseGUI(this);
        baseUpgradeGui = new BaseUpgradeGUI(this);

        // ===== Events =====
        Bukkit.getPluginManager().registerEvents(new ResourceDepositListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BaseBuildProtectionListener(this), this);

        Bukkit.getPluginManager().registerEvents(econ, this);
        Bukkit.getPluginManager().registerEvents(kit, this);
        Bukkit.getPluginManager().registerEvents(kitMenu, this);
        Bukkit.getPluginManager().registerEvents(shopGui, this);

        Bukkit.getPluginManager().registerEvents(new MobSpawnController(this), this);
        Bukkit.getPluginManager().registerEvents(new GameRulesController(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerRulesListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RegenMiningListener(this), this);

        // GUI listeners
        Bukkit.getPluginManager().registerEvents(repairGui, this);
        Bukkit.getPluginManager().registerEvents(baseGui, this);
        Bukkit.getPluginManager().registerEvents(baseUpgradeGui, this);

        // ===== Commands =====
        if (getCommand("repair") != null)
            getCommand("repair").setExecutor(new RepairCommand(this, repairGui));

        if (getCommand("shop") != null)
            getCommand("shop").setExecutor(new ShopCommand(this));

        if (getCommand("kit") != null)
            getCommand("kit").setExecutor(new KitCommand(this));

        if (getCommand("money") != null)
            getCommand("money").setExecutor(new MoneyCommand(this));

        // ✅ /base
        if (getCommand("base") != null)
            getCommand("base").setExecutor(new BaseCommand(this));

        // ===== Timers =====
        actionBar.start();

        getLogger().info("DeadCycle v0.6 enabled.");
    }

    @Override
    public void onDisable() {
        try {
            if (econ != null) econ.save();
            if (baseResources != null) baseResources.save();
            if (upgrades != null) upgrades.save();
        } catch (Throwable ignored) {}
    }

    // ===== getters =====
    public BaseManager base() { return base; }
    public BaseResourceManager baseResources() { return baseResources; }
    public BaseUpgradeManager upgrades() { return upgrades; }

    public EconomyManager econ() { return econ; }
    public EconomyManager economy() { return econ; }

    public KitManager kit() { return kit; }
    public KitMenu kitMenu() { return kitMenu; }

    public ProgressManager progress() { return progress; }

    public ZombieWaveManager zombie() { return zombie; }
    public PhaseManager phase() { return phase; }

    public SiegeManager siege() { return siege; }
    public BlockHealthManager blocks() { return blockHealth; }

    public ShopGUI shopGui() { return shopGui; }

    // ✅ GUI getters
    public RepairGUI repairGui() { return repairGui; }
    public BaseGUI baseGui() { return baseGui; }
    public BaseUpgradeGUI baseUpgradeGui() { return baseUpgradeGui; }
}
