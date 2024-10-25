package fr.originsfight;

import org.bukkit.plugin.java.JavaPlugin;
import fr.originsfight.listeners.EnderPearlListener;
import fr.originsfight.listeners.GoldenAppleListener;

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
        getServer().getPluginManager().registerEvents(new EnderPearlListener(this), this);
        getServer().getPluginManager().registerEvents(new GoldenAppleListener(this), this);
    }

    public static OriginsFightCore getInstance() {
        return instance;
    }
}