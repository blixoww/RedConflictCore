package fr.originsfight;

import fr.originsfight.rtp.RTPCommand;
import fr.originsfight.rtp.RTPListener;
import org.bukkit.plugin.java.JavaPlugin;
import fr.originsfight.listeners.EnderPearlListener;
import fr.originsfight.listeners.FoodAppleListener;

public class OriginsFightCore extends JavaPlugin {
    private static OriginsFightCore instance;

    @Override
    public void onEnable() {
        instance = this;
        registerCommands();
        registerListeners();
        saveDefaultConfig();
    }
    @Override
    public void onDisable() {
        // Code de désactivation si nécessaire
    }
    private void registerCommands() {
        //commands
        getCommand("rtp").setExecutor(new RTPCommand());
    }
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new EnderPearlListener(), this);
        getServer().getPluginManager().registerEvents(new FoodAppleListener(), this);
        getServer().getPluginManager().registerEvents(new RTPListener(), this);
    }
    public static OriginsFightCore getInstance() {
        return instance;
    }
}