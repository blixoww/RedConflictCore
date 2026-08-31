# Restaurer après incident

Quoi restaurer, dans quel ordre, et avec quoi. Pour une remise à zéro
volontaire, voir [RESET-BDD.md](RESET-BDD.md).

---

## 1. Inventaire des bases

Trois bases portent des données vivantes.

| # | Base | Contenu | Sauvegarde |
|---|---|---|---|
| 1 | **H2 `central`** | tout le gameplay : profils, inventaires, économie, métiers, HDV, marché, sanctions, homes, warps | **automatique** par le plugin |
| 2 | **MariaDB LuckPerms** | grades, permissions, préfixes | à faire — cron / Pterodactyl |
| 3 | **MariaDB `azuriom`** | comptes joueurs, boutique web, rôles, tickets, vote + `rc_players` / `rc_factions` | à faire — cron / Pterodactyl |

À côté, du stockage fichier qui n'est pas une base mais se perd aussi : les
factions (`plugins/RedFaction/data/`), les spawners possédés
(`plugins/MySpawner/Spawners/*.json`), les mondes (`world/`,
`world/playerdata/`), les configurations des plugins.

> Le point 1 est verrouillé. **Les points 2 et 3 sont le vrai risque** : le
> journal de migration les signale déjà comme le seul manque dont la perte
> serait définitive. Une restauration H2 réussie avec une MariaDB perdue rend
> le serveur jouable mais sans comptes ni grades.

### Ce que le plugin sauvegarde, exactement

`BackupManager`, actif seulement sur l'hôte H2 (le Faction, celui qui a
`database.server.enabled: true`) :

- **Où** — `Backup/Back_<AAAA-MM-JJ_HH-mm>.zip`, relatif à la racine du serveur
  (`backup.folder`).
- **Quoi** — un fichier `h2_central.sql`, produit par `SCRIPT TO` sur la
  connexion vivante : un dump SQL transactionnellement cohérent, schéma +
  données. Si `SCRIPT TO` échoue, repli sur une copie de `central.mv.db`.
  **Regardez ce que contient l'archive avant de choisir la procédure de §3.**
- **Quand** — toutes les `backup.interval-hours` heures (6 par défaut), la
  première après un intervalle complet et non au démarrage.
- **Rétention** — `max-backups: 28` et `retention-days: 14`, les deux critères
  s'appliquent.
- **À la demande** — `/dbbackup now`. `/dbbackup next` donne l'échéance suivante.

Tout est asynchrone : déclencher une sauvegarde ne lague pas le serveur.

**Angle mort à connaître** : la sauvegarde vit sur la même machine que la base.
Un disque perdu emporte les deux. Une copie hors machine est le seul vrai filet.

---

## 2. Choisir quoi restaurer

| Symptôme | Ce qui est touché | Aller à |
|---|---|---|
| Inventaires, argent, stats, métiers effacés ou incohérents | H2 | §3 |
| Le serveur démarre mais tous les joueurs sont « neufs » | H2, ou mauvais chemin de base | §3 puis §6 |
| Les grades ont disparu, plus personne n'est staff | MariaDB LuckPerms | §4 |
| Connexion au launcher impossible, comptes introuvables | MariaDB `azuriom` | §4 |
| Classements du site vides | ni l'un ni l'autre | rien à faire, SiteSync repousse sous 5 min |
| Factions disparues | fichiers YAML RedFaction | restaurer le dossier du plugin |

Restaurer H2 pour un problème de grades ne sert à rien : les deux bases sont
indépendantes, seul l'UUID les relie.

---

## 3. Restaurer H2

### 3.1 Arrêter, dans l'ordre

**Minage d'abord, Faction ensuite.** Le Faction porte le serveur TCP ; le couper
en premier laisse le Minage écrire dans le vide au moment de son arrêt.

### 3.2 Mettre l'existant de côté — ne jamais écraser

```bash
cd plugins/RedConflictCore/data
cp central.mv.db central.mv.db.incident-$(date +%F_%H-%M)
```

Même corrompue, la base actuelle peut contenir des heures que la sauvegarde
n'a pas. C'est aussi la seule pièce exploitable si la restauration se passe mal.

### 3.3 Extraire l'archive

```bash
cd /chemin/vers/faction
unzip Backup/Back_2026-08-19_06-00.zip -d /tmp/restore
ls -l /tmp/restore
```

### 3.4a L'archive contient `h2_central.sql` — cas normal

`RUNSCRIPT` rejoue le dump dans une base **vide** : il faut donc retirer
l'ancien fichier, pas écrire par-dessus.

```bash
mv plugins/RedConflictCore/data/central.mv.db /tmp/central.avant-restauration.mv.db

java -cp plugins/RedConflictCore.jar org.h2.tools.RunScript \
     -url "jdbc:h2:./plugins/RedConflictCore/data/central;MODE=PostgreSQL" \
     -user sa -password "" \
     -script /tmp/restore/h2_central.sql -showResults
```

Le driver et les outils H2 sont embarqués dans le jar du plugin (H2 n'est pas
relocalisé au shade) : aucun téléchargement.

### 3.4b L'archive contient `central.mv.db` — repli

`SCRIPT TO` avait échoué au moment de la sauvegarde. Le fichier est
*crash-consistent* : H2 le recouvre au chargement, avec une possible perte des
toutes dernières écritures.

```bash
cp /tmp/restore/central.mv.db plugins/RedConflictCore/data/central.mv.db
```

### 3.5 Vérifier avant de rallumer

```bash
java -cp plugins/RedConflictCore.jar org.h2.tools.Shell \
     -url "jdbc:h2:./plugins/RedConflictCore/data/central;MODE=PostgreSQL" \
     -user sa -password ""
```

```sql
SELECT COUNT(*) FROM player_profiles;
SELECT name, balance, kills FROM player_profiles ORDER BY last_join DESC LIMIT 5;
SELECT COUNT(*) FROM ess_warps;
SELECT COUNT(*) FROM shop_items;
```

Les cinq derniers connectés doivent vous parler. Un `player_profiles` à 0 sur
une archive non vide signifie que vous avez ouvert une autre base : contrôlez le
chemin et le nom (`config.yml` → `database.name`).

### 3.6 Purger les verrous de présence

Un arrêt brutal laisse des lignes dans `player_locks`. Avec
`database.lock.kick-on-conflict: true`, les joueurs concernés sont kickés à la
connexion.

```sql
DELETE FROM player_locks;
```

Sans risque : la table se reconstruit à chaque connexion.

### 3.7 Redémarrer

**Faction d'abord**, attendre que la console confirme la base joignable, **puis
le Minage**.

---

## 4. Restaurer MariaDB

Même procédure pour LuckPerms et pour Azuriom, seul le nom de base change.

```bash
# Sauvegarde (à mettre en cron si ce n'est pas déjà fait)
mysqldump -u root -p --single-transaction --routines --events \
          azuriom > /var/backups/azuriom_$(date +%F).sql

# Restauration
mysql -u root -p azuriom < /var/backups/azuriom_2026-08-19.sql
```

`--single-transaction` évite de verrouiller les tables pendant le dump, donc de
figer le site.

**Après une restauration Azuriom** : vider le cache de configuration Laravel,
sans quoi le site sert l'ancien état.

```bash
php artisan config:clear
```

**Après une restauration LuckPerms** : `/lp networksync` en jeu, ou un
redémarrage des serveurs — LuckPerms garde les permissions en mémoire.

**`rc_players` et `rc_factions`** n'ont pas besoin d'être restaurées : SiteSync
les réécrit depuis H2 toutes les 5 minutes, en sens unique. Si elles sont vides
après restauration, c'est normal — attendez un cycle.

> Coller un gros SQL dans le client interactif `mysql` le mutile en silence et
> répond `Query OK` quand même. Passez toujours par une redirection de fichier,
> comme ci-dessus.

---

## 5. Redéploiement complet

Machine perdue, tout à remonter. L'ordre compte : chaque étape suppose la
précédente en place.

1. **Infrastructure** — Pterodactyl, conteneurs, allocations. Les serveurs
   Minecraft doivent être alloués sur l'adresse du **pont Docker**, pas sur
   `0.0.0.0` : c'est ce qui ferme les ports backend. `ufw` n'y peut rien, Docker
   publie en DNAT et ses paquets passent par `FORWARD`, jamais par `INPUT`.
2. **MariaDB** — restaurer `azuriom` et la base LuckPerms (§4).
3. **Site Azuriom** — déployer, `AZURIOM_GAME` dans `.env`, `php artisan
   config:clear`, activer l'API d'authentification dans Paramètres →
   Authentification (désactivée par défaut, sinon tous les jetons sont refusés).
4. **Serveur Faction** — plugins, `config.yml` avec `database.server.enabled:
   true` et `server-id: faction`. Restaurer H2 (§3). Démarrer, vérifier le port
   9092.
5. **Serveur Minage** — `database.server.enabled: false`, `host: 127.0.0.1`
   — **jamais `localhost`** : avec `enabled: false`, `localhost` fait ouvrir une
   base fichier isolée au lieu de se connecter au Faction, et la synchro
   d'inventaire échoue sans erreur visible.
6. **HUB** — lobby verrouillé, pas de RedConflictCore, donc pas de base.
7. **Vérifications** — §6.

---

## 6. Vérifications post-incident

| Contrôle | Attendu |
|---|---|
| Connexion d'un joueur connu | retrouve inventaire, argent, homes |
| `/money`, `/metier info`, `/ks` | valeurs cohérentes avec l'avant-incident |
| Passage Faction → Minage | inventaire synchronisé dans les deux sens |
| `/pb` | solde de Points Boutique intact |
| Un grade acheté | commandes du grade disponibles ([PERMISSIONS.md](PERMISSIONS.md)) |
| `/dbbackup next` | l'auto-sauvegarde est bien reprogrammée sur le Faction |
| Classements du site | remplis après un cycle SiteSync (5 min) |
| Ports 25566 · 25567 · 25570 depuis l'extérieur | injoignables |

Le dernier point est facile à oublier après un redéploiement : tant que les
serveurs backend sont joignables directement, on s'y connecte sans passer par le
HUB, donc sans être authentifié — et tout le système de comptes ne vaut rien.

---

## 7. Ce qui reste à mettre en place

- **Sauvegarde MariaDB.** Le H2 se sauvegarde seul, elle non. Elle porte les
  comptes, la boutique et les grades : c'est le seul manque dont la perte serait
  définitive.
- **Copie hors machine.** Les archives `Backup/` sont sur le même disque que la
  base qu'elles protègent.
- **Restauration testée.** Une sauvegarde jamais rejouée n'est pas une
  sauvegarde. Rejouer une archive dans une base jetable, une fois, vaut tous les
  contrôles.

---

## 8. Règle de conduite

Le journal de migration en tire une leçon qui vaut aussi pour la restauration :
**on ne démonte jamais l'ancien chemin avant d'avoir emprunté le nouveau.** On
ne supprime pas la base incidentée avant d'avoir vu la restauration fonctionner,
et on ne réécrit jamais par-dessus une sauvegarde.
