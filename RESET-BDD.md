# Remettre les données à zéro

Trois scénarios, du plus fin au plus large. Pour restaurer après un incident,
voir [RESTAURATION-BDD.md](RESTAURATION-BDD.md).

| Scénario | Ce qui part | Ce qui reste | Section |
|---|---|---|---|
| **Un seul joueur** | son argent, ses PB, son grade, son stuff, ses stats | tout le reste du serveur | [§3](#3-reset-dun-seul-joueur) |
| **Reset global** | grades achetés, PB, monnaie, inventaires, enderchests, spawners, HDV, stats… | warps, spawns, catalogue du marché | [§4](#4-reset-global-nouvelle-saison) |
| **Light reset** | monnaie, inventaires, enderchests, HDV, stats, métiers, homes | **grades, PB et spawners** | [§5](#5-light-reset-on-garde-grades-pb-et-spawners) |

> **Aucune de ces opérations n'est réversible.** La sauvegarde de [§2.1](#21-sauvegarder)
> n'est pas une formalité : c'est le seul retour arrière.

---

## 1. Où vit quoi

C'est la partie à lire en premier : les données d'un joueur sont réparties sur
**trois bases et deux dossiers**, et vider la mauvaise donne l'illusion d'un
reset réussi jusqu'à la première reconnexion.

| Donnée | Où elle vit | Comment on la remet à zéro |
|---|---|---|
| Monnaie en jeu ($) | H2 `ess_accounts` (+ copie d'affichage `player_profiles.balance`) | `/eco set` ou SQL |
| **Points Boutique** | MariaDB `azuriom` → **`users.money`** | `/pb set` ou SQL |
| Grades et permissions | MariaDB `luckperms` → `luckperms_user_permissions`, `luckperms_players` | `lp user … clear`, ou SQL serveur arrêté ([§4.4](#44-grades-luckperms)) |
| Achats de boutique (le verrou « déjà acheté ») | MariaDB `azuriom` → `rc_entitlements` | SQL |
| Commandes du site pas encore livrées | MariaDB `azuriom` → `rc_orders` | SQL |
| **Inventaire, armure, enderchest, XP** | H2 `player_data` **et** `world*/playerdata/<uuid>.dat` | les **deux**, voir [§3.3](#33-inventaire-et-enderchest-les-deux-emplacements) |
| Stats, métiers, homes, amis, sanctions, HDV, marché | H2 `central` | SQL |
| Spawners possédés | `plugins/MySpawner/Spawners/*.json` | fichiers |
| Factions | YAML de RedFaction | fichiers |
| Classements du site (`rc_players`, `rc_factions`) | MariaDB `azuriom` | **rien à faire** : SiteSync repousse un instantané toutes les 5 min |

**Le piège des PB.** Le solde PB **est** `users.money` chez Azuriom
(`pb.ledger: site`, la valeur par défaut) : il n'y a pas de copie côté jeu, la
colonne `player_profiles.pb` n'est qu'un cache d'affichage pour `/profil`. Si et
seulement si `config.yml` porte `pb.ledger: h2`, c'est l'inverse : le solde vit
dans `player_profiles.pb` et vider cette table **détruit les PB**. Vérifiez avant
de toucher à quoi que ce soit :

```bash
grep -A 2 "^pb:" plugins/RedConflictCore/config.yml
```

---

## 2. Préparatifs

### 2.1 Sauvegarder

```bash
# En jeu, sur le Faction (l'hôte H2) :
/dbbackup now

# Les deux MariaDB, depuis la machine :
mysqldump --single-transaction azuriom   > azuriom-avant-reset.sql
mysqldump --single-transaction luckperms > luckperms-avant-reset.sql
```

### 2.2 Arrêter les serveurs, dans cet ordre

**Minage d'abord, Faction ensuite.** Le Faction héberge le serveur H2 TCP et le
Minage s'y connecte en client : couper le Faction en premier casse la connexion
du Minage, qui peut alors réécrire des données pendant son propre arrêt.

Et surtout : les modules gardent des caches en mémoire (profils, soldes,
inventaires, métiers) qu'ils réécrivent à la déconnexion et à l'arrêt. **Vider
une table serveur allumé, c'est la voir se remplir à nouveau dans la minute.**

### 2.3 Se connecter aux bases

**MariaDB** — les identifiants root sont dans `/root/.my.cnf`, donc aucun mot de
passe à taper. Tel quel, ça marche :

```bash
ssh red.conflict

sudo mariadb azuriom      # PB (users.money), achats, comptes du site
sudo mariadb luckperms    # grades et permissions

# Une requête sans ouvrir de session
sudo mariadb azuriom -e "SELECT COUNT(*) FROM users WHERE money > 0;"

# Jouer un fichier
sudo mariadb azuriom < reset-pb.sql
```

Une fois dedans, `SHOW TABLES;` confirme qu'on est sur la bonne base :
`azuriom` liste `users` et les `rc_*`, `luckperms` liste les `luckperms_*`.

> N'utilisez le compte applicatif `rc_sync` que si vous devez tester ses droits :
> il n'a volontairement ni `CREATE` ni accès complet à `users`. Son mot de passe
> est dans `/home/lezink/rc-sync-credentials.txt` s'il n'a pas déjà été supprimé,
> et dans le `site.password` du serveur Faction.

**H2** — le driver et ses outils sont embarqués dans le jar du plugin, rien à
installer. Une seule variable à renseigner, le reste se colle :

```bash
# La racine du serveur Faction, celle qui contient spigot.jar
SRV=/chemin/vers/serveur-faction

# Si vous ne l'avez pas en tête :
sudo find / -name central.mv.db -path '*RedConflictCore*' 2>/dev/null

# Shell interactif
java -cp "$SRV"/plugins/RedConflictCore*.jar org.h2.tools.Shell \
     -url "jdbc:h2:$SRV/plugins/RedConflictCore/data/central;MODE=PostgreSQL" \
     -user sa -password ""

# Jouer un fichier .sql d'un coup
java -cp "$SRV"/plugins/RedConflictCore*.jar org.h2.tools.RunScript \
     -url "jdbc:h2:$SRV/plugins/RedConflictCore/data/central;MODE=PostgreSQL" \
     -user sa -script reset.sql -showResults
```

`SHOW TABLES;` doit lister `player_profiles`, `ess_accounts`, `hdv_listings`…
Une base vide veut dire que le chemin est faux : le `MODE=PostgreSQL` et le
chemin doivent être **identiques** à ceux de `config.yml` → `database`, sinon H2
crée sereinement une base neuve à côté et vous croirez avoir tout perdu.

Si le serveur tourne en conteneur et que `java` n'existe pas sur l'hôte, lancez
les deux commandes depuis le conteneur, ou installez un JRE — l'outil H2 n'a
besoin de rien d'autre que du jar.

---

## 3. Reset d'un seul joueur

**Le joueur doit être déconnecté** — sinon ses caches réécrivent tout à sa
déconnexion. S'il est en ligne : `/kick <joueur> Remise à zéro de ton compte`.

Il faut son UUID, sous **deux formes** : avec tirets pour H2, sans tirets pour
`users.game_id` chez Azuriom.

```sql
-- H2 : retrouver l'UUID à partir du pseudo
SELECT uuid, name FROM player_profiles WHERE LOWER(name) = LOWER('Pseudo');
```

### 3.1 Ce qui se fait en commande

À faire serveur **allumé**, avant de l'éteindre pour le reste :

```
/eco set <joueur> 0                  # monnaie en jeu
/pb set <joueur> 0                   # Points Boutique (écrit dans users.money)
lp user <joueur> clear               # grades, permissions, préfixe — console
lp user <joueur> parent set default  # le remettre dans le groupe de base
```

`/eco` et `/pb` acceptent un joueur hors ligne : ils résolvent l'UUID via le
cache du serveur.

### 3.2 Le reste, en SQL

H2 (`central`) — remplacer `@UUID` par l'UUID **avec tirets** :

```sql
SET REFERENTIAL_INTEGRITY FALSE;

DELETE FROM player_profiles    WHERE uuid = '@UUID';   -- stats, solde affiché, prime, streak
DELETE FROM player_data        WHERE uuid = '@UUID';   -- inventaire, armure, enderchest, XP, vie
DELETE FROM player_locks       WHERE uuid = '@UUID';   -- verrou de présence cross-serveur
DELETE FROM player_names       WHERE uuid = '@UUID';
DELETE FROM ess_accounts       WHERE uuid = '@UUID';   -- solde réel (si /eco n'a pas été utilisé)
DELETE FROM ess_seen           WHERE uuid = '@UUID';
DELETE FROM ess_homes          WHERE uuid = '@UUID';
DELETE FROM ess_back           WHERE uuid = '@UUID';
DELETE FROM ess_ignores        WHERE uuid = '@UUID';
DELETE FROM ess_player_states  WHERE uuid = '@UUID';
DELETE FROM player_jobs        WHERE uuid = '@UUID';   -- métiers et XP
DELETE FROM topluck            WHERE uuid = '@UUID';   -- compteurs de minage
DELETE FROM vote_counts        WHERE uuid = '@UUID';
DELETE FROM vote_pending       WHERE uuid = '@UUID';
DELETE FROM friends            WHERE uuid_a = '@UUID' OR uuid_b = '@UUID';
DELETE FROM friend_requests    WHERE sender_uuid = '@UUID' OR receiver_uuid = '@UUID';
DELETE FROM hdv_listings       WHERE seller_uuid = '@UUID';
DELETE FROM hdv_earnings       WHERE uuid = '@UUID';
DELETE FROM hdv_economy        WHERE uuid = '@UUID';
DELETE FROM shop_transactions  WHERE player_uuid = '@UUID';

-- Modération : à ne PAS effacer si le joueur est banni ou averti.
-- DELETE FROM sanctions  WHERE uuid = '@UUID';
-- DELETE FROM player_ips WHERE uuid = '@UUID';
-- DELETE FROM player_hwid WHERE uuid = '@UUID';   -- empreinte matérielle anti-contournement

SET REFERENTIAL_INTEGRITY TRUE;
```

MariaDB `azuriom` — attention, `game_id` porte l'UUID **sans tirets** :

```sql
UPDATE users SET money = 0 WHERE game_id = '@UUIDSANSTIRETS';   -- PB, si /pb n'a pas été utilisé
DELETE FROM rc_entitlements WHERE uuid = '@UUID';               -- il pourra racheter ses grades
DELETE FROM rc_players      WHERE uuid = '@UUID';               -- se recrée seul à la reconnexion
```

`rc_entitlements` est le verrou « tu possèdes déjà cet article ». Le laisser en
place après avoir retiré le grade dans LuckPerms, c'est un joueur sans grade que
la boutique refuse de resservir.

MariaDB `luckperms` — seulement si `lp user … clear` n'a pas été utilisé, et
**serveur arrêté** (voir le piège du cache en [§4.4](#44-grades-luckperms)) :

```sql
DELETE FROM luckperms_user_permissions WHERE uuid = '@UUID';
DELETE FROM luckperms_players          WHERE uuid = '@UUID';
```

### 3.3 Inventaire et enderchest : les deux emplacements

C'est le point que l'on rate une fois sur deux. À la connexion,
`PlayerDataSyncService.loadAndApply()` **écrase l'inventaire local avec le
contenu de H2** — c'est ce qui rend la synchro Faction ⇄ Minage possible. Donc :

- supprimer seulement le `.dat` → H2 réécrit l'ancien stuff à la reconnexion ;
- supprimer seulement la ligne H2 → le `.dat` vanilla reprend la main et
  redevient la nouvelle référence.

Il faut donc les **deux** : la ligne `player_data` ci-dessus, **et** le fichier,
dans chaque monde de chaque serveur :

```bash
# serveurs arrêtés, depuis la racine de chaque serveur (Faction, Minage…)
rm -f world*/playerdata/<uuid-avec-tirets>.dat
rm -f world*/stats/<uuid-avec-tirets>.json
```

---

## 4. Reset global (nouvelle saison)

Tout ce qui appartient aux joueurs part. Warps, spawns et catalogue du marché
restent : ce sont des données de configuration, pas de progression.

### 4.1 H2 `central`

`SET REFERENTIAL_INTEGRITY FALSE` évite d'avoir à respecter l'ordre des clés
étrangères (`shop_price_history` et `shop_transactions` pointent vers
`shop_items`). À remettre à `TRUE` : sans quoi la base tourne sans contrôle
d'intégrité.

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

-- Métiers et classements
DELETE FROM player_jobs;
DELETE FROM job_top_snapshot;
DELETE FROM job_top_meta;

-- Social et votes
DELETE FROM friends;
DELETE FROM friend_requests;
DELETE FROM vote_counts;
DELETE FROM vote_pending;

-- Modération (voir l'avertissement plus bas)
DELETE FROM sanctions;
DELETE FROM player_ips;
DELETE FROM player_hwid;
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

-- Prix ramenés à leur valeur de référence, volumes à zéro
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

> **Bans et empreintes matérielles.** Les trois lignes `sanctions`,
> `player_ips` et `player_hwid` amnistient tous les tricheurs bannis, y compris
> définitivement. Une nouvelle saison n'est pas une raison de laisser revenir
> celui qui a été banni pour X-Ray : commentez ces trois lignes si vous n'avez
> pas décidé le contraire explicitement.

### 4.2 Points Boutique — MariaDB `azuriom`

Le solde PB **est** `users.money`. Il est lu en direct à chaque consultation
(`SitePBLedger`), sans aucun cache côté jeu : une remise à zéro en SQL prend
effet immédiatement, même serveur allumé.

```sql
-- Tous les joueurs
UPDATE users SET money = 0;

-- Un seul joueur (game_id = UUID SANS tirets)
UPDATE users SET money = 0 WHERE game_id = '@UUIDSANSTIRETS';

-- Vérifier
SELECT COUNT(*) FROM users WHERE money > 0;                  -- 0
SELECT name, money FROM users ORDER BY money DESC LIMIT 10;  -- les plus riches
```

En jeu, `/pb set <joueur> 0` fait exactement la même écriture, joueur hors ligne
compris : pour quelques comptes, c'est plus sûr que le SQL, ça journalise le
mouvement et ça évite de se tromper de format d'UUID.

> **Ne touchez pas à `rc_pb_log`.** C'est le journal des mouvements de PB, donc
> la seule trace en cas de litige avec un joueur qui a payé. C'est aussi le
> témoin d'idempotence de `PBMigration` : le vider pendant que
> `player_profiles.pb` porte encore des valeurs relancerait la migration au
> prochain démarrage et **recréditerait** tout le monde. Dans l'ordre donné ici
> le risque est nul (H2 est vidé avant), mais l'ordre compte.

### 4.3 Achats de boutique — MariaDB `azuriom`

```sql
TRUNCATE TABLE rc_entitlements;  -- plus personne ne « possède » d'article
TRUNCATE TABLE rc_orders;        -- commandes du site non livrées
TRUNCATE TABLE rc_players;       -- se remplit seul, SiteSync repousse sous 5 min
TRUNCATE TABLE rc_factions;      -- idem
```

`rc_entitlements` est le verrou « tu possèdes déjà cet article ». Il doit partir
**en même temps** que les grades LuckPerms de §4.4 : les garder après avoir
retiré les grades laisse des joueurs sans grade que la boutique refuse de
resservir.

### 4.4 Grades LuckPerms

Deux routes. **Préférez les commandes** : elles écrivent en base *et* rafraîchissent
le cache mémoire, alors que le SQL ne fait que la moitié du travail.

#### Route 1 — commandes (serveur allumé)

Depuis la **console**, pas en jeu :

```
lp bulkupdate users delete
```

LuckPerms demande un code de confirmation à retaper. Cette commande efface les
données de tous les *utilisateurs* et laisse les *groupes* intacts : la
hiérarchie (`elite`, `immortel`, `moderateur`…), leurs permissions et leurs
préfixes sont conservés, seule l'appartenance des joueurs disparaît.

Variantes utiles :

```
# Ne retirer que les grades vendus en boutique, sans toucher au staff
lp bulkupdate users delete "permission == group.elite"
lp bulkupdate users delete "permission == group.immortel"

# Un seul joueur
lp user <joueur> clear
lp user <joueur> parent set default
```

#### Route 2 — directement en base (serveur ARRÊTÉ)

La base s'appelle `luckperms`, les tables portent le préfixe `luckperms_`
(`storage-method: mariadb`, `table-prefix` dans `plugins/LuckPerms/config.yml`).

```sql
-- Ce que les JOUEURS ont : permissions, grades, préfixes personnels
TRUNCATE TABLE luckperms_user_permissions;

-- Le lien pseudo ↔ UUID et le grade principal affiché
TRUNCATE TABLE luckperms_players;

-- Un seul joueur (UUID AVEC tirets)
DELETE FROM luckperms_user_permissions WHERE uuid = '@UUID';
DELETE FROM luckperms_players          WHERE uuid = '@UUID';
```

Ce qu'on **ne touche pas**, sauf à vouloir reconstruire toute la hiérarchie à la
main : `luckperms_groups` (les groupes eux-mêmes), `luckperms_group_permissions`
(leurs permissions et préfixes), `luckperms_tracks` (les pistes de promotion).
`luckperms_actions` est le journal d'audit : à garder aussi, c'est lui qui dit
qui a donné quel grade à qui.

> **Le piège du cache.** LuckPerms garde les utilisateurs connectés en mémoire et
> les réécrit en base. Un `TRUNCATE` serveur allumé ne change donc rien pour les
> joueurs en ligne — pire, leurs données seront **réécrites par-dessus** à leur
> déconnexion. Si vous devez le faire à chaud, enchaînez avec `lp sync` (qui
> recharge depuis la base) et acceptez que les joueurs connectés à cet instant
> gardent leur grade jusqu'à leur reconnexion. Serveur arrêté, la question ne se
> pose pas.

### 4.5 Fichiers

```bash
# serveurs arrêtés

# Spawners possédés (MySpawner) — les spawners DÉJÀ POSÉS restent des blocs
# dans le monde : seul un reset de map les enlève.
rm -f plugins/MySpawner/Spawners/*.json
# facultatif, repart les identifiants à zéro : LastID: 0 dans plugins/MySpawner/config.yml

# Inventaires vanilla, sur CHAQUE serveur (Faction, Minage…)
rm -rf world*/playerdata/ world*/stats/

# Factions : appartenances, claims, coffres de faction
rm -rf plugins/RedFaction/data/factions/ plugins/RedFaction/data/chests/
rm -f  plugins/RedFaction/data/players.json
# config.yml et levels.yml restent : ce sont des réglages, pas de la progression

# Journaux PB en jeu (le journal de référence reste rc_pb_log côté site)
rm -f plugins/RedConflictCore/pb/pb_logs.txt
```

### 4.6 Vérifications avant de rallumer

```sql
-- H2
SELECT COUNT(*) FROM player_profiles;   -- 0
SELECT COUNT(*) FROM ess_warps;         -- inchangé
SELECT COUNT(*) FROM shop_items;        -- inchangé

-- azuriom
SELECT COUNT(*) FROM users WHERE money > 0;   -- 0
SELECT COUNT(*) FROM rc_entitlements;         -- 0
```

### Variante — repartir d'une base H2 entièrement neuve

Plus radical et plus rapide que le script : les tables sont recréées vides au
démarrage, le catalogue se réamorce depuis `shop/shop_items.yml`.

```bash
cd plugins/RedConflictCore/data
mv central.mv.db central.mv.db.avant-reset
rm -f central.trace.db
```

**Ce que ça coûte en plus** : les warps (`ess_warps`) et les spawns
(`ess_spawns`) partent aussi, à refaire au `/setwarp` et `/setspawn`. Gardez le
`.avant-reset` jusqu'à validation complète.

---

## 5. Light reset (on garde grades, PB et spawners)

Pour relancer une saison sans effacer ce que les joueurs ont **payé**.

| Conservé | Effacé |
|---|---|
| Grades et permissions (LuckPerms) | Monnaie en jeu ($) |
| Points Boutique (`users.money`) | Inventaires, armures, enderchests, XP |
| Achats de boutique (`rc_entitlements`) | Stats : kills, morts, temps de jeu, killstreak, primes |
| Spawners possédés (MySpawner) | Métiers et classements |
| Warps, spawns, catalogue | HDV : ventes en cours, gains en attente, historique |
| | Marché : historique, prix revenus à la référence |
| | Homes, `/back`, amis, votes |

Trois choses seulement à faire.

**1. H2** — noter le `UPDATE` sur `player_profiles` : il remet les stats à zéro
**sans supprimer la ligne**, pour préserver la colonne `pb` au cas où le serveur
tournerait en `pb.ledger: h2`. C'est la seule différence de fond avec le script
du §4, et c'est ce qui rend ce script sûr dans les deux configurations.

```sql
SET REFERENTIAL_INTEGRITY FALSE;

-- Stats et économie remises à zéro, colonne pb intacte
UPDATE player_profiles SET kills = 0, deaths = 0, playtime_s = 0, balance = 0,
                           streak = 0, bounty = 0, xp_boost_until = 0;

DELETE FROM player_data;        -- inventaires, armures, enderchests, XP
DELETE FROM player_locks;
DELETE FROM ess_accounts;       -- monnaie en jeu
DELETE FROM ess_homes;
DELETE FROM ess_back;
DELETE FROM player_jobs;
DELETE FROM job_top_snapshot;
DELETE FROM job_top_meta;
DELETE FROM friends;
DELETE FROM friend_requests;
DELETE FROM vote_counts;
DELETE FROM vote_pending;
DELETE FROM topluck;

DELETE FROM hdv_listings;
DELETE FROM hdv_earnings;
DELETE FROM hdv_transactions;
DELETE FROM hdv_economy;

DELETE FROM shop_transactions;
DELETE FROM shop_price_history;
DELETE FROM shop_events;
UPDATE shop_items SET current_buy_price  = base_buy_price,
                      current_sell_price = base_sell_price,
                      total_buy_volume   = 0,
                      total_sell_volume  = 0,
                      frozen             = 0;

ALTER TABLE hdv_listings       ALTER COLUMN id RESTART WITH 1;
ALTER TABLE hdv_transactions   ALTER COLUMN id RESTART WITH 1;
ALTER TABLE shop_transactions  ALTER COLUMN id RESTART WITH 1;
ALTER TABLE shop_price_history ALTER COLUMN id RESTART WITH 1;
ALTER TABLE shop_events        ALTER COLUMN id RESTART WITH 1;

SET REFERENTIAL_INTEGRITY TRUE;
```

Sanctions, IP et empreintes matérielles ne sont **pas** touchées : les bannis
restent bannis.

**2. Fichiers** — inventaires vanilla uniquement, on ne touche ni à MySpawner ni
à LuckPerms :

```bash
rm -rf world*/playerdata/ world*/stats/     # sur chaque serveur
```

**3. Azuriom** — rien à faire. `users.money` et `rc_entitlements` sont
justement ce qu'on garde.

> **Le stuff donné avec les grades revient-il ?** Non : un grade donne des
> permissions, pas des objets. Les kits (`greatkits.kits.*`) restent
> réclamables, ce qui est cohérent — le joueur a payé un accès au kit, pas une
> unique livraison. Si vous voulez qu'ils repartent nus, il faut réinitialiser
> les cooldowns de GreatKits dans son propre stockage.

---

## 6. Redémarrer

**Faction d'abord** (il porte le serveur H2 TCP), Minage ensuite, une fois que
la console du Faction affiche que la base est joignable.

Dans l'autre sens, le Minage ne trouve personne sur le port 9092 et ses modules
DB se désactivent proprement — mais silencieusement pour les joueurs, qui
verront simplement leur inventaire ne plus se synchroniser.

Contrôles utiles dans les premières minutes :

- se connecter avec un compte de test : inventaire vide, `/money` à 0, `/pb` au
  solde attendu selon le scénario ;
- `/pbshop` s'ouvre et propose bien les articles (le verrou d'appartenance
  suit `rc_entitlements`) ;
- la console ne répète pas d'erreur `[Sync]` ni `[Site]` ;
- au bout de 5 min, les classements du site se repeuplent tout seuls.

---

## 7. Ce qu'aucun reset de base ne touche

| Donnée | Où | Pour la remettre à zéro |
|---|---|---|
| Le monde lui-même (bases, coffres, spawners posés) | `world/`, `world_nether/`… | régénérer la map — décision à part entière |
| Comptes du site, rôles, tickets, paiements | MariaDB `azuriom` (tables Azuriom) | panel Azuriom |
| Cooldowns et accès des kits | GreatKits | stockage propre au plugin |
| Configurations des plugins | `plugins/*/config.yml` | à la main |

Un reset H2 seul laisse les joueurs avec leurs grades et leurs PB mais sans
argent, sans stats et sans homes. C'est exactement le *light reset* du §5 — mais
ça se décide, ça ne se subit pas.
