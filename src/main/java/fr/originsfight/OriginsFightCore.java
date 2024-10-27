package fr.originsfight;


import fr.originsfight.combatlog.CombatLogCommand;
import fr.originsfight.combatlog.CombatLogListener;
import fr.originsfight.listeners.DisabledCommands;
import fr.originsfight.listeners.EnderPearlListener;
import fr.originsfight.listeners.FoodAppleListener;
import fr.originsfight.listeners.VoidListener;
import fr.originsfight.repair.RepairCommand;
import fr.originsfight.rtp.RTPCommand;
import fr.originsfight.rtp.RTPListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class OriginsFightCore extends JavaPlugin {
    private static OriginsFightCore instance;
    private List<String> disabledInCombatCommands;
    private List<String> alwaysDisabledCommands;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("OriginsFightCore est activé !");
        registerCommands();
        registerListeners();
        saveDefaultConfig();
        this.alwaysDisabledCommands = getConfig().getStringList("commands.always-disabled");
        this.disabledInCombatCommands = getConfig().getStringList("commands.disabled-in-combat");
    }
    @Override
    public void onDisable() {
        getLogger().info("OriginsFightCore est désactivé.");
    }
    private void registerCommands() {
        //commands
        getCommand("rtp").setExecutor(new RTPCommand());
        getCommand("ct").setExecutor(new CombatLogCommand());
        getCommand("repairall").setExecutor(new RepairCommand());
    }
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new EnderPearlListener(), this);
        getServer().getPluginManager().registerEvents(new FoodAppleListener(), this);
        getServer().getPluginManager().registerEvents(new RTPListener(), this);
        getServer().getPluginManager().registerEvents(new CombatLogListener(), this);
        getServer().getPluginManager().registerEvents(new VoidListener(), this);
        getServer().getPluginManager().registerEvents(new DisabledCommands(), this);
    }

    public List<String> getDisabledInCombatCommands() {
        return disabledInCombatCommands;
    }

    public List<String> getAlwaysDisabledCommands() {
        return alwaysDisabledCommands;
    }
    public static OriginsFightCore getInstance() {
        return instance;
    }

}