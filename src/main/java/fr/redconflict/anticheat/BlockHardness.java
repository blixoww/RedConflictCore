package fr.redconflict.anticheat;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Le temps que la 1.8 impose pour casser un bloc, recalculé côté serveur.
 *
 * <p><b>Pourquoi cette table existe.</b> Bukkit n'expose ni la dureté d'un bloc
 * ni l'efficacité d'un outil : ce sont des champs NMS. Les lire par réflexion
 * marcherait sur cette version et casserait à la première autre. On recopie donc
 * les valeurs du jeu, une fois, en clair — plus long à écrire qu'une cascade de
 * {@code getDeclaredField}, et infiniment plus lisible.
 *
 * <p><b>La règle qui gouverne tout ce fichier : dans le doute, on favorise le
 * joueur.</b> Un bloc dont la dureté est inconnue renvoie {@code -1} et le
 * contrôle passe son chemin ; un outil qu'on ne sait pas classer vaut la vitesse
 * de base. Un anti-triche qui devine une dureté trop haute accuse des innocents,
 * et c'est le seul type d'erreur qui coûte des joueurs.
 *
 * <p>La formule reproduite est celle de {@code EntityHuman.getBreakSpeed} et de
 * {@code Block.getPlayerRelativeBlockHardness} :
 *
 * <pre>
 *   vitesse = efficacite de l'outil sur ce bloc
 *   si vitesse &gt; 1 et Efficacite N : vitesse += N*N + 1
 *   Celerite N : vitesse *= 1 + 0,2*N      Fatigue N : vitesse *= 0,3^N
 *   progres par tick = vitesse / durete / (outil adapte ? 30 : 100)
 *   ticks = plafond(1 / progres)
 * </pre>
 */
public final class BlockHardness {

    /** Dureté de chaque bloc, par Material (valeurs 1.8). */
    private static final Map<Material, Float> HARDNESS = new HashMap<Material, Float>();

    /** Blocs qui se minent à la pioche (matière « roche » ou « métal »). */
    private static final Set<Material> PICKAXE = new HashSet<Material>();
    /** Blocs qui se minent à la pelle (terre, sable, neige...). */
    private static final Set<Material> SHOVEL = new HashSet<Material>();
    /** Blocs qui se minent à la hache (bois et dérivés). */
    private static final Set<Material> AXE = new HashSet<Material>();

    /** Niveau de pioche exigé pour récolter : 1 pierre, 2 fer, 3 diamant. */
    private static final Map<Material, Integer> HARVEST_LEVEL = new HashMap<Material, Integer>();

    /** Outil universel du serveur : vitesse fixe, sur tous les blocs. */
    private static final String MULTI_TOOL = "MULTI_TOOL";
    private static final float MULTI_TOOL_SPEED = 20.0f;

    /** Plage des identifiants d'objets custom du serveur. */
    private static final int FIRST_CUSTOM_ID = 432;
    private static final int LAST_CUSTOM_ID = 481;

    private BlockHardness() { }

    // ── La table ───────────────────────────────────────────────────────────────

    static {
        // Roche et minerais
        hard("STONE", 1.5f, PICKAXE, 0);
        hard("COBBLESTONE", 2.0f, PICKAXE, 0);
        hard("MOSSY_COBBLESTONE", 2.0f, PICKAXE, 0);
        hard("SMOOTH_BRICK", 1.5f, PICKAXE, 0);
        hard("BRICK", 2.0f, PICKAXE, 0);
        hard("SANDSTONE", 0.8f, PICKAXE, 0);
        hard("RED_SANDSTONE", 0.8f, PICKAXE, 0);
        hard("NETHERRACK", 0.4f, PICKAXE, 0);
        hard("NETHER_BRICK", 2.0f, PICKAXE, 0);
        hard("ENDER_STONE", 3.0f, PICKAXE, 0);
        hard("COAL_ORE", 3.0f, PICKAXE, 0);
        hard("QUARTZ_ORE", 3.0f, PICKAXE, 0);
        hard("IRON_ORE", 3.0f, PICKAXE, 1);
        hard("LAPIS_ORE", 3.0f, PICKAXE, 1);
        hard("LAPIS_BLOCK", 3.0f, PICKAXE, 1);
        hard("GOLD_ORE", 3.0f, PICKAXE, 2);
        hard("DIAMOND_ORE", 3.0f, PICKAXE, 2);
        hard("EMERALD_ORE", 3.0f, PICKAXE, 2);
        hard("REDSTONE_ORE", 3.0f, PICKAXE, 2);
        hard("GLOWING_REDSTONE_ORE", 3.0f, PICKAXE, 2);
        hard("OBSIDIAN", 50.0f, PICKAXE, 3);
        hard("IRON_BLOCK", 5.0f, PICKAXE, 1);
        hard("GOLD_BLOCK", 3.0f, PICKAXE, 2);
        hard("DIAMOND_BLOCK", 5.0f, PICKAXE, 2);
        hard("EMERALD_BLOCK", 5.0f, PICKAXE, 2);
        hard("REDSTONE_BLOCK", 5.0f, PICKAXE, 2);
        hard("COAL_BLOCK", 5.0f, PICKAXE, 0);
        hard("QUARTZ_BLOCK", 0.8f, PICKAXE, 0);
        hard("PRISMARINE", 1.5f, PICKAXE, 0);
        hard("SEA_LANTERN", 0.3f, null, -1);
        hard("HARD_CLAY", 1.25f, PICKAXE, 0);
        hard("STAINED_CLAY", 1.25f, PICKAXE, 0);
        hard("STEP", 2.0f, PICKAXE, 0);
        hard("DOUBLE_STEP", 2.0f, PICKAXE, 0);
        hard("STONE_SLAB2", 2.0f, PICKAXE, 0);
        hard("COBBLESTONE_STAIRS", 2.0f, PICKAXE, 0);
        hard("BRICK_STAIRS", 2.0f, PICKAXE, 0);
        hard("SMOOTH_STAIRS", 2.0f, PICKAXE, 0);
        hard("SANDSTONE_STAIRS", 0.8f, PICKAXE, 0);
        hard("NETHER_BRICK_STAIRS", 2.0f, PICKAXE, 0);
        hard("QUARTZ_STAIRS", 0.8f, PICKAXE, 0);
        hard("COBBLE_WALL", 2.0f, PICKAXE, 0);
        hard("IRON_FENCE", 5.0f, PICKAXE, 0);
        hard("FURNACE", 3.5f, PICKAXE, 0);
        hard("BURNING_FURNACE", 3.5f, PICKAXE, 0);
        hard("DISPENSER", 3.5f, PICKAXE, 0);
        hard("DROPPER", 3.5f, PICKAXE, 0);
        hard("HOPPER", 3.0f, PICKAXE, 0);
        hard("ANVIL", 5.0f, PICKAXE, 0);
        hard("ENCHANTMENT_TABLE", 5.0f, PICKAXE, 0);
        hard("ENDER_CHEST", 22.5f, PICKAXE, 0);
        hard("BEACON", 3.0f, null, -1);
        hard("MOB_SPAWNER", 5.0f, PICKAXE, 0);
        hard("CAULDRON", 2.0f, PICKAXE, 0);
        hard("BREWING_STAND", 0.5f, PICKAXE, 0);
        hard("IRON_DOOR", 5.0f, PICKAXE, 0);
        hard("IRON_DOOR_BLOCK", 5.0f, PICKAXE, 0);
        hard("IRON_TRAPDOOR", 5.0f, PICKAXE, 0);
        hard("IRON_PLATE", 0.5f, PICKAXE, 0);
        hard("GOLD_PLATE", 0.5f, PICKAXE, 0);
        hard("STONE_PLATE", 0.5f, PICKAXE, 0);
        hard("STONE_BUTTON", 0.5f, PICKAXE, 0);
        hard("PISTON_BASE", 0.5f, null, -1);
        hard("PISTON_STICKY_BASE", 0.5f, null, -1);
        hard("PISTON_EXTENSION", 0.5f, null, -1);
        hard("RAILS", 0.7f, PICKAXE, 0);
        hard("POWERED_RAIL", 0.7f, PICKAXE, 0);
        hard("DETECTOR_RAIL", 0.7f, PICKAXE, 0);
        hard("ACTIVATOR_RAIL", 0.7f, PICKAXE, 0);
        hard("ICE", 0.5f, PICKAXE, 0);
        hard("PACKED_ICE", 0.5f, PICKAXE, 0);
        hard("GLOWSTONE", 0.3f, null, -1);
        hard("GLASS", 0.3f, null, -1);
        hard("THIN_GLASS", 0.3f, null, -1);
        hard("STAINED_GLASS", 0.3f, null, -1);
        hard("STAINED_GLASS_PANE", 0.3f, null, -1);

        // Terre et matières meubles
        hard("DIRT", 0.5f, SHOVEL, -1);
        hard("GRASS", 0.6f, SHOVEL, -1);
        hard("MYCEL", 0.6f, SHOVEL, -1);
        hard("SOIL", 0.6f, SHOVEL, -1);
        hard("SAND", 0.5f, SHOVEL, -1);
        hard("GRAVEL", 0.6f, SHOVEL, -1);
        hard("CLAY", 0.6f, SHOVEL, -1);
        hard("SOUL_SAND", 0.5f, SHOVEL, -1);
        hard("SNOW", 0.1f, SHOVEL, -1);
        hard("SNOW_BLOCK", 0.2f, SHOVEL, -1);
        hard("GRASS_PATH", 0.6f, SHOVEL, -1);

        // Bois et dérivés
        hard("LOG", 2.0f, AXE, -1);
        hard("LOG_2", 2.0f, AXE, -1);
        hard("WOOD", 2.0f, AXE, -1);
        hard("WOOD_STEP", 2.0f, AXE, -1);
        hard("WOOD_DOUBLE_STEP", 2.0f, AXE, -1);
        hard("WOOD_STAIRS", 2.0f, AXE, -1);
        hard("BIRCH_WOOD_STAIRS", 2.0f, AXE, -1);
        hard("SPRUCE_WOOD_STAIRS", 2.0f, AXE, -1);
        hard("JUNGLE_WOOD_STAIRS", 2.0f, AXE, -1);
        hard("ACACIA_STAIRS", 2.0f, AXE, -1);
        hard("DARK_OAK_STAIRS", 2.0f, AXE, -1);
        hard("FENCE", 2.0f, AXE, -1);
        hard("FENCE_GATE", 2.0f, AXE, -1);
        hard("SPRUCE_FENCE", 2.0f, AXE, -1);
        hard("BIRCH_FENCE", 2.0f, AXE, -1);
        hard("JUNGLE_FENCE", 2.0f, AXE, -1);
        hard("DARK_OAK_FENCE", 2.0f, AXE, -1);
        hard("ACACIA_FENCE", 2.0f, AXE, -1);
        hard("WORKBENCH", 2.5f, AXE, -1);
        hard("CHEST", 2.5f, AXE, -1);
        hard("TRAPPED_CHEST", 2.5f, AXE, -1);
        hard("BOOKSHELF", 1.5f, AXE, -1);
        hard("JUKEBOX", 2.0f, AXE, -1);
        hard("NOTE_BLOCK", 0.8f, AXE, -1);
        hard("WOODEN_DOOR", 3.0f, AXE, -1);
        hard("WOOD_DOOR", 3.0f, AXE, -1);
        hard("TRAP_DOOR", 3.0f, AXE, -1);
        hard("SIGN_POST", 1.0f, AXE, -1);
        hard("WALL_SIGN", 1.0f, AXE, -1);
        hard("LADDER", 0.4f, AXE, -1);
        hard("PUMPKIN", 1.0f, AXE, -1);
        hard("JACK_O_LANTERN", 1.0f, AXE, -1);
        hard("MELON_BLOCK", 0.2f, AXE, -1);
        hard("HUGE_MUSHROOM_1", 0.2f, AXE, -1);
        hard("HUGE_MUSHROOM_2", 0.2f, AXE, -1);
        hard("BANNER", 1.0f, AXE, -1);
        hard("WALL_BANNER", 1.0f, AXE, -1);
        hard("STANDING_BANNER", 1.0f, AXE, -1);

        // Laine, feuillages et divers sans outil dédié
        hard("WOOL", 0.8f, null, -1);
        hard("CARPET", 0.1f, null, -1);
        hard("LEAVES", 0.2f, null, -1);
        hard("LEAVES_2", 0.2f, null, -1);
        hard("WEB", 4.0f, null, -1);
        hard("SPONGE", 0.6f, null, -1);
        hard("HAY_BLOCK", 0.5f, null, -1);
        hard("TNT", 0.0f, null, -1);
        hard("TORCH", 0.0f, null, -1);
        hard("REDSTONE_TORCH_ON", 0.0f, null, -1);
        hard("REDSTONE_TORCH_OFF", 0.0f, null, -1);
        hard("REDSTONE_WIRE", 0.0f, null, -1);
        hard("LONG_GRASS", 0.0f, null, -1);
        hard("DEAD_BUSH", 0.0f, null, -1);
        hard("YELLOW_FLOWER", 0.0f, null, -1);
        hard("RED_ROSE", 0.0f, null, -1);
        hard("DOUBLE_PLANT", 0.0f, null, -1);
        hard("SAPLING", 0.0f, null, -1);
        hard("CROPS", 0.0f, null, -1);
        hard("CARROT", 0.0f, null, -1);
        hard("POTATO", 0.0f, null, -1);
        hard("SUGAR_CANE_BLOCK", 0.0f, null, -1);
        hard("NETHER_WARTS", 0.0f, null, -1);
        hard("VINE", 0.2f, null, -1);
        hard("WATER_LILY", 0.0f, null, -1);
        hard("CACTUS", 0.4f, null, -1);
        hard("BED_BLOCK", 0.2f, null, -1);
        hard("CAKE_BLOCK", 0.5f, null, -1);
        hard("DRAGON_EGG", 3.0f, null, -1);
        hard("SLIME_BLOCK", 0.0f, null, -1);
        hard("BROWN_MUSHROOM", 0.0f, null, -1);
        hard("RED_MUSHROOM", 0.0f, null, -1);
        hard("SKULL", 1.0f, null, -1);
        hard("FLOWER_POT", 0.0f, null, -1);
        hard("LEVER", 0.5f, null, -1);
        hard("WOOD_BUTTON", 0.5f, null, -1);
        hard("WOOD_PLATE", 0.5f, null, -1);
        hard("DIODE_BLOCK_ON", 0.0f, null, -1);
        hard("DIODE_BLOCK_OFF", 0.0f, null, -1);
        hard("REDSTONE_COMPARATOR_ON", 0.0f, null, -1);
        hard("REDSTONE_COMPARATOR_OFF", 0.0f, null, -1);
    }

    /**
     * Une ligne de la table.
     *
     * <p>Le bloc est désigné par son NOM et non par la constante {@code Material}
     * pour que ce fichier compile et tourne quelle que soit la version de l'API :
     * un nom absent est simplement ignoré, sans erreur au chargement de classe.
     *
     * @param tool  famille d'outils qui accélère ce bloc, {@code null} si aucune
     * @param level niveau de pioche exigé pour récolter, {@code -1} si le bloc se
     *              récolte à mains nues
     */
    private static void hard(String name, float hardness, Set<Material> tool, int level) {
        Material material = Material.getMaterial(name);
        if (material == null) {
            return;
        }
        HARDNESS.put(material, Float.valueOf(hardness));
        if (tool != null) {
            tool.add(material);
        }
        if (level >= 0) {
            HARVEST_LEVEL.put(material, Integer.valueOf(level));
        }
    }

    // ── Interrogation ──────────────────────────────────────────────────────────

    /** Dureté du bloc, ou {@code -1} si la table ne le connaît pas. */
    public static float hardness(Material material) {
        Float value = HARDNESS.get(material);
        return value == null ? -1f : value.floatValue();
    }

    /**
     * Efficacité de l'objet tenu sur ce bloc — le {@code getStrVsBlock} du jeu.
     *
     * <p>Un outil n'accélère que les blocs de sa famille : la pioche ne fait rien
     * sur la terre, la pelle rien sur la pierre. Hors famille, la vitesse vaut 1,
     * comme à mains nues.
     */
    public static float toolSpeed(ItemStack held, Material block) {
        if (held == null || held.getType() == Material.AIR) {
            return 1.0f;
        }
        String name = held.getType().name();
        if (MULTI_TOOL.equals(name)) {
            // ItemMultiTool : vitesse fixe sur TOUT, y compris ce qu'aucun outil
            // vanilla n'accelere. Pas de famille, pas d'exception a chercher.
            return MULTI_TOOL_SPEED;
        }
        if ("SHEARS".equals(name)) {
            if (block == Material.WEB || isLeaves(block)) {
                return 15.0f;
            }
            return block == Material.WOOL ? 5.0f : 1.0f;
        }
        if (name.endsWith("_SWORD")) {
            // L'épée casse la toile à la vitesse des cisailles et tout le reste
            // une fois et demie plus vite : de quoi « casser trop vite » aux yeux
            // d'un contrôle naïf, sans la moindre triche.
            return block == Material.WEB ? 15.0f : 1.5f;
        }
        Set<Material> family = familyOf(name);
        if (family == null || !family.contains(block)) {
            return 1.0f;
        }
        return tierSpeed(name);
    }

    /**
     * Le joueur récolte-t-il ce bloc, ou le casse-t-il « à vide » ?
     *
     * <p>Ce n'est pas une question de butin ici mais de VITESSE : le jeu divise la
     * progression par 30 quand l'outil convient, par 100 sinon. Se tromper de ce
     * côté change le temps attendu d'un facteur trois — d'où le parti pris de
     * répondre {@code true} au moindre doute.
     */
    public static boolean canHarvest(ItemStack held, Material block) {
        Integer required = HARVEST_LEVEL.get(block);
        if (required == null) {
            return true; // récoltable à mains nues (terre, bois, laine...)
        }
        if (held == null || held.getType() == Material.AIR) {
            return false;
        }
        String name = held.getType().name();
        if (MULTI_TOOL.equals(name)) {
            return true; // canDestroySpecialBlock renvoie vrai sur tout
        }
        return isPickaxe(name) && tierLevel(name) >= required.intValue();
    }

    /**
     * Cet objet fait-il partie de ce que la table sait juger ?
     *
     * <p><b>La question n'est pas rhétorique : c'est le garde-fou du contrôle de
     * vitesse de minage.</b> Un objet non reconnu vaut la vitesse de base, 1 —
     * donc une durée attendue énorme, donc un signalement à chaque bloc. C'est
     * exactement ce qui s'est produit quand les paliers du serveur (acier,
     * émeraude, rubis, cobalt) manquaient : une pioche en cobalt était mesurée
     * comme une main nue et chaque bloc cassé remontait au staff.
     *
     * <p>Un objet custom que la table ne connaît pas — le prochain qui sera
     * ajouté — ne doit donc rien déclencher du tout. Les objets vanilla, eux,
     * sont tous couverts.
     */
    public static boolean recognizes(ItemStack held) {
        if (held == null || held.getType() == null || held.getType() == Material.AIR) {
            return true; // mains nues : vitesse 1, et c'est la bonne
        }
        String name = held.getType().name();
        if (MULTI_TOOL.equals(name) || "SHEARS".equals(name) || name.endsWith("_SWORD")) {
            return true;
        }
        if (familyOf(name) != null) {
            return tierSpeed(name) > 1.0f; // palier connu ?
        }
        // Ni outil ni arme connus : sans risque tant que ce n'est pas un objet
        // custom, dont on ignorerait justement la vitesse.
        int id = held.getTypeId();
        return id < FIRST_CUSTOM_ID || id > LAST_CUSTOM_ID;
    }

    // ── Familles et paliers d'outils ───────────────────────────────────────────

    private static Set<Material> familyOf(String toolName) {
        if (isPickaxe(toolName)) {
            return PICKAXE;
        }
        // Les pelles vanilla s'appellent _SPADE, les pelles du serveur _SHOVEL.
        if (toolName.endsWith("_SPADE") || toolName.endsWith("_SHOVEL")) {
            return SHOVEL;
        }
        if (toolName.endsWith("_AXE")) {
            return AXE;
        }
        return null;
    }

    /**
     * Pioche, marteau compris.
     *
     * <p>{@code ItemCobaltHammer} étend {@code ItemTool} avec la table de blocs
     * d'une pioche : pour la durée de minage, c'en est une.
     */
    private static boolean isPickaxe(String toolName) {
        return toolName.endsWith("_PICKAXE") || toolName.endsWith("_HAMMER");
    }

    /**
     * Vitesse du palier.
     *
     * <p>Les quatre derniers sont ceux du serveur ({@code Item.EnumToolMaterial}
     * du fork) : ils doivent rester la copie exacte de cette table, sinon le
     * contrôle mesure un outil qui n'existe pas.
     */
    private static float tierSpeed(String toolName) {
        String tier = tierOf(toolName);
        if ("WOOD".equals(tier)) return 2.0f;
        if ("STONE".equals(tier)) return 4.0f;
        if ("IRON".equals(tier)) return 6.0f;
        if ("DIAMOND".equals(tier)) return 8.0f;
        if ("GOLD".equals(tier)) return 12.0f;
        if ("STEEL".equals(tier)) return 9.0f;
        if ("EMERALD".equals(tier)) return 10.0f;
        if ("RUBY".equals(tier)) return 12.0f;
        if ("COBALT".equals(tier)) return 14.0f;
        return 1.0f;
    }

    /** Niveau de récolte du palier. Au-delà du diamant, ceux du serveur. */
    private static int tierLevel(String toolName) {
        String tier = tierOf(toolName);
        if ("STONE".equals(tier)) return 1;
        if ("IRON".equals(tier)) return 2;
        if ("DIAMOND".equals(tier)) return 3;
        if ("STEEL".equals(tier)) return 4;
        if ("EMERALD".equals(tier) || "RUBY".equals(tier)) return 5;
        if ("COBALT".equals(tier)) return 6;
        return 0;
    }

    private static String tierOf(String toolName) {
        int cut = toolName.indexOf('_');
        return cut < 0 ? toolName : toolName.substring(0, cut);
    }

    private static boolean isLeaves(Material material) {
        return material != null && material.name().toUpperCase(Locale.ROOT).startsWith("LEAVES");
    }
}
