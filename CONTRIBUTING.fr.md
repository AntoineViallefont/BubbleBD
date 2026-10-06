# Contribuer à BubbleBD

Les retours de lecture comptent autant que les contributions au code.

## Signaler un problème

Ouvrez [un signalement](https://github.com/AntoineViallefont/BubbleBD/issues/new?template=bug-fr.yml). Indiquez le téléphone, la version Android, la version de BubbleBD, les étapes et ce que vous attendiez. Pour un problème de découpage, précisez si la lecture en page entière fonctionne.

Les signalements sont publics. Ne joignez pas de BD complète, de clé, de compte ou d'information personnelle. Une capture n'est utile que si vous avez le droit de la partager. Décrivez sinon la disposition des cases et les gestes effectués. Un compte GitHub gratuit est nécessaire pour participer.

## Proposer une amélioration

Utilisez [le formulaire de suggestion](https://github.com/AntoineViallefont/BubbleBD/issues/new?template=feature-fr.yml). Décrivez d'abord le besoin rencontré pendant la lecture. Les propositions de corrections bibliographiques restent des suggestions : il n'existe pas encore de contribution directe des lecteurs à une base de fiches modifiable.

## Contribuer au code

1. Discutez d'une modification importante dans un ticket avant de l'implémenter.
2. Créez un fork et une branche dédiée.
3. Préservez la lecture locale, l'absence de compte BubbleBD, la progression et les fichiers originaux.
4. Décrivez le comportement avant/après et les vérifications exécutées dans votre pull request.
5. Contribuez sous AGPL-3.0-or-later, en respectant les licences des composants tiers.

Les règles de détection doivent rester générales : aucune exception par titre, nom de fichier ou page. Ne modifiez pas des références ou des assertions pour dissimuler une régression.

## Tests

`./scripts/build.sh` compile l'app, lance les tests JVM et lint. `./scripts/build.sh --compile-only` compile sans tests. Android Studio et le SDK/NDK indiqués dans le README sont nécessaires.

Les tests Android passent par `./scripts/test-device.sh`, uniquement sur l'AVD dédié `BubbleBD_Test_API_36_1`, port 5580. Ce script peut réinitialiser les données de l'app de test. Ne l'utilisez pas sur un téléphone personnel.

Le code des tests est ouvert. Les planches privées ne sont pas redistribuées : certains tests sont ignorés ou ne peuvent pas être exécutés sans ces données. Une compilation publique ou un compte de cases concordant ne constitue pas une validation complète du lecteur. Le mainteneur exécute la régression privée avant de retenir une évolution du moteur.

Tests indépendants du service bibliographique :

```sh
python3 -m unittest discover -s services/metadata -v
node --test services/metadata/worker/test-worker.mjs
```

Node 22.13 ou supérieur avec `node:sqlite` est nécessaire. Aucun appel Mistral réel ni paiement n'est nécessaire pour ces tests hors ligne.

## Traductions

Les textes d’interface sont centralisés dans `app/src/main/java/fr/bubblebd/UiCatalog.kt`. Les titres, résumés, noms de fichiers et clés de préférences restent inchangés. Conserver les paramètres `{0}`, `{1}`, etc. L’application suit la langue du téléphone (français ou anglais ; anglais pour les autres langues). Sur Android 13+, la langue peut aussi être choisie dans les paramètres Android de BubbleBD.
