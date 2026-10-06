# Construire et publier une version

## Installation de développement

Installer Android Studio avec JDK 17 ou supérieur, Android SDK 36, build-tools 36.0.0, NDK 28.2.13676358 et CMake 3.22.1. Créer `local.properties` à partir de `local.properties.example` et renseigner le chemin du SDK. Gradle et CMake téléchargent leurs dépendances lors de la première compilation.

```sh
git clone https://github.com/AntoineViallefont/BubbleBD.git
cd BubbleBD
cp local.properties.example local.properties
./scripts/build.sh
```

Sous macOS, le chemin habituel du SDK est `/Users/VOTRE_NOM/Library/Android/sdk`. Corriger `sdk.dir` dans `local.properties` avant de compiler. L'APK de développement se trouve dans `app/build/outputs/apk/debug/app-debug.apk`. Ne pas le confondre avec la bêta signée publiée dans Releases.

Pour mettre à jour le code sans modifications locales :

```sh
git pull --ff-only
./scripts/build.sh
```

## Signature de publication

Les publications officielles utilisent une clé distincte de la clé Android Debug. La clé et son mot de passe restent hors du dépôt, dans `~/.config/bubblebd/signing/` sur le poste du mainteneur. Ce dossier contient `release.p12` (alias `bubblebd`) et `password`. Faire une sauvegarde chiffrée séparée et conserver la même clé pour les mises à jour. Ne jamais envoyer ce dossier à GitHub.

Pour un fork, générer sa propre clé dans un dossier privé, puis renseigner `BUBBLEBD_SIGNING_DIR`. Ne pas chercher à remplacer l'application officielle avec une autre signature.

```sh
./scripts/build.sh --release
```

Ce mode compile la version release, exécute les tests JVM et lint release, vérifie l'alignement 16 Kio puis signe et vérifie l'APK. Il échoue si la clé privée est absente. Le script ne publie rien.

Le `versionCode` doit augmenter à chaque mise à jour publique. Mettre à jour aussi `bubbleVersion` et les noms des livrables dans les scripts concernés. Relire les permissions, la configuration réseau et la politique de confidentialité. Tester l'APK signé et une mise à jour avant de publier.

Les builds publics d'origine utilisent les identifiants publics décrits dans `public-build.properties` ; ce fichier ne contient aucune clé de fournisseur. Copier ses valeurs dans `local.properties` si l'on souhaite la même configuration. Le service commun a des quotas partagés ; pour un fork public, déployer son propre service ou laisser l'adresse vide.

## Vérifications et limites

Les contrôles Android automatisés passent exclusivement par `scripts/test-device.sh` sur l'AVD `BubbleBD_Test_API_36_1` au port 5580. Les originaux du corpus privé ne sont pas redistribuables et sont absents du dépôt. Le mainteneur conserve les preuves et exécute le corpus complet lorsqu'il modifie le moteur. Ne jamais annoncer que tous les tests passent lorsque des échecs connus restent ouverts.

La bêta 0.3.32 conserve le moteur existant. La dernière mesure privée complète portait sur 61 planches et 340 variantes ; 15 planches comportaient encore au moins un critère en échec. Ces chiffres ne sont pas un taux de réussite sur toutes les BD.

Les anciennes versions Firebase étaient signées Android Debug. Elles ne peuvent pas être remplacées directement par la bêta publique. Désinstaller ferait perdre les données locales : ne pas demander une désinstallation sans expliquer cette conséquence. Les fichiers originaux ne sont pas supprimés.
