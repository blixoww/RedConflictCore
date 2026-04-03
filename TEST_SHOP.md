# Test du Shop - Instructions

## Avant de démarrer le serveur

1. **Supprimez** le fichier `shop.db` s'il existe dans `plugins/OriginsFightCore/`
2. **Supprimez** le fichier `shop_items.yml` s'il existe dans `plugins/OriginsFightCore/`

## Démarrage du serveur

Lancez le serveur et regardez les logs. Vous devriez voir :

```
[Shop] === DEBUT DE L'INITIALISATION ===
[Shop] Étape 1: Connexion à la base de données...
[Shop-DB] Chargement du driver JDBC SQLite...
[Shop-DB] Chemin de la base: .../plugins/OriginsFightCore/shop.db
[Shop-DB] Création du dossier: .../plugins/OriginsFightCore
[Shop-DB] Dossier créé: true
[Shop-DB] URL de connexion: jdbc:sqlite:...?journal_mode=WAL
[Shop-DB] ✓ Connexion établie
[Shop-DB] Configuration des PRAGMA...
[Shop-DB] ✓ PRAGMA configurés
[Shop-DB] Création des tables...
[Shop-DB] Création/vérification des tables SQL...
[Shop-DB] → Création table shop_categories...
[Shop-DB] → Création table shop_items...
[Shop-DB] → Création table shop_price_history...
[Shop-DB] → Création table shop_transactions...
[Shop-DB] ✓ Toutes les tables créées/vérifiées
[Shop-DB] ✓ Tables créées/vérifiées
[Shop-DB] ✓✓✓ Base de données connectée avec succès ✓✓✓
[Shop-DB] Mode WAL activé pour les opérations concurrentes
[Shop] ✓ Base de données connectée
[Shop] Étape 2: Configuration de l'économie...
[Shop] ✓ Économie configurée
[Shop] Étape 3: Vérification des items...
[Shop] Items présents: false
[Shop] Étape 4: Chargement des items depuis shop_items.yml...
[Shop-DB] → Début du chargement depuis shop_items.yml
[Shop-DB] Chemin du fichier: .../plugins/OriginsFightCore/shop_items.yml
[Shop-DB] Fichier non trouvé, extraction depuis les resources...
[Shop-DB] ✓ Fichier shop_items.yml créé
[Shop-DB] Nombre de catégories trouvées: 12
[Shop-DB] Création catégorie: Minerais
[Shop-DB] → 24 items dans 'Minerais'
[Shop-DB] Création catégorie: Blocs
[Shop-DB] → 17 items dans 'Blocs'
... (autres catégories)
[Shop-DB] ✓✓✓ Chargement terminé avec succès ✓✓✓
[Shop-DB] → 12 catégories créées
[Shop-DB] → 150+ items insérés
[Shop] ✓ Items chargés depuis la config
[Shop] Étape 5: Création des snapshots initiaux...
[Shop] Nombre d'items chargés: 150+
[Shop] ✓ Snapshots initiaux créés
[Shop] Étape 6: Démarrage des tâches périodiques...
[Shop] ✓ Tâches démarrées
[Shop] === INITIALISATION TERMINÉE AVEC SUCCÈS ===
```

## Si ça ne fonctionne pas

Regardez à quelle étape ça bloque dans les logs et notez :
1. Le dernier message affiché avant l'erreur
2. Le message d'erreur complet (EXCEPTION, ERREUR, etc.)
3. La stack trace si elle est affichée

## Vérification

Après le démarrage, vérifiez que :
- Le fichier `plugins/OriginsFightCore/shop.db` existe
- Le fichier `plugins/OriginsFightCore/shop_items.yml` existe
- La commande `/shop` fonctionne
- La commande `/shopdebug info` affiche les statistiques

## Fichiers créés

✅ `shop.db` — Base de données SQLite avec 4 tables
✅ `shop.db-shm` — Fichier de mémoire partagée (mode WAL)
✅ `shop.db-wal` — Fichier Write-Ahead Log (mode WAL)
✅ `shop_items.yml` — Configuration des items

