# Cloudflare Workers Free — service actif

Déployé le 03/10/2026 après accord explicite d’Antoine sur le compte, les conditions, le secret Mistral et la distribution de test. Workers Free et D1 en Europe, aucune carte ni facturation activée. Adresse publique : https://bubblebd-metadata.antoine-viallefont.workers.dev. Les limites gratuites interrompent les nouvelles recherches ; aucun passage automatique à une offre payante.

API identique au service Python : `POST /v1/books`, `GET /health`, `GET /source`. Secret `MISTRAL_API_KEY` exclusivement dans le Worker. Production 0.3.19 : indices bibliographiques connus, puis texte/miniature de couverture après absence de résultat ; aucune page de lecture, progression, identité d’appareil ou de lecteur. Miniature non conservée dans D1. Pas de journalisation applicative. Cloudflare traite nécessairement la connexion réseau ; cela ne constitue pas une promesse d’absence de journaux de plateforme.

D1 : fiches communes trouvées sans expiration ni plafond arbitraire. Les non-correspondances restent un jour, avec 2 000 entrées temporaires maximum. Limites physiques du plan gratuit toujours applicables (500 Mo par base). Migration des fiches anciennes conservées : `migrate-durable.sql`. La table `lookup_locks` évite les recherches simultanées sur le même album (bail de 90 s, libéré après réponse/échec). Compteur atomique commun à tous les clients et conservé au remplacement du Worker : 100 recherches/jour, espacement minimal 10 s, échecs compris. Une remise en forme sans nouvelle recherche est permise par consultation, uniquement avec les sources effectivement retournées. Au maximum deux appels de recherche/mise en forme Mistral, plus une transcription de couverture lorsque nécessaire dans la 0.3.19 ; délais bornés, aucun nouvel essai en boucle. Les réponses erronées ne sont pas importées. Le quota de 100 recherches n’est pas une garantie de 10 USD/mois : **le paiement Mistral désactivé** empêche les dépenses, et son crédit commun peut être épuisé avant la fin du mois. Les fiches déjà mises en cache restent accessibles si les services de cache sont disponibles.

Tests locaux (Node 22.13+ avec `node:sqlite`, Python 3) :

```sh
node --test services/metadata/worker/test-worker.mjs
python3 -m unittest discover -s services/metadata -v
python3 scripts/prepare-metadata-worker.py
cd services/metadata/worker
CLOUDFLARE_SEND_METRICS=false npx --yes wrangler@4.147.0 deploy --dry-run --config dashboard.jsonc --outdir /tmp/bubblebd-worker-build
```

Tests réels du Worker : recherche Demain T02 HTTP 200 en 11,974 s, puis même fiche du cache en 0,130 s, sources publiques dont l’éditeur Delcourt. `/health` configuré et `/source` HTTP 200. Ces deux requêtes ne prouvent pas la qualité sur toute une bibliothèque ni la tenue sous forte charge. Les erreurs de réponse ou délais Mistral restent possibles ; l’app diffère alors la recherche sans importer de fiche incorrecte. Les derniers tests locaux du code 0.3.19 passent avec 33 contrôles Python et 17 Node/SQLite.

La base `bubblebd-catalog` est liée par `DB`, le secret est chiffré côté Cloudflare, les journaux applicatifs et traces sont désactivés. Le code du dashboard est produit par `scripts/prepare-metadata-worker.py`, puis compilation sèche avec `dashboard.jsonc`; son archive AGPL est embarquée pour `/source`. Publication via le dashboard autorisé, sans token Wrangler persistant. La configuration CLI avec binding assets reste utilisable si un déploiement CLI est explicitement autorisé. Ne jamais inclure les fichiers générés, clés ou données privées dans les sources publiques.

L’app ne réclame aucun compte Cloudflare ou Mistral aux lecteurs. La file Android peut être différée par Android, le réseau ou le crédit partagé ; l’enrichissement de toute la bibliothèque n’est pas garanti immédiatement.

Sources officielles vérifiées le 03/10/2026 :

- https://developers.cloudflare.com/workers/platform/pricing/
- https://developers.cloudflare.com/d1/platform/pricing/
- https://developers.cloudflare.com/d1/worker-api/prepared-statements/
- https://developers.cloudflare.com/workers/configuration/secrets/
- https://docs.cloud.google.com/run/docs/setup


Ordre de traitement : base D1 commune avant les quotas et tout appel Mistral. Une fiche trouvée est réutilisée par tous les utilisateurs pour la même identité normalisée, même si le quota IA est épuisé. Tome et ISBN restent distincts. La conservation durable, les quotas et la concurrence sont couverts par les tests Node/SQLite. Export SQL Cloudflare et conversion CSV/restauration avec `../catalog_backup.py` ; aucune route publique d’administration.

Déploiement durable : créer `lookup_locks` d’abord, déployer le nouveau Worker, puis mettre `expires=0` pour les fiches trouvées. Ne pas effectuer cette dernière étape avec l’ancien Worker, qui supprimait les entrées expirées. Sauvegarder la table cache auparavant. La restauration préparée préserve les fiches déjà présentes et les compteurs de quota ; elle peut remplacer une ancienne non-correspondance par une fiche sourcée.

## 0.3.19 — service activé le 03/10/2026

Contrat enrichi, indices de couverture puis vérification web, cache des anciens positifs préservé. Taille JSON 230 ko, JPEG uniquement (160 ko décodé, 1 024 px maximum accepté), aucun téléchargement d’URL d’image fournie. Aucune image en base ou logs applicatifs. Les images passent temporairement par Cloudflare/Mistral ; `store=false` pour les Conversations ne couvre pas tous les journaux de plateforme. Miniature jamais incluse dans le prompt textuel web ni dans les fiches enregistrées.

La reconnaissance réelle de couverture a fonctionné mais comporte un titre mal lu ; la recherche web suivante est bloquée HTTP 429 « web_search rate limit reached. ». Aucun résultat complet réel par couverture annoncé. Le plafond interne de 100 recherches/jour ne remplace pas les limites Mistral. `provider_quota` devient HTTP 429 pour affichage Android et reprise différée. Le cache reste consulté avant toute IA. Voir [contrat et essais](../README.md).

Le déploiement coordonne Worker et APK enrichi : l’ancien Worker refusait ces nouveaux champs. Le contrat enrichi a été vérifié HTTP 200 sur la fiche Demain T02 du cache, sans nouvel appel IA. La disponibilité réelle du repli par couverture reste limitée par le quota Mistral.

## 0.3.21 — sources élargies

Les sources citées Wikipédia, Bedetheque, BDThèque et Amazon sont acceptées ; l’exclusion historique de BDThèque est retirée. Les citations doivent provenir de l’outil de recherche terminé, l’identité du tome/ISBN reste vérifiée, les URL avec identifiants ou HTTP sont refusées. Prompt et tests actualisés. Déployer cet adaptateur avec l’APK 0.3.21 pour appliquer la même politique de sources des deux côtés.

Contrôles locaux 0.3.21 : 34 Python et 18 Node/SQLite réussis ; aucun appel IA réel nouveau pour ces sites pendant les tests.
