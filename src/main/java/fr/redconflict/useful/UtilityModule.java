package fr.redconflict.useful;

import fr.redconflict.RedConflictCore;
import fr.redconflict.core.Module;
import fr.redconflict.core.command.CommandRegistrar;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Petites commandes de confort : /poubelle, /furnace, /vision, /commands,
 * /tpu, /guide, /baltop et /cobble. Chacune est autonome ; ce module ne fait
 * que les câbler (et indexer les recettes de cuisson pour /furnace).
 */
public class UtilityModule implements Module {

    private final RedConflictCore plugin;

    /** Recettes de cuisson vanilla indexées pour /furnace (entrée → résultat). */
    private final Map<Material, ItemStack> smeltableItems = new HashMap<>();

    public UtilityModule(RedConflictCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "Utility";
    }

    @Override
    public void enable() {
        loadRecipes();

        CommandRegistrar commands = new CommandRegistrar(plugin);
        commands.register("furnace", new FurnaceCommand(plugin, smeltableItems));
        commands.register("vision", new VisionCommand(plugin));
        commands.register("commands", new CommandsCommand(plugin));
        commands.register("tpu", new TpuCommand(plugin));
        commands.register("baltop", new BaltopCommand(plugin));
        commands.register("guide", new GuideCommand(plugin));

        // /poubelle et /cobble sont à la fois exécuteurs et listeners (même instance).
        PoubelleCommand poubelle = new PoubelleCommand(plugin);
        commands.register("poubelle", poubelle);
        plugin.getServer().getPluginManager().registerEvents(poubelle, plugin);

        CobbleCommand cobble = new CobbleCommand(plugin);
        commands.register("cobble", cobble);
        plugin.getServer().getPluginManager().registerEvents(cobble, plugin);
    }

    /** Indexe les recettes de cuisson vanilla pour /furnace. */
    private void loadRecipes() {
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
}
