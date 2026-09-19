package fr.redconflict.succes;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * Définition d'un succès, telle que lue dans {@code succes/succes.yml}.
 *
 * <p>Immuable : le catalogue est construit une fois à l'activation du module et
 * partagé entre le gestionnaire, la commande et l'envoi réseau. Ce qui varie
 * d'un joueur à l'autre — l'avancement, le déblocage, la réclamation — vit dans
 * {@link SuccesDatabase}, jamais ici.
 */
public final class Succes {

    /** Nombre de paliers de difficulté ; borne les valeurs de {@link #tier}. */
    public static final int TIERS = 4;

    /** Identifiant stable, clé en base et sur le fil. Ne jamais le renommer. */
    public final String id;
    public final String name;
    public final String description;
    /** Rubrique d'affichage : MINAGE, COMBAT, ECONOMIE... */
    public final String category;
    /** 0 facile · 1 moyen · 2 difficile · 3 extrême. */
    public final int tier;

    public final SuccesTrigger trigger;
    /** Matériau, métier... selon le déclencheur ; vide si inutile. */
    public final String target;
    /** Valeur à atteindre. Toujours ≥ 1. */
    public final int goal;

    public final long rewardMoney;
    public final List<ItemStack> rewardItems;
    /** Résumé lisible des récompenses, calculé une fois, envoyé au client. */
    public final String rewardText;

    public Succes(String id, String name, String description, String category, int tier,
                  SuccesTrigger trigger, String target, int goal,
                  long rewardMoney, List<ItemStack> rewardItems, String rewardText) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.tier = Math.max(0, Math.min(TIERS - 1, tier));
        this.trigger = trigger;
        this.target = target == null ? "" : target;
        this.goal = Math.max(1, goal);
        this.rewardMoney = Math.max(0L, rewardMoney);
        this.rewardItems = rewardItems == null
                ? Collections.<ItemStack>emptyList()
                : Collections.unmodifiableList(rewardItems);
        this.rewardText = rewardText == null ? "" : rewardText;
    }

    /** Vrai si l'événement décrit par {@code trigger}/{@code target} concerne ce succès. */
    public boolean matches(SuccesTrigger trigger, String target) {
        if (this.trigger != trigger) return false;
        if (!this.trigger.needsTarget()) return true;
        if (this.target.isEmpty()) return true;
        return this.target.equalsIgnoreCase(target);
    }

    /** Couleur du palier, alignée sur les raretés du client. */
    public String tierColor() {
        switch (tier) {
            case 0:  return "§a";
            case 1:  return "§e";
            case 2:  return "§6";
            default: return "§c";
        }
    }

    public String tierName() {
        switch (tier) {
            case 0:  return "Facile";
            case 1:  return "Moyen";
            case 2:  return "Difficile";
            default: return "Extrême";
        }
    }

    @Override
    public String toString() {
        return "Succes{" + id + " " + trigger + (target.isEmpty() ? "" : ":" + target) + " x" + goal + "}";
    }
}
