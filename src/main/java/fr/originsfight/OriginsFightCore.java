package fr.originsfight;


import fr.originsfight.listeners.EnderPearlListener;
import fr.originsfight.listeners.FoodAppleListener;
import fr.originsfight.rtp.RTPCommand;
import fr.originsfight.rtp.RTPListener;
import org.bukkit.plugin.java.JavaPlugin;

public class OriginsFightCore extends JavaPlugin {
    private static OriginsFightCore instance;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("OriginsFightCore est activé !");
        registerCommands();
        registerListeners();
        saveDefaultConfig();
    }
    @Override
    public void onDisable() {
        getLogger().info("OriginsFightCore est désactivé.");
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