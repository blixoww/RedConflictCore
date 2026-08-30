# Anti-triche — ce qu'il sait faire, et jusqu'où on peut le laisser sanctionner

Tout ce qui est décrit ici vit dans `fr.redconflict.anticheat` et se règle sous
`anticheat:` dans `config.yml`, dont les commentaires restent la référence pour
chaque paramètre. Ce document répond à la question que la configuration ne peut
pas trancher seule : **lesquels de ces contrôles peut-on brancher sur une
sanction automatique, et lesquels ne doivent jamais l'être.**

---

## 1. La seule garantie qui tienne

Ces contrôles s'exécutent sur le **serveur** et jugent des faits que le serveur
mesure lui-même : une distance, une cadence, un angle, une proportion. Un client
modifié n'a aucune prise dessus — au mieux il reste sous les seuils, et rester
sous les seuils, c'est jouer normalement.

Tout ce qu'on place **dans le client** tourne sur la machine du joueur, qui
possède le processus, le disque et le débogueur. C'est un ralentisseur utile
contre les outils tout faits, jamais une barrière. Ne jamais faire reposer une
sanction sur la seule parole du client.

---

## 2. Le compteur, et pourquoi il existe

Chaque violation ajoute 1 au compteur du contrôle pour ce joueur. Le compteur
perd un point toutes les `decay-seconds` (**20 s**). L'action ne se déclenche
qu'au franchissement de `threshold`.

C'est ce qui distingue un pic de latence d'une triche : le premier arrive seul,
la seconde se répète. **C'est aussi le « kick au bout d'un moment »** — il n'y a
pas d'autre minuterie à régler.

Trois actions possibles, sous `anticheat.<contrôle>.action` :

| Action | Effet |
|---|---|
| `alert` | prévient le staff en jeu et écrit en console. Par défaut. |
| `kick` | expulse. Le joueur revient en trois secondes. |
| `command` | exécute `anticheat.<contrôle>.command` en console, `%player%` substitué. |

`anticheat.action` fixe le défaut global (`alert`).

> **Le kick est la réponse la plus faible qui soit.** Pour qu'une sanction coûte
> quelque chose, passer par `command` et le système de bans :
>
> ```yaml
>     action: command
>     command: "ban %player% perm Killaura"
> ```
>
> Grammaire : `/ban <joueur> <durée|perm> <raison>` — durées `1m 1h 1j 7j 30j perm`.

---

## 3. Deux familles, et c'est tout ce qui compte

### Statistiques — un seuil, donc un compromis

Ils mesurent une **quantité** et la comparent à une limite. Cette limite doit
être assez large pour épargner le joueur à 250 ms de latence, donc assez large
pour qu'une triche réglée juste en dessous passe. On discute toujours d'un
chiffre, et le tricheur discute avec nous.

**Ne jamais les mettre en sanction automatique tant qu'on ne les a pas observés
plusieurs jours sur ce serveur, avec sa latence et ses habitudes.**

| Contrôle | Seuil | Ce qu'il mesure |
|---|---|---|
| `speed` | 12 | déplacement horizontal soutenu au-delà du permis |
| `timer` | 10 | cadence des paquets de mouvement (boucle client accélérée) |
| `fly` | 6 | altitude tenue ou gagnée sans support |
| `nofall` | 8 | le client prétend toucher le sol pour annuler les dégâts |
| `reach` | 8 | allonge, **compensée en latence** (voir §4) |
| `autoclick` | 10 | régularité des clics (voir §4) |
| `through-wall` | 10 | coup porté à travers un bloc plein |
| `nuker` | 8 | cadence et distance de minage |
| `xray` | 1 | proportion de minerais rares — **ouvre une enquête, ne la conclut pas** |

### Catégoriels — une impossibilité, donc une preuve

Ils ne mesurent rien. Ils constatent qu'un paquet a été produit que la boucle de
jeu vanilla **ne peut pas** produire. Pas de seuil à régler, pas de latence à
pardonner, pas de faux positif à craindre.

| Contrôle | Seuil | Ce qu'il prouve |
|---|---|---|
| `honeypot` | **1** | une entité hors du champ de vision a été frappée (voir §4) |
| `aim` | 8 | coup porté sur une cible hors de l'axe du regard |
| `no-swing` | 12 | attaque sans animation de bras |
| `multi-aura` | 4 | plusieurs joueurs distincts touchés en quelques ticks |
| `client-injection` | 1 | code chargé hors du jar officiel, ou agent d'instrumentation |

**Ce sont ceux-là qu'on peut escalader.** Recommandation, une fois chacun observé
au moins une fois sur quelqu'un dont vous êtes sûr :

```yaml
  honeypot:
    action: command
    command: "ban %player% perm Killaura"
  aim:
    action: command
    command: "ban %player% 7j Aim"
  no-swing:
    action: kick
  multi-aura:
    action: kick
```

`client-injection` est déjà en `kick` — c'est aujourd'hui la seule sanction
automatique active.

> **`client-report` et `attestation` restent en `alert`, définitivement.**
> Ils reposent sur ce que le client déclare de lui-même. Un client qui fait taire
> son propre rapport n'apparaît jamais, et `client-report` se lève chez des
> joueurs légitimes (poignée de main du launcher absente). Les sanctionner
> punirait les honnêtes sans gêner les autres.

---

## 4. Les trois contrôles récents, et ce qu'ils changent

### `reach` — compensation de latence (`PositionHistory`)

Avant : plafond gonflé à 4,2 blocs « pour le ping », accordé à **tout le monde
en permanence**. Ce mou de 1,2 bloc était le budget du tricheur : une aura réglée
sur 4,1 passait pour toujours, y compris chez un joueur à 15 ms qui n'en avait
aucun besoin.

Maintenant : la position de chaque joueur est enregistrée à chaque tick. À la
réception d'un coup, on cherche la position de la cible **la plus favorable à
l'attaquant** dans la fenêtre que sa latence rend plausible (`ping +
latency-margin-ms`). Si même la version la plus généreuse des faits dépasse le
plafond, aucun ping ne l'explique.

La latence est ainsi payée à son coût réel, joueur par joueur, et le plafond
redescend à **3,25** — proche du vanilla.

```yaml
  reach:
    max-blocks: 3.25
    latency-margin-ms: 150
```

### `autoclick` — régularité (`ClickPattern`)

Les autres contrôles mesurent *combien*. Un tricheur soigneux lit le seuil et se
règle en dessous. Celui-ci mesure **la manière**.

Une main humaine ne produit jamais deux intervalles identiques : tremblement,
fatigue et respiration donnent un coefficient de variation de 15 à 40 %. Un
automate reste sous 10 %, et retombe sur les mêmes valeurs entières.
**Ralentir ne l'aide pas** : il devient un automate lent. Pour passer, il faut
écrire un générateur de bruit crédible — un autre métier que baisser un chiffre.

Deux indices requis **ensemble** (l'un seul remonterait un joueur en pleine série
rapide) : CV sous `max-cv`, et plus de `max-repeats` intervalles rigoureusement
identiques. Minimum `min-samples` intervalles avant tout verdict, fenêtre purgée
après 1,2 s sans coup.

```yaml
  autoclick:
    max-cv: 0.10
    max-repeats: 6
    min-samples: 20
```

### `honeypot` — l'entité fantôme

Le seul contrôle qui produise une **preuve**. Quand un joueur a frappé au corps à
corps dans les `engage-window-seconds`, on dépose toutes les
`interval-seconds` une entité **invisible dans son dos**, à `spawn-distance`
blocs, à hauteur d'yeux, pour `lifetime-ms`.

Le client vanilla choisit sa cible en lançant un rayon **droit devant lui**
(`EntityRenderer.getMouseOver`). Une entité située derrière n'est jamais
candidate, quelle que soit la latence. Si le paquet d'attaque la nomme, il a été
fabriqué.

Quatre protections pour le joueur honnête :

1. le fantôme n'appartient qu'à un joueur — un coup venu d'ailleurs est ignoré ;
2. l'angle est vérifié **au moment du coup**, pas à la pose : se retourner ne
   déclenche rien ;
3. l'angle est mesuré vers le **point de la boîte le plus favorable au joueur**,
   comme `PositionHistory` pour l'allonge — on affirme qu'*aucun* point de la
   cible n'était devant lui ;
4. le fantôme ne rend ni ne reçoit aucun dégât.

```yaml
  honeypot:
    enabled: true
    entity-type: ARMOR_STAND   # silencieux, ininflammable, sans IA ni butin
    engage-window-seconds: 20
    interval-seconds: 30
    spawn-distance: 2.6
    lifetime-ms: 1500
    min-angle-degrees: 100.0
    disabled-worlds: []
    threshold: 1
```

**Limite connue** : certaines auras filtrent les porte-armures et ne mordront
pas. `entity-type: ZOMBIE` élargit le filet — le code le rend invisible,
l'empêche de brûler, de cibler et de frapper, mais il émet des sons d'ambiance.
Un fantôme à forme de **joueur** serait le filet le plus large ; il demande de
fabriquer les paquets `PlayerInfo` + `NamedEntitySpawn` en NMS.

**Réglages vérifiés par simulation** (40 000 tirages) : couverture 98,6 % sur une
distribution de visée réaliste ; en se retournant vers le fantôme, l'angle mesuré
ne dépasse jamais 85°, soit 15° de marge sous le seuil. La hauteur de pose
(`HEIGHT_OFFSET = 0.6`) est ce qui fait passer la couverture de 61 % à 98,6 % :
posée au sol, la boîte tombait sous la ligne des yeux et l'angle s'effondrait dès
que le joueur regardait vers le bas.

---

## 5. Prévenir plutôt que détecter — et ce qui est éteint

### `visibility` — masquage anti-ESP · **DÉSACTIVÉ AUJOURD'HUI**

```yaml
  visibility:
    enabled: false      # <- état actuel
```

C'est la meilleure pièce du dispositif, et elle ne sert à rien tant qu'elle est
sur `false`. Ne pas envoyer la position d'un joueur derrière un mur, c'est de la
**prévention** : aucun ESP ne peut afficher une donnée qu'il n'a jamais reçue.
Tout le reste de ce document ne fait que détecter *après coup*.

À activer et à observer — le risque est le sens inverse (un joueur légitime à qui
l'on cache brièvement un adversaire qu'il aurait dû voir), d'où l'inertie
intégrée dans `VisibilityCulling`.

### La chaîne HWID n'est pas fermée

Un ban permanent de compte se contourne avec un compte alt. Ce qui le rend
réellement permanent, c'est le ban matériel — et il dépend de trois maillons :

| Maillon | Réglage | État |
|---|---|---|
| 1. Le joueur doit passer par votre client | `anticheat.attestation.enabled` | **`false`** |
| 2. …sinon il est expulsé | `anticheat.attestation.action` | `alert` |
| 3. Le client rapporte son empreinte, le serveur refuse les récidives | `anticheat.ban.hwid.enabled` | **`false`** |

Tant que 1 et 2 sont ouverts, un joueur en client vanilla + injecteur n'envoie
aucune empreinte, et le ban HWID ne le voit jamais. L'ordre à suivre est
1 → 2 → 3 ; inverser ne sert à rien.

Une fois `ban.hwid.enabled` à `true`, `HwidBanService` refuse à la connexion tout
compte partageant au moins `threshold` (4) points de matériel avec un compte au
ban actif, en réaffichant l'écran de ban d'origine. Les machines virtuelles sont
refusées séparément (`block-vms`), parce que c'est le contournement le plus
propre du procédé.

---

## 6. Exploitation

```yaml
anticheat:
  enabled: true
  action: alert          # défaut global
  decay-seconds: 20
  debug: false           # true = une ligne console à CHAQUE violation
```

- **Exemption** : la permission `redconflict.anticheat.bypass` désactive tous les
  contrôles pour un joueur. Le mode créatif et le spectateur exemptent
  automatiquement `speed`, `fly` et `nofall`.
- **Mettre au point un seuil** : passer `debug: true`, jouer normalement une
  soirée, relever les `vl=` qui montent sans triche. Le seuil doit être
  nettement au-dessus du bruit observé.
- **Ne jamais dire au tricheur ce qui l'a trahi.** `attestation` est écrit pour
  ne jamais répondre « ta réponse est fausse » : sinon il itère jusqu'à trouver.
  Même principe pour les messages de kick, volontairement vagues.

---

## 7. Ce qui reste ouvert

- `visibility` et la chaîne HWID à activer (§5) — c'est le plus gros gain
  disponible, et il ne demande aucun code.
- Le fantôme à forme de joueur, si l'armor stand ne mord pas.
- **Rien de tout ceci n'a été vérifié en jeu.** Les réglages du honeypot sont
  validés par simulation géométrique, pas par une partie réelle.
