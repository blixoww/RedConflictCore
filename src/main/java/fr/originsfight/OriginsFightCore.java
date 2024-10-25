package fr.originsfight;

import fr.originsfight.rtp.RTPCommand;
import fr.originsfight.rtp.RTPListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import fr.originsfight.listeners.EnderPearlListener;
import fr.originsfight.listeners.FoodAppleListener;

public class OriginsFightCore extends JavaPlugin {

    private static OriginsFightCore instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        registerListeners();
    }

    @Override
    public void onDisable() {
        // Code de désactivation si nécessaire
    }

    private void registerListeners() {
        //events
        PluginManager pluginManager = Bukkit.getPluginManager();
        pluginManager.registerEvents(new EnderPearlListener(), this);
        pluginManager.registerEvents(new FoodAppleListener(), this);
        pluginManager.registerEvents(new RTPListener(), this);

        //commands
        getCommand("rtp").setExecutor(new RTPCommand());
    }

    public static OriginsFightCore getInstance() {
        return instance;
    }
}