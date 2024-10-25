package fr.originsfight;

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
        getServer().getPluginManager().registerEvents(new EnderPearlListener(), this);
        getServer().getPluginManager().registerEvents(new FoodAppleListener(), this);
    }

    public static OriginsFightCore getInstance() {
        return instance;
    }
}