# RKTRL (ReKontrol) — contrôleur, séquenceur et effets MIDI pour Android

Application Android en paysage, connexion **USB-MIDI** ou **Bluetooth MIDI (BLE)**.
Esthétique Wipeout / The Designers Republic : noir, jaune acide, cyan, magenta, angles coupés.

---

## 1. Obtenir l'APK

### Option A — Android Studio (le plus simple)
1. Installer Android Studio (gratuit).
2. *File → Open* → choisir le dossier `RKTRL`.
3. Attendre la synchronisation Gradle (la première fois, quelques minutes).
4. Brancher le téléphone/tablette en USB avec le **débogage USB** activé, puis ▶ *Run*.
   Ou : *Build → Build APK(s)* → l'APK est dans `app/build/outputs/apk/debug/`.

### Option B — sans rien installer, via GitHub
1. Créer un dépôt GitHub et y envoyer le contenu du dossier `RKTRL`.
2. Onglet **Actions** → le workflow *Build APK* se lance tout seul.
3. Une fois terminé (≈ 4 min), télécharger l'artefact **RKTRL-apk**, le dézipper,
   copier `app-debug.apk` sur l'appareil et l'ouvrir (autoriser « sources inconnues »).

Android 8.0 minimum. Le Bluetooth MIDI demande l'autorisation « Appareils à proximité ».

---

## 2. Connexion

Toucher le **bandeau du haut** (zone « NO LINK ») :
- **USB / virtuels / déjà ouverts** : appareils branchés en USB-OTG ou déjà connectés.
- **Bluetooth MIDI** : `SCANNER`, puis toucher l'instrument trouvé.
  L'instrument ou l'adaptateur doit être en mode BLE-MIDI (ex. WIDI, CME, Korg microKEY Air, Roland…).

---

## 2b. Menu OPTIONS (toucher le logo RKTRL)

- **Langue** : Français, English, Aurebesh (textes anglais écrits en alphabet Star Wars ;
  les boutons de langue restent toujours lisibles pour revenir en arrière).
- **Sauvegarder…** : enregistre boutons, noms, valeurs, fader et les 16 séquences dans un fichier `.json`.
- **Charger un fichier…** : recharge une sauvegarde.

Police Aurebesh : SilvinoR, SIL Open Font License 1.1 (`app/src/main/assets/fonts`).

---

## 3. Page CONTROLLER

- **GROUP** : 1er appui = sélection multiple ; 2e appui = on sort du mode et tout est désélectionné.
- **▶ PLAY / ■ STOP** : lance ou arrête le séquenceur sans changer de page.

| Élément | Rôle |
|---|---|
| 32 boutons ronds | anneau = valeur actuelle de l'instrument, petit texte = canal/CC |
| Case sous chaque bouton | texte libre (nom du paramètre…) |
| Fader à droite | agit sur le ou les boutons sélectionnés |
| Écran d'infos | liste la sélection et l'état d'accroche |

**Accroche (pas de saut de valeur)** — tant que le fader n'a pas *croisé* la valeur réelle
de l'instrument, rien n'est envoyé.
- trait magenta sur le bouton = position du fader pour ce bouton
- ▲ / ▼ magenta = sens dans lequel bouger le fader
- ● vert = accroché, le fader pilote le paramètre

En groupe, chaque bouton s'accroche indépendamment, quand le fader passe par sa propre valeur.
Le fader se déplace en relatif : le toucher ne le fait jamais sauter, et il reste où on le laisse.

**D'où vient la « valeur réelle » ?** Si l'instrument renvoie ses CC (Local/CC Out activé),
elle se met à jour toute seule. Sinon, c'est la dernière valeur envoyée par l'appli, ou celle
saisie dans *EDIT → valeur actuelle*. Les valeurs sont gardées d'une session à l'autre.

**Boutons du bas**
- **EDIT** puis un bouton rond : nom, canal MIDI (1–16), CC (0–127, nom du CC affiché),
  plage min/max (une plage inversée max<min fonctionne aussi), valeur actuelle.
- **GROUP** : chaque bouton touché s'ajoute/se retire de la sélection. En sortant de GROUP,
  la sélection est conservée. Un toucher hors GROUP resélectionne un seul bouton.
- **UNDO** : revient aux valeurs d'avant le dernier mouvement de fader (64 niveaux).
- **SEQUENCER** : page 2.

Réglages par défaut : boutons 1–16 = volume (CC7) canaux 1–16, boutons 17–32 = cutoff (CC74) canaux 1–16.

---

## 4. Page SEQUENCER (inspirée du Zaquencer / BCR32)

- **16 pistes x 32 pas**. Bandeau des pistes : toucher = éditer la piste, **appui long = MUTE**.
  Barre sous le numéro = la piste contient des notes ; la case s'éclaire quand elle joue.
- **Toucher un pas** : active / coupe. **Glisser verticalement** : modifie la valeur.
- **NOTE / VEL / DURÉE / ACCORD** : ce que la grille édite.
  - DURÉE : 10 % → 32 pas, ou **∞** (note tenue jusqu'à la note suivante de la piste, STOP, MUTE ou KILL).
    Les pas couverts par une note longue portent une barre de liaison.
  - ACCORD : 5te, octave, majeur, mineur, sus2, sus4, maj7, m7, 7, m9, add9, dim.
- Par piste : CANAL, LONGUEUR, DIVISION, TRANSPO, MUTE.
- Global : TEMPO, SWING, HORLOGE MIDI.
- **RANDOM** : séquence aléatoire dans une gamme.
- **KILL** (rayures danger) : efface la piste en cours — deux appuis (le 1er arme, le 2e confirme).
- **AUTO** : génère une séquence par style — BASSLINE, LEAD, ACID, PAD, DRONE, ARP, TECHNO, BERLIN —
  avec fondamentale, octave et couleur mineure/majeure. Chaque appui sur GÉNÉRER = nouvelle variante.
  ACID utilise des notes qui se chevauchent pour les slides : activer le portamento/legato du synthé.
  PAD joue des accords : il faut un synthé polyphonique sur ce canal.

Notes : convention C4 = 60.

---

## 5. Structure du code (Kotlin, sans dépendance externe)

```
MainActivity.kt    pages, connexion, autorisations Bluetooth
MidiHub.kt         MIDI USB/BLE : envoi, réception des CC, scan BLE
ControllerPage.kt  32 boutons, accroche, GROUP, UNDO, EDIT
KnobView.kt        dessin d'un bouton rond
FaderView.kt       fader vertical
Sequencer.kt       moteur temps réel, gammes, random
SequencerPage.kt   interface du séquenceur
StepGridView.kt    grille de 32 pas
Widgets.kt         réglages ±, dialogues, grilles de choix
Tdr.kt / TdrButton.kt / HeaderBar.kt   identité graphique
Store.kt           sauvegarde (réglages, labels, fader, patterns)
```


---

## Nouveautés v1.2

- **Nom RKTRL** + nouveau logo. Bouton **■ STOP rouge** quand la séquence tourne.
- **Fader** : corrigé quand on le bouge vite (l'écho des CC renvoyés par l'instrument est ignoré, envoi limité en débit pour le Bluetooth).
- **OPTIONS** (logo) : 4 interfaces (Originale, Groove/SEQTRAK, Night City/Cyberpunk, Neutre 2 couleurs réglables),
  noms des 16 canaux MIDI, horloge MIDI (activée par défaut), sauvegarde complète.
- **Séquenceur** :
  - pistes à gauche (2 x 8), renommables ; réglages en une colonne à droite ;
  - jusqu'à **1024 pas** par piste (pages de 32 pas, ◀ ▶, SUIVRE) ;
  - **PAGE A / PAGE B** par piste, changement à la volée ;
  - **COPIER / COLLER** une piste ;
  - **EUCLIDIEN** (2 couches combinables OU / XOR / ET / MOINS) ;
  - **COND** par pas : probabilité (75 %, 50 %…) ou cycles (1:2, 2:4, 1ST…) ;
  - **RATCH** par pas : 1 à 8 répétitions dans le pas ;
  - **SENS DE LECTURE** : avant, arrière, aller-retour, hasard ;
  - **AUTO** : + RIFF, DJENT, EBM, SOLO.
- **DESTROY MODE** (bouton rayé du contrôleur) : pad X/Y, 9 effets combinables
  (REPEAT, STUTTER, ECHO, FILTER, PITCH, TAPE STOP, SCATTER, OCTAVER, WARP), choix des canaux visés,
  LATCH, 8 pads assignables (canal, note, vélocité), PLAY/STOP, NORMAL MODE pour revenir.
  FILTER envoie CC74/CC71 et revient aux valeurs connues du contrôleur (sinon 64) ;
  PITCH envoie du pitch bend et revient au centre.

---

## Nouveautés v1.3

- **Logo** : un seul R penché, centré.
- **Controller** : 128 boutons sur 4 pages (boutons 1 à 4 au-dessus de l'écran d'infos ; la sélection GROUP
  peut mélanger plusieurs pages). **LATCH** du fader (rouge) : allumé = le fader reste où on le lâche ;
  éteint = il revient au centre en glissant, en envoyant les valeurs intermédiaires (pas de saut).
- **Destroy Mode** : LATCH en bouton d'urgence rouge, NORMAL MODE rayé jaune/noir.
- **Séquenceur** : AUTO au-dessus d'EUCLIDIEN, RANDOM en dessous. **MUTE** en bas : allumé, toucher une piste
  la coupe / la rallume (l'appui long fonctionne toujours).
- **Options** : interface ORIGINALE ou **SOBRE** (même dessin, 3 couleurs au choix : fond, principale, accent).

---

## Nouveautés v1.4

- **Séquenceur** : AUTO, EUCLIDIEN et RANDOM se ferment après GÉNÉRER ; TRANSPO de −1024 à +1024
  (le MIDI ne compte que 128 notes : au-delà, la note reste bloquée sur la plus grave / la plus aiguë).
- **Destroy Mode** : plus de LATCH — dès qu'on lève le doigt, tout revient à l'origine (filtre, pitch, échos coupés,
  tempo recalé après TAPE STOP / WARP). Nouveau bouton **TAP BPM** (3 tapes minimum).
  Animations géométriques dans le pad X/Y (tunnel de polygones, rayons, onde au tempo), désactivables dans OPTIONS.

---

## Nouveautés v1.5

- **Stabilité Bluetooth** : tous les envois MIDI passent par une file gérée par un thread dédié. CC et pitch bend
  sont fusionnés (seule la dernière valeur part), le débit est plafonné en Bluetooth, et en cas d'embouteillage
  les nouvelles notes sont sautées (jamais les fins de notes). FILTER / PITCH n'envoient plus qu'aux canaux
  réellement utilisés, 50 fois par seconde au maximum.
- **Destroy Mode** : une animation géométrique par effet, d'une seule couleur ; bouton **MULTI** sous TAP BPM
  (sans MULTI, toucher un effet le remplace ; avec MULTI, on en combine plusieurs et les animations se
  transforment l'une en l'autre toutes les 4 temps).

---

## Nouveautés v2.0 — interface FIELD

- Nouvelle interface par défaut **FIELD** : papier, encre noire, une seule couleur d'accent ; typographie Space Mono
  (SIL Open Font License, `app/src/main/assets/fonts`). Papier et accent réglables dans OPTIONS.
  Les interfaces ORIGINALE et SOBRE restent disponibles.
- En-tête : logo `rktrl`, onglets `ctrl / 01 · seq / 02 · dstr / 03` (touchables pour changer de page), tempo, liaison.
- Boutons de valeur : courbe autour, valeur au centre. Banques A B C D. Fader avec trait d'accent.
- Boutons rayés (DESTROY MODE, KILL, NORMAL MODE, TAP BPM) : rayures accent / papier, sans contour.
- ▶ et ■ en couleur d'accent ; notes longues soulignées en accent ; pad X/Y : croix d'accent et petit point central.
- Animations FILTER et TAPE STOP centrées (v1.6).

---

## Nouveautés v2.1

- Logo RKTRL en majuscules italiques ; icône : RKTRL noir sur fond orange.
- « pas de liaison » clignote en accent tant que rien n'est connecté.
- Séquenceur : ligne des notes longues continue (par-dessus les notes suivantes, plus épaisse si plusieurs) ;
  boutons GÉNÉRER en accent ; nom des pistes = nom du canal MIDI ; sens de lecture en accent ;
  **appui long sur un pas** : mode avancé (panoramique CC10, modulation CC1, pitch bend, ACCENT, SLIDE).
- Controller : VALIDER en accent ; curseur du fader biseauté.
- Options : seule l'interface FIELD reste (couleurs FOND et ACCENT) ; libellés simplifiés.

---

## Nouveautés v2.2

- Notes longues : trait à l'intérieur de chaque case (plus épais si plusieurs notes se superposent).
- Controller : appui long sur LATCH → **FORCE** (accent) : le fader impose directement sa valeur, sans accroche.
  Appui court sur FORCE pour revenir à LATCH.
- Sécurité : import des sauvegardes limité (8 Mo, textes bornés).

### Sécurité / confidentialité
- Aucune donnée personnelle, adresse ou mot de passe dans le code.
- L'appli ne demande pas l'accès à Internet : elle ne peut rien envoyer en ligne.
- La clé de signature n'est **pas** dans le dépôt : GitHub la lit dans les secrets du dépôt
  `RKTRL_KEYSTORE_B64` (clé encodée en base64) et `RKTRL_KEYSTORE_PASSWORD`.
  Le dépôt peut donc être public.
