# Remettre la base à zéro

Vider les données de jeu **en gardant le schéma** : les tables et leurs colonnes
restent, seules les lignes partent. Pour restaurer après un incident, voir
[RESTAURATION-BDD.md](RESTAURATION-BDD.md).

Tout ce qui suit concerne la base **H2 `central`** (`plugins/RedConflictCore/data/central.mv.db`),
la seule que ce plugin écrit. Ce qui vit ailleurs — grades LuckPerms, comptes
Azuriom, factions RedFaction — n'est **pas** touché : voir §6.

> **Sauvegarde d'abord.** `/dbbackup now` en jeu, ou copie du fichier
> `central.mv.db` serveurs arrêtés. Aucune des opérations ci-dessous n'est
> réversible.

---

## 1. Arrêter les deux serveurs

Obligatoire, et dans cet ordre : **Minage d'abord, Faction ensuite.**

Le Faction héberge le serveur H2 TCP (`database.server.enabled: true`) et le
Minage s'y connecte en client. Couper le Faction en premier casse la connexion
du Minage, qui peut alors réécrire des données au moment de son propre arrêt.

Les modules gardent aussi des caches en mémoire (profils, soldes, métiers) et
les réécrivent au `quit` et à l'arrêt : vider les tables serveur allumé revient
à les voir se remplir à nouveau dans la minute.

---

## 2. Ouvrir la base

Le driver H2 **et ses outils** sont embarqués dans le jar du plugin (H2 n'est
pas relocalisé au shade). Rien à télécharger :

```bash
# depuis la racine du serveur Faction
java -cp plugins/RedConflictCore.jar org.h2.tools.Shell \
     -url "jdbc:h2:./plugins/RedConflictCore/data/central;MODE=PostgreSQL" \
     -user sa -password ""
```

Le `MODE=PostgreSQL` et le chemin doivent être identiques à ceux de
`config.yml` → `database` (`name: central`), sinon H2 crée sereinement une
**nouvelle base vide** à côté et vous croirez avoir tout perdu.

Pour exécuter un fichier au lieu de taper les requêtes :

```bash
java -cp plugins/RedConflictCore.jar org.h2.tools.RunScript \
     -url "jdbc:h2:./plugins/RedConflictCore/data/central;MODE=PostgreSQL" \
     -user sa -script reset.sql -showResults
```

---

## 3. Ce que contient chaque table

**Données joueur — à vider**

| Table | Contenu |
|---|---|
| `player_profiles` | UUID, pseudo, kills, morts, temps de jeu, solde, grade affiché, faction, killstreak, prime, PB |
| `player_data` | inventaire, armure, enderchest, XP, vie, faim, effets (synchro cross-serveur) |
| `player_locks` | verrou de présence cross-serveur |
| `player_names` | cache UUID → pseudo |
| `ess_accounts` | soldes du module Essentials |
| `ess_seen` | première/dernière connexion |
| `ess_homes` · `ess_back` | homes et position de retour |
| `ess_ignores` · `ess_player_states` | `/ignore`, état god/fly |
| `player_jobs` | métiers et XP |
| `job_top_snapshot` · `job_top_meta` | classement des métiers |
| `friends` · `friend_requests` | amis et demandes |
| `sanctions` | warns, mutes, bans, kicks |
| `player_ips` | historique IP ↔ pseudo |
| `topluck` | compteurs de minage |
| `hdv_listings` · `hdv_earnings` · `hdv_transactions` · `hdv_economy` | hôtel des ventes |
| `shop_transactions` · `shop_price_history` | achats/ventes et historique de prix |
| `shop_events` | krach / inflation / aubaine en cours |

**Configuration serveur — à garder**

| Table | Contenu |
|---|---|
| `ess_warps` | warps, par serveur |
| `ess_spawns` | spawn de chaque serveur |
| `shop_categories` · `shop_items` | catalogue et prix de référence |

`shop_categories` et `shop_items` se **réamorcent tout seuls** depuis
`plugins/RedConflictCore/shop/shop_items.yml` au démarrage si `shop_items` est
vide (`ShopDatabase.hasItems()`). Les vider est donc sans risque — c'est même la
manière propre de reprendre un catalogue modifié dans le YAML.

---

## 4. Reset des joueurs (recommandé)

Vide tout ce qui appartient aux joueurs, garde warps, spawns et catalogue.

`SET REFERENTIAL_INTEGRITY FALSE` évite d'avoir à respecter l'ordre des clés
étrangères (`shop_price_history` et `shop_transactions` pointent vers
`shop_items`). À remettre à `TRUE` à la fin, sans quoi la base tourne sans
contrôle d'intégrité.

```sql
SET REFERENTIAL_INTEGRITY FALSE;

-- Profils, économie, synchro
DELETE FROM player_profiles;
DELETE FROM player_data;
DELETE FROM player_locks;
DELETE FROM player_names;
DELETE FROM ess_accounts;
DELETE FROM ess_seen;
DELETE FROM ess_player_states;
DELETE FROM ess_homes;
DELETE FROM ess_back;
DELETE FROM ess_ignores;

-- Métiers
DELETE FROM player_jobs;
DELETE FROM job_top_snapshot;
DELETE FROM job_top_meta;

-- Social
DELETE FROM friends;
DELETE FROM friend_requests;

-- Modération
DELETE FROM sanctions;
DELETE FROM player_ips;
DELETE FROM topluck;

-- Hôtel des ventes
DELETE FROM hdv_listings;
DELETE FROM hdv_earnings;
DELETE FROM hdv_transactions;
DELETE FROM hdv_economy;

-- Marché : historique et transactions (le catalogue reste)
DELETE FROM shop_transactions;
DELETE FROM shop_price_history;
DELETE FROM shop_events;

-- Remise des prix à leur valeur de référence, compteurs de volume à zéro
UPDATE shop_items SET current_buy_price  = base_buy_price,
                      current_sell_price = base_sell_price,
                      total_buy_volume   = 0,
                      total_sell_volume  = 0,
                      frozen             = 0;

-- Compteurs auto-incrément : sans ça les prochains id repartent de l'ancien maximum
ALTER TABLE sanctions          ALTER COLUMN id RESTART WITH 1;
ALTER TABLE hdv_listings       ALTER COLUMN id RESTART WITH 1;
ALTER TABLE hdv_transactions   ALTER COLUMN id RESTART WITH 1;
ALTER TABLE shop_transactions  ALTER COLUMN id RESTART WITH 1;
ALTER TABLE shop_price_history ALTER COLUMN id RESTART WITH 1;
ALTER TABLE shop_events        ALTER COLUMN id RESTART WITH 1;

SET REFERENTIAL_INTEGRITY TRUE;
```

Vérification avant de redémarrer :

```sql
SELECT COUNT(*) FROM player_profiles;   -- 0
SELECT COUNT(*) FROM ess_warps;         -- inchangé
SELECT COUNT(*) FROM shop_items;        -- inchangé
```

### Variante — n'effacer que les pseudos

Si le but est d'anonymiser sans perdre les statistiques (tests, démonstration,
capture d'écran), remplacer les `DELETE` par :

```sql
UPDATE player_profiles SET name = '';
UPDATE ess_accounts    SET name = '';
UPDATE ess_seen        SET name = '';
DELETE FROM player_names;
DELETE FROM player_ips;
UPDATE sanctions       SET name = 'Anonyme', staff = 'Staff';
UPDATE hdv_earnings    SET player_name = 'Anonyme';
UPDATE hdv_economy     SET player_name = 'Anonyme';
UPDATE hdv_listings    SET seller_name = 'Anonyme';
UPDATE hdv_transactions SET buyer_name = 'Anonyme', seller_name = 'Anonyme';
UPDATE shop_transactions SET player_name = 'Anonyme';
UPDATE job_top_snapshot SET name = 'Anonyme';
```

Les UUID restent : les joueurs retrouvent leurs données à la connexion suivante
et leur pseudo se réécrit tout seul. C'est une anonymisation d'affichage, pas
une suppression.

---

## 5. Reset total

Repartir d'une base entièrement neuve. Les tables sont recréées vides au
démarrage (`CREATE TABLE IF NOT EXISTS` dans chaque module), le catalogue se
réamorce depuis `shop_items.yml`.

```bash
# serveurs arrêtés
cd plugins/RedConflictCore/data
mv central.mv.db  central.mv.db.avant-reset
rm -f central.trace.db
```

**Ce que ça coûte en plus du reset joueur** : les warps (`ess_warps`) et les
spawns (`ess_spawns`) partent aussi — à refaire au `/setwarp` et `/setspawn`.

Garder le `.avant-reset` jusqu'à la validation : c'est le seul retour arrière.

---

## 6. Ce qu'un reset H2 ne touche pas

Le fichier `central.mv.db` ne contient que ce que RedConflictCore écrit. Après
un reset, restent intacts :

| Donnée | Où elle vit | Comment la remettre à zéro |
|---|---|---|
| Grades et permissions | MariaDB LuckPerms | `lp group <nom> clear`, ou voir [PERMISSIONS.md](PERMISSIONS.md) pour tout reconstruire |
| Comptes, boutique, rôles du site | MariaDB Azuriom | via le panel Azuriom |
| Classements du site | `rc_players`, `rc_factions` (MariaDB Azuriom) | se remplissent seuls, SiteSync repousse un instantané toutes les 5 min |
| Factions | fichiers YAML de RedFaction | supprimer les fichiers du dossier du plugin |
| Inventaires vanilla, positions | `world/playerdata/*.dat` | supprimer le dossier |
| Spawners posés | plugin MySpawner | selon son propre stockage |

Un reset H2 sans reset LuckPerms laisse donc les joueurs avec leurs grades
achetés mais sans argent, sans stats et sans homes. C'est parfois voulu — mais
ça se décide, ça ne se subit pas.

---

## 7. Redémarrage

**Faction d'abord** (il porte le serveur H2 TCP), puis le Minage une fois que
la console affiche que la base est joignable.

Dans l'autre sens, le Minage ne trouve personne sur le port 9092 et ses modules
DB se désactivent proprement — mais silencieusement pour les joueurs, qui
verront simplement leur inventaire ne plus se synchroniser.
