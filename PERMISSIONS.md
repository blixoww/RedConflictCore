# Permissions RedConflict

Inventaire complet des permissions du module **RedConflictCore**, correspondance
avec `/boutique`, et configuration LuckPerms prête à coller pour chaque grade.

Sources : `src/main/resources/plugin.yml` (permission déclarée par commande),
`src/main/resources/boutique/boutique.yml` (offre), et les `hasPermission(...)`
du code pour les sous-permissions.

**Règle : aucun nœud `essentials.*`.** Le module Essentials est intégré au Core
et ne lit que des nœuds `redconflict.*`.

Deux mécaniques de contrôle coexistent :

- **Bukkit** — `commands.<nom>.permission` dans `plugin.yml` : la commande est
  refusée avant d'atteindre le code.
- **Code** — `hasPermission(...)` dans la commande : sous-permissions `.others`,
  bypass, marqueurs. Elles ne sont pas déclarées dans `plugin.yml`.

Les nœuds non déclarés dans la section `permissions:` de `plugin.yml` sont
**op par défaut** : un OP a tout, sans configuration.

---

## 1. Commandes ouvertes à tous

Aucune permission requise — ne rien accorder, ne rien retirer.

`/rtp` `/ct` `/poubelle` `/hdv` `/shop` `/sellall` `/pb` `/pbshop` `/metier`
`/bottlexp` `/trade` `/baltop` `/ks` `/profil` `/prime` `/friend` `/loto`
`/guide` `/commands` `/hub` `/minage` `/faction` `/cobble` `/tpu` `/msg` `/r`
`/annonyme` `/spawn` `/tpa` `/tpaccept` `/tpno` `/tpahere` `/top` `/home`
`/sethome` `/delhome` `/warp` `/ignore` `/seen` `/list` `/help` `/pay` `/money`

---

## 2. Permissions vendues en boutique

| Nœud | Donne | Vendu comme |
|---|---|---|
| `redconflict.back` | `/back` | à l'unité + Elite |
| `redconflict.craft` | `/craft`, `/wb` | à l'unité + Elite |
| `redconflict.furnace` | `/furnace` | à l'unité + Elite |
| `redconflict.nv` | `/nightvision`, `/nv`, `/vision` | à l'unité + Elite |
| `redconflict.sethome.multiple.6` | 6 homes | Elite |
| `redconflict.ec` | `/ec`, `/enderchest` | à l'unité + Immortel |
| `redconflict.repair` | `/repairall` | à l'unité + Immortel |
| `redconflict.feed` | `/feed` | à l'unité + Immortel |
| `redconflict.near` | `/near` | à l'unité + Immortel |
| `redconflict.near.unlimited` | `/near` sans borne de rayon | vendu avec `/near` |
| `redconflict.sethome.multiple.10` | 10 homes | Immortel |
| `greatkits.kits.Elite` / `.Immortel` | `/kit elite` / `/kit immortel` | grades (plugin GreatKits) |
| `greatkits.kits.Starter` / `.Bonus` / `.Potion` | kits achetables | catégorie Kits |

Achat temporaire → `lp … permission settemp … %duree%` (30 j) : le groupe **et**
les nœuds expirent ensemble. Achat permanent → clé `commandes_perm`, sans durée.

Les spawners passent par `mspa %player% add <MOB> <n>` (MySpawner) — aucune
permission. Les packs exécutent une liste `commandes` telle quelle.

Le nombre de homes vient de `HomeService.maxHomes()` : il retient le plus grand
`redconflict.sethome.multiple.<n>` détenu (scan borné par
`homes.permission-scan-max`, 30), sinon `homes.default-max` (1 dans
`essentials.yml`).

---

## 3. Essentials — confort et administration

| Nœud | Commande / effet |
|---|---|
| `redconflict.anvil` | `/anvil` — enclume virtuelle |
| `redconflict.hat` | `/hat` |
| `redconflict.more` | `/more` |
| `redconflict.clear` | `/clear`, `/ci` |
| `redconflict.enchant` | `/enchant` |
| `redconflict.enchant.unsafe` | niveaux au-delà de la limite vanilla |
| `redconflict.give` | `/give` |
| `redconflict.kill` | `/kill` |
| `redconflict.god` | `/god` |
| `redconflict.fly` | `/fly` |
| `redconflict.speed` | `/speed` |
| `redconflict.gm` | `/gm`, `/gamemode` |
| `redconflict.heal` | `/heal` |
| `redconflict.xp` | `/xp`, `/exp` |
| `redconflict.potion` | `/potion` |
| `redconflict.eco` | `/eco` — administration de l'économie |
| `redconflict.tp` | `/tp` — téléportation directe |
| `redconflict.setspawn` | `/setspawn` |
| `redconflict.setwarp` | `/setwarp` |
| `redconflict.delwarp` | `/delwarp` |
| `redconflict.weather` | `/weather`, `/meteo` |
| `redconflict.invsee` | `/invsee` — lecture seule |
| `redconflict.admin` | `/red reload\|modules\|import` |

### Sous-permissions `.others`

Vérifiées dans le code (`EssCommand.checkOthers`) quand la commande cible un
autre joueur. Elles ne sont **pas** dans `plugin.yml`, il faut les accorder à part.

`redconflict.clear.others` · `redconflict.ec.others` · `redconflict.feed.others`
`redconflict.fly.others` · `redconflict.gm.others` · `redconflict.god.others`
`redconflict.heal.others` · `redconflict.speed.others` · `redconflict.money.others`

### Bypass et jokers

| Nœud | Effet |
|---|---|
| `redconflict.teleport.bypass` | saute le délai de `/spawn` `/home` `/warp` `/back` `/tpa` (`teleport.warmup-seconds`) |
| `redconflict.cooldown.bypass` | ignore les cooldowns de `essentials.yml` → `cooldowns` |
| `redconflict.ignore.exempt` | ne peut pas être ignoré via `/ignore` |
| `redconflict.near.unlimited` | lève `near.max-radius` (300 blocs) |
| `redconflict.sethome.multiple.<n>` | `<n>` homes (`<n>` de 2 à 30) |
| `redconflict.sethome.multiple.unlimited` | homes illimités |
| `redconflict.warp.<nom>` | accès à un warp précis, si `warps.per-warp-permission: true` |

`redconflict.teleport.bypass` et les deux nœuds `sethome.multiple` de la boutique
sont déclarés `default: false` dans `plugin.yml` : **même un OP ne les a pas**
sans les recevoir explicitement. C'est volontaire (sinon le staff téléporte
toujours instantanément).

---

## 4. Staff et modération

| Nœud | Commande / effet |
|---|---|
| `staff.staff` | **marqueur staff** — reçoit les broadcasts de sanction, voit les joueurs en vanish, reçoit les alertes PB |
| `staff.staffmode` | `/staffmode`, `/sm` |
| `staff.vanish` | `/vanish`, `/v` |
| `staff.staffchat` | `/sc`, `/staffchat` |
| `staff.freeze` | `/freeze` |
| `staff.warn` | `/warn` |
| `staff.mute` / `staff.unmute` | `/mute` / `/unmute` |
| `staff.kick` | `/kick` |
| `staff.ban` / `staff.unban` | `/ban` / `/unban` |
| `staff.sanctions` | `/sanctions` — historique |
| `staff.unsanction` | `/unsanction` — purge des sanctions actives |
| `staff.clearchat` | `/clearchat`, `/ccl` |
| `staff.lockchat` | `/lockchat` |
| `staff.msgspy` | `/msgspy` — surveillance des MP |
| `staff.giveall` | `/giveall` |
| `staff.topluck` | `/topluck`, `/tl` |
| `staff.annonyme` | voit les pseudos masqués par `/annonyme` |
| `staff.loto` | forcer / administrer le loto |
| `staff.pb-alerts` | alertes Points Boutique |
| `redconflict.staff` | `/lagswitch` `/clearlagg` `/dbbackup` `/annonce` |
| `shop.admin` | `/shopdebug` `/shopevent` |
| `jobs.admin` | `/metier topupdate\|info\|xp\|reset` |
| `redconflict.boutique.admin` | `/pbshop offre …`, `/pbshop reload` |
| `redconflict.pb.admin` | `/pb add\|remove\|set` |

Les commandes staff vérifient **deux fois** : la permission de `plugin.yml`, puis
leur propre nœud dans le code. C'est le même nœud dans les deux cas, sauf pour
`staff.staff` qui n'est qu'un marqueur — il ne débloque aucune commande.

---

## 5. Configuration des grades (LuckPerms)

Hiérarchie : chaque grade hérite du précédent, on ne redonne jamais ce qui est
déjà hérité.

```
default ──> elite ──> immortel ──> moderateur-joueur ──> moderateur
```

### 5.1 Joueur — groupe `default`

Rien à accorder : tout ce qui est listé en section 1 est déjà ouvert, et
`homes.default-max: 1` s'applique sans permission.

```
lp group default meta setprefix 1 "&7Joueur &8» &f"
lp group default setweight 1
```

### 5.2 Elite

```
lp creategroup elite
lp group elite parent add default
lp group elite setweight 10
lp group elite meta setprefix 10 "&b&lElite &8» &f"

lp group elite permission set redconflict.back true
lp group elite permission set redconflict.craft true
lp group elite permission set redconflict.furnace true
lp group elite permission set redconflict.nv true
lp group elite permission set redconflict.sethome.multiple.6 true
lp group elite permission set greatkits.kits.Elite true
```

### 5.3 Immortel

```
lp creategroup immortel
lp group immortel parent add elite
lp group immortel setweight 20
lp group immortel meta setprefix 20 "&6&lImmortel &8» &f"

lp group immortel permission set redconflict.ec true
lp group immortel permission set redconflict.repair true
lp group immortel permission set redconflict.feed true
lp group immortel permission set redconflict.near true
lp group immortel permission set redconflict.near.unlimited true
lp group immortel permission set redconflict.sethome.multiple.10 true
lp group immortel permission set greatkits.kits.Immortel true
```

`sethome.multiple.6` reste hérité d'Elite, c'est sans effet : `maxHomes()` retient
la plus grande valeur détenue, donc 10.

### 5.4 Modérateur Joueur — `moderateur-joueur`

Modérateur recruté parmi les joueurs. Mêmes avantages de jeu qu'Immortel, plus
la modération du chat et du comportement. **Pas** de sanctions lourdes, pas
d'outils serveur.

```
lp creategroup moderateur-joueur
lp group moderateur-joueur parent add immortel
lp group moderateur-joueur setweight 30
lp group moderateur-joueur meta setprefix 30 "&a&lModo-J &8» &f"

# Presence et communication
lp group moderateur-joueur permission set staff.staffmode true
lp group moderateur-joueur permission set staff.vanish true
lp group moderateur-joueur permission set staff.staffchat true
lp group moderateur-joueur permission set staff.annonyme true

# Moderation du chat et du comportement
lp group moderateur-joueur permission set staff.warn true
lp group moderateur-joueur permission set staff.mute true
lp group moderateur-joueur permission set staff.unmute true
lp group moderateur-joueur permission set staff.freeze true
lp group moderateur-joueur permission set staff.clearchat true
lp group moderateur-joueur permission set staff.lockchat true

# Consultation
lp group moderateur-joueur permission set staff.sanctions true
lp group moderateur-joueur permission set staff.topluck true
lp group moderateur-joueur permission set redconflict.invsee true
```

**Volontairement absents** — `staff.staff`, `staff.ban`, `staff.unban`,
`staff.kick`, `staff.unsanction`, `staff.msgspy`, `staff.giveall`,
`redconflict.staff`, `shop.admin`, `jobs.admin`, `redconflict.boutique.admin`,
`redconflict.pb.admin`.

À savoir sur `staff.staff` : c'est le marqueur staff, pas une commande. Sans lui,
le Modo-J **ne reçoit pas** les broadcasts de sanction et **ne voit pas** les
membres du staff en vanish. Si tu préfères qu'il soit visible comme staff tout en
gardant le reste retiré :

```
lp group moderateur-joueur permission set staff.staff true
```

### 5.5 Modérateur — `moderateur`

Permissions joueur identiques à Immortel, plus la modération complète.

```
lp creategroup moderateur
lp group moderateur parent add moderateur-joueur
lp group moderateur setweight 40
lp group moderateur meta setprefix 40 "&2&lModérateur &8» &f"

# Marqueur staff
lp group moderateur permission set staff.staff true
lp group moderateur permission set staff.pb-alerts true

# Sanctions lourdes
lp group moderateur permission set staff.kick true
lp group moderateur permission set staff.ban true
lp group moderateur permission set staff.unban true
lp group moderateur permission set staff.unsanction true

# Outils d'enquete et de serveur
lp group moderateur permission set staff.msgspy true
lp group moderateur permission set staff.giveall true
lp group moderateur permission set staff.loto true
lp group moderateur permission set redconflict.staff true

# Deplacement en intervention
lp group moderateur permission set redconflict.tp true
lp group moderateur permission set redconflict.fly true
lp group moderateur permission set redconflict.teleport.bypass true
lp group moderateur permission set redconflict.cooldown.bypass true
```

`redconflict.tp` / `.fly` / les deux bypass sont des outils d'intervention, pas
des avantages de jeu : retire-les si tu veux un modérateur strictement au niveau
Immortel hors modération.

**Hors périmètre modérateur** (réservé admin) : `redconflict.admin`,
`redconflict.eco`, `redconflict.give`, `redconflict.gm`, `redconflict.kill`,
`redconflict.xp`, `redconflict.potion`, `redconflict.enchant`,
`redconflict.setspawn`, `redconflict.setwarp`, `redconflict.delwarp`,
`redconflict.weather`, `shop.admin`, `jobs.admin`,
`redconflict.boutique.admin`, `redconflict.pb.admin`, et les `.others`.

### 5.6 Administrateur (pour mémoire)

```
lp creategroup admin
lp group admin parent add moderateur
lp group admin setweight 100
lp group admin meta setprefix 100 "&c&lAdmin &8» &f"
lp group admin permission set redconflict.* true
lp group admin permission set staff.* true
lp group admin permission set shop.admin true
lp group admin permission set jobs.admin true
```

Le joker `redconflict.*` couvre aussi `redconflict.teleport.bypass` et les
`sethome.multiple.<n>`, malgré leur `default: false`.

### 5.7 Attribuer un grade

```
lp user <pseudo> parent set elite
lp user <pseudo> parent addtemp elite 30d      # achat temporaire
lp user <pseudo> parent set moderateur
```

---

## 6. À configurer hors du Core

1. **Kits GreatKits `Elite` et `Immortel`** : les nœuds sont accordés par les
   grades, mais les kits doivent exister côté GreatKits, sinon `/kit elite` ne
   répond pas.
2. **MySpawner** et **Vault** doivent être présents pour les spawners et
   l'économie de la boutique.

---

## 7. Changements de comportement

`/back` et `/near` étaient ouverts à tous (aucune permission dans `plugin.yml`)
alors que la boutique les vendait. Ils exigent désormais `redconflict.back` et
`redconflict.near`. Pour les rouvrir à tous, retirer la ligne `permission:` de la
commande concernée dans `plugin.yml`, ou :

```
lp group default permission set redconflict.back true
lp group default permission set redconflict.near true
```
