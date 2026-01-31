package me.korgan.deadcycle;

import me.korgan.deadcycle.base.BaseManager;
import me.korgan.deadcycle.base.BaseScoreboard;
import me.korgan.deadcycle.econ.EconomyManager;
import me.korgan.deadcycle.kit.KitMenu;
import me.korgan.deadcycle.kit.KitManager;
import me.korgan.deadcycle.mobs.MobSpawnController;
import me.korgan.deadcycle.phase.PhaseManager;
import me.korgan.deadcycle.player.ActionBarHUD;
import me.korgan.deadcycle.player.PlayerDataStore;
import me.korgan.deadcycle.player.ProgressManager;
import me.korgan.deadcycle.regen.RegenMiningListener;
import me.korgan.deadcycle.shop.ShopGUI;
import me.korgan.deadcycle.siege.BlockHealthManager;
import me.korgan.deadcycle.siege.RepairCommand;
import me.korgan.deadcycle.siege.RepairListener;
import me.korgan.deadcycle.siege.SiegeManager;
import me.korgan.deadcycle.system.GameRulesController;
import me.korgan.deadcycle.system.PlayerRulesListener;
import me.korgan.deadcycle.zombies.ZombieWaveManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class DeadCyclePlugin extends JavaPlugin {

    private PhaseManager phaseManager;
    private EconomyManager economy;
    private KitManager kits;
    private ZombieWaveManager zombies;
    private KitMenu kitMenu;
    private ShopGUI shop;

    private BaseManager base;
    private BaseScoreboard hud;

    // v0.4 siege
    private BlockHealthManager blockHealth;
    private SiegeManager siege;

    // v0.4.1 прогресс + экшнбар
    private PlayerDataStore playerData;
    private ProgressManager progress;
    private ActionBarHUD actionBarHUD;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.base = new BaseManager(this);
        this.economy = new EconomyManager(this);
        this.kits = new KitManager(this);
        this.zombies = new ZombieWaveManager(this);

        this.blockHealth = new BlockHealthManager(this);
        this.siege = new SiegeManager(this, blockHealth);

        this.playerData = new PlayerDataStore(this);
        this.progress = new ProgressManager(this, playerData);

        this.phaseManager = new PhaseManager(this, siege);
        this.kitMenu = new KitMenu(this);
        this.shop = new ShopGUI(this);
        this.hud = new BaseScoreboard(this);

        // listeners
        Bukkit.getPluginManager().registerEvents(economy, this);
        Bukkit.getPluginManager().registerEvents(kits, this);
        Bukkit.getPluginManager().registerEvents(kitMenu, this);
        Bukkit.getPluginManager().registerEvents(shop, this);

        Bukkit.getPluginManager().registerEvents(new MobSpawnController(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerRulesListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GameRulesController(this), this);

        Bukkit.getPluginManager().registerEvents(new RepairListener(this, blockHealth), this);
        getCommand("repair").setExecutor(new RepairCommand(this, blockHealth));

        // v0.4.1: копание “реген” + опыт майнера
        Bukkit.getPluginManager().registerEvents(new RegenMiningListener(this, progress), this);

        // HUD
        hud.start();

        getLogger().info("DeadCycle enabled v0.4.1");
    }

    @Override
    public void onDisable() {
        if (phaseManager != null) phaseManager.stop();
        if (economy != null) economy.save();
        if (hud != null) hud.stop();
        if (siege != null) siege.stop();

        if (playerData != null) playerData.save();

        getLogger().info("DeadCycle disabled.");
    }

    public PhaseManager phase() { return phaseManager; }
    public EconomyManager econ() { return economy; }
    public KitManager kit() { return kits; }
    public ZombieWaveManager zombie() { return zombies; }
    public KitMenu kitMenu() { return kitMenu; }
    public ShopGUI shop() { return shop; }
    public BaseManager base() { return base; }

    public SiegeManager siege() { return siege; }
    public BlockHealthManager blockHealth() { return blockHealth; }

    public ProgressManager progress() { return progress; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("shop")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
            shop.open(p);
            return true;
        }
        if (name.equals("kit")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
            kitMenu.open(p);
            return true;
        }
        if (name.equals("money")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
            long bal = economy.getMoney(p.getUniqueId());
            p.sendMessage(ChatColor.GOLD + "Money: " + ChatColor.YELLOW + bal);
            return true;
        }

        if (!name.equals("dc")) return false;

        if (args.length == 0) {
        sender.sendMessage(ChatColor.YELLOW + "DeadCycle admin: /dc start|stop|setphase <day|night>|reload|setbase <radius>|siege <on|off>");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "start" -> {
                if (!sender.hasPermission("deadcycle.admin")) return noPerm(sender);
                phaseManager.start();
                sender.sendMessage(ChatColor.GREEN + "DeadCycle started.");
            }
            case "stop" -> {
                if (!sender.hasPermission("deadcycle.admin")) return noPerm(sender);
                phaseManager.stop();
                sender.sendMessage(ChatColor.RED + "DeadCycle stopped.");
            }
            case "reload" -> {
                if (!sender.hasPermission("deadcycle.admin")) return noPerm(sender);
                reloadConfig();
                base.reload();
                blockHealth.reload();
                sender.sendMessage(ChatColor.GREEN + "Config reloaded.");
            }
            case "setphase" -> {
                if (!sender.hasPermission("deadcycle.admin")) return noPerm(sender);
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /dc setphase <day|night>"); return true; }
                phaseManager.forcePhase(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Phase forced: " + args[1]);
            }
            case "setbase" -> {
                if (!sender.hasPermission("deadcycle.admin")) return noPerm(sender);
                if (!(sender instanceof Player p)) { sender.sendMessage("Only players."); return true; }
                int radius = 30;
                if (args.length >= 2) {
                    try { radius = Integer.parseInt(args[1]); } catch (Exception ignored) {}
                }
                base.setBase(p.getLocation(), radius);
                sender.sendMessage(ChatColor.AQUA + "Base set. Radius=" + radius);
            }
            case "siege" -> {
                if (!sender.hasPermission("deadcycle.admin")) return noPerm(sender);
                if (args.length < 2) { sender.sendMessage("Usage: /dc siege <on|off>"); return true; }
                boolean on = args[1].equalsIgnoreCase("on");
                getConfig().set("siege.enabled", on);
                saveConfig();
                sender.sendMessage(ChatColor.AQUA + "Siege: " + (on ? "ON" : "OFF"));
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }

        return true;
    }

    private boolean noPerm(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "No permission.");
        return true;
    }
}
