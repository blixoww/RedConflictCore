# Configuration du Shop Dynamique

## 📋 Vue d'ensemble

Le shop utilise un système de **prix dynamiques** qui évolue en fonction de l'offre et de la demande. Les prix sont définis dans un fichier de configuration YAML externe, permettant une modification facile sans recompilation.

## 🚀 Démarrage automatique

**Au premier lancement du serveur** :
1. Le fichier `shop_items.yml` est automatiquement créé dans le dossier du plugin
2. La base de données SQLite `shop.db` est créée avec tous les items configurés
3. Un premier snapshot de prix est enregistré pour initialiser l'historique
4. Le cycle de 24h démarre automatiquement

## ⏰ Système de temps (24 heures)

### Snapshots automatiques
- **Toutes les 5 minutes** : Un snapshot des prix actuels est enregistré
- Permet de voir l'évolution des prix dans le graphique du GUI

### Régression journalière
- **Toutes les 24 heures** (en temps jeu) : Les prix se rapprochent de 8% vers leurs valeurs de base
- Évite une dérive infinie des prix
- Les snapshots continuent pendant la régression

### Commandes de debug
```bash
/shopdebug tick all    # Simule 24h de régression (24 cycles)
/shopdebug info        # Affiche l'état du marché + temps avant prochaine régression
/shopdebug reset       # Supprime tout et recharge depuis shop_items.yml
```

## 📝 Format du fichier shop_items.yml

### Structure
```yaml
categories:
  nom_categorie:
    name: "Nom Affiché"
    icon: "minecraft:item_id"
    sort: 1
    items:
      - "Nom|minecraft_item:meta|baseBuy|baseSell|maxStack|floor|ceil"
```

### Explication des paramètres

| Paramètre | Description | Exemple |
|-----------|-------------|---------|
| **Nom** | Nom affiché dans le shop | `"Diamant"` |
| **minecraft_item** | ID Minecraft (+ meta optionnel) | `diamond` ou `wool:1` |
| **baseBuy** | Prix d'achat de base (centimes) | `10000` = 100€ |
| **baseSell** | Prix de vente de base (centimes) | `7500` = 75€ |
| **maxStack** | Taille de stack maximum | `64` |
| **floor** | Prix plancher (min absolu) | `5000` = 50€ |
| **ceil** | Prix plafond (max absolu) | `50000` = 500€ |

### Exemple complet
```yaml
categories:
  minerais:
    name: "Minerais"
    icon: "minecraft:diamond"
    sort: 1
    items:
      - "Diamant|diamond|10000|7500|64|5000|50000"
      - "Émeraude|emerald|8000|6000|64|4000|40000"
      - "Lapis-Lazuli|dye:4|1200|800|64|300|10000"
```

## 🎮 Mouvements Boursiers (Live)

### Comment ça fonctionne
1. **Achat d'un joueur** → Prix monte de +0.5% par unité achetée
2. **Vente d'un joueur** → Prix baisse de -0.5% par unité vendue
3. **Top Achats/Ventes** : Mis à jour instantanément après chaque transaction
4. **Historique** : Affiché sous forme de graphique ASCII dans le GUI

### Flèches de tendance
- ▲ **Vert** : Prix en hausse (plus d'achats récents)
- ▼ **Rouge** : Prix en baisse (plus de ventes récentes)  
- ═ **Gris** : Prix stable

## 🗂️ Items Moddés

Les items moddés sont automatiquement reconnus et affichés avec leurs vraies textures :
- `ruby_ore`, `ruby`, `ruby_block`
- `cobalt_ore`, `cobalt_ingot`, `cobalt_block`
- `steel_ingot`, `steel_block`, `steel_chest`
- `cobalt_apple`, `green_pumpkin_pie`
- `obsidian_door`, `obsidian_slab`, etc.

## 🔧 Modification en jeu

Pour modifier les items/prix sans arrêter le serveur :
1. Éditer `shop_items.yml`
2. Exécuter `/shopdebug reset`
3. Les nouveaux prix sont chargés immédiatement

## 📊 Données persistantes

Toutes les données sont stockées dans `shop.db` (SQLite) :
- Prix actuels de tous les items
- Historique des snapshots (graphiques)
- Transactions des joueurs
- Volumes d'achats/ventes cumulés

**Mode WAL activé** pour supporter les opérations concurrentes sans blocage.

## ⚠️ Notes importantes

- Les prix ne peuvent PAS descendre en dessous de `floor`
- Les prix ne peuvent PAS monter au dessus de `ceil`
- La régression ramène progressivement vers les prix de base
- Les items peuvent être gelés (`frozen=1` dans la DB) pour bloquer les variations

