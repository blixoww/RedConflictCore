package fr.originsfight;


import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import fr.originsfight.combatlog.CombatLogCommand;
import fr.originsfight.combatlog.CombatLogListener;
import fr.originsfight.death.DeathMessages;
import fr.originsfight.listeners.DisabledCommands;
import fr.originsfight.listeners.EnderPearlListener;
import fr.originsfight.listeners.FoodAppleListener;
import fr.originsfight.listeners.VoidListener;
import fr.originsfight.repair.RepairCommand;
import fr.originsfight.rtp.RTPCommand;
import fr.originsfight.rtp.RTPListener;
import fr.originsfight.useful.FurnaceCommand;
import fr.originsfight.useful.PoubelleCommand;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class OriginsFightCore extends JavaPlugin {
    private static OriginsFightCore instance;
    private WorldGuardPlugin worldGuard;
    private List<String> disabledInCombatCommands;
    private List<String> alwaysDisabledCommands;
    private final Map<Material, ItemStack> smeltableItems = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("OriginsFightCore est activé !");
        this.worldGuard = (WorldGuardPlugin) getServer().getPluginManager().getPlugin("WorldGuard");
        registerCommands();
        registerListeners();
        saveDefaultConfig();
        loadRecipes();
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
        getCommand("poubelle").setExecutor(new PoubelleCommand());
        getCommand("furnace").setExecutor(new FurnaceCommand());
    }
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new EnderPearlListener(), this);
        getServer().getPluginManager().registerEvents(new FoodAppleListener(), this);
        getServer().getPluginManager().registerEvents(new RTPListener(), this);
        getServer().getPluginManager().registerEvents(new CombatLogListener(), this);
        getServer().getPluginManager().registerEvents(new VoidListener(), this);
        getServer().getPluginManager().registerEvents(new DeathMessages(), this);
        getServer().getPluginManager().registerEvents(new DisabledCommands(), this);
    }

    public void loadRecipes() {
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe recipe = it.next();
            if (recipe instanceof FurnaceRecipe) {
                FurnaceRecipe furnaceRecipe = (FurnaceRecipe) recipe;
                if (furnaceRecipe.getInput() != null && furnaceRecipe.getResult() != null) {
                    smeltableItems.put(furnaceRecipe.getInput().getType(), furnaceRecipe.getResult());
                }
            }
        }
    }

    public Map<Material, ItemStack> getSmeltableItems() {
        return smeltableItems;
    }

    public List<String> getDisabledInCombatCommands() {
        return disabledInCombatCommands;
    }

    public List<String> getAlwaysDisabledCommands() {
        return alwaysDisabledCommands;
    }

    public WorldGuardPlugin getWorldGuard() {
        return worldGuard;
    }
    public static OriginsFightCore getInstance() {
        return instance;
    }

}