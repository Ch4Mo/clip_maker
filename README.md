# ClipMaker

Application Android de création de **clips vidéo et musicaux**, de A à Z : on filme ou on importe, on monte sur le rythme, on enregistre ses voix et ses sons, on ajoute effets, transitions et titres, puis on exporte jusqu'en 4K.

## Inspirations : le meilleur de chaque application

| Application | Ce qu'on reprend dans ClipMaker |
|---|---|
| **CapCut** | Timeline avec tête de lecture fixe au centre, piste principale magnétique, barre d'outils contextuelle, texte animé, montage automatique sur le beat |
| **KineMaster / LumaFusion** | Vraies pistes multiples (vidéo, incrustations, audio, texte), images clés sur position/échelle/rotation/opacité/volume, fond vert, verrouillage et mute par piste |
| **VN / PowerDirector** | Vitesse de 0,1x à 10x, préréglages ralenti/timelapse, filtres façon pellicule, réglages couleur complets |
| **BandLab / FL Studio Mobile** | Studio d'enregistrement avec métronome, décompte et écoute du projet pendant la prise, banque de prises, effets audio (EQ, compresseur, réverb, écho, pitch...) |
| **TikTok / Reels** | Caméra intégrée en mode play-back : la musique du projet joue pendant qu'on filme et le plan tombe pile à la bonne place |

## Fonctionnalités

**Projets** : création avec format (16:9, 9:16, 1:1, 4:5, 4:3, 2.39:1, 21:9), images par seconde (24 à 60) et résolution (720p à 4K). Sauvegarde automatique, annuler/rétablir illimité, duplication.

**Montage**
- Timeline multi-pistes : vidéo principale magnétique, incrustations (PiP), pistes audio, pistes texte
- Pincer pour zoomer, glisser pour naviguer, appui long pour déplacer un clip (y compris vers une autre piste), poignées pour rogner
- Calage magnétique sur les bords des clips, la tête de lecture, les marqueurs et les temps musicaux
- Couper, supprimer, dupliquer, vitesse, séparer l'audio, marqueurs

**Rythme / beat sync**
- Détection automatique du tempo (BPM) et des temps forts de la musique
- Tap tempo manuel
- « Couper sur les temps » (tous les 1, 2, 4 ou 8 temps)
- « Montage auto » : répartit vos vidéos et photos sur la musique, un plan tous les N temps
- Effet « Pulsation » qui fait zoomer l'image sur chaque temps

**Vidéo**
- 14 filtres (Cinéma, Teal & Orange, Vintage, Noir, Kodak, Cyberpunk...) avec intensité
- Étalonnage : exposition, contraste, saturation, vibrance, température, teinte, hautes lumières, ombres, délavé
- Effets : vignette, grain, flou, netteté, noir & blanc, négatif, glitch, VHS, décalage RVB, pixelisation, miroir, kaléidoscope, ondulation, secousse, stroboscope, bandes cinéma, fond vert
- Animation par images clés (position, échelle, rotation, opacité) et animations rapides (Ken Burns, zoom punch, travelling, incrustation dans un coin)
- 15 transitions (fondus, flash, zooms, rotation, flou, glitch, glissements, whip pan, secousse, RVB)

**Audio**
- Import de musique, extraction de la bande son d'une vidéo (fichier audio séparé)
- Studio : enregistrement de prises, métronome, décompte, écoute du projet, rognage automatique des silences, banque de samples à placer n'importe où
- Bibliothèque de bruitages générés dans l'app, sans droits : whoosh, riser, impact, sub drop, batterie, glitch, scratch...
- Effets : gain, égaliseur 5 bandes, passe-bas, passe-haut, compresseur, réverbération, écho, chorus, distorsion, lo-fi, noise gate, téléphone/radio, hauteur (pitch)
- Préréglages : voix claire, voix radio, grande salle, écho stade, grosse voix...
- Volume par clip avec courbe d'images clés, fondus, table de mixage par piste (volume, solo, mute, effets de piste)

**Texte** : 6 polices, taille, couleur, contour, fond, ombre, 10 animations d'entrée et de sortie (machine à écrire, pop, glitch, rebond...).

**Export** : préréglages YouTube (1080p, 4K 60 i/s), Reels/TikTok/Shorts, Instagram, Cinéma, brouillon ; H.264 ou HEVC ; enregistrement dans la galerie et partage.

## Architecture

```
core/   Kotlin pur (testé sur JVM) : modèle de projet, édition de timeline,
        historique, calage magnétique, DSP audio, détection de rythme,
        bruitages procéduraux, looks couleur (LUT 3D), plan de rendu
app/    Android : Jetpack Compose (UI), Media3 Transformer + CompositionPlayer
        (aperçu et export identiques), shaders OpenGL ES, CameraX, AudioRecord
```

- **Aperçu = export** : la même `Composition` Media3 sert à la lecture en temps réel et au rendu final.
- Les effets vidéo sont des shaders GLSL exécutés sur le GPU ; les filtres sont des LUT 3D générées à la volée.
- Les effets audio passent par le moteur DSP du module `core`, branché dans Media3 via un `AudioProcessor`.
- Le modèle est immuable : chaque modification crée un nouvel état, ce qui rend annuler/rétablir fiable.

## Compiler

Prérequis : Android Studio (Ladybug ou plus récent) ou JDK 17 + SDK Android 36.

```bash
./gradlew :core:test          # tests du moteur
./gradlew :app:assembleDebug  # APK : app/build/outputs/apk/debug/
```

La CI GitHub (`.github/workflows/android.yml`) lance les tests et produit l'APK de debug à chaque push, téléchargeable dans l'onglet *Actions*.

Android 8.0 (API 26) minimum ; Android 10 ou plus recommandé.

## Feuille de route

- Fondus enchaînés entre deux plans qui se chevauchent (les transitions actuelles se jouent sur le bord de chaque plan)
- Modes de fusion des incrustations (écran, produit, incrustation...)
- Courbes de vitesse (rampes) et lecture inversée
- Masques et détourage automatique par IA, suivi de mouvement
- Séparation voix / instrumental par IA, autotune
- Sous-titres automatiques (reconnaissance vocale)
- Stabilisation
