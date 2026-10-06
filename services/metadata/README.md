# Recherche bibliographique commune

Service 0.3.19 actif : Cloudflare Workers Free et D1 Europe. Distribution Android coordonnée et vérifiée par les scripts du projet. Mistral Conversations/web_search recherche les fiches absentes ; D1 conserve les fiches sourcées sans expiration. Aucun compte lecteur, clé dans l’APK ou paiement automatique. Clé du propriétaire uniquement côté service. Les recherches réelles Demain T02 et Vertigéo ont réussi en production ; cela ne valide pas toute une bibliothèque.

## 0.3.19 — service activé le 03/10/2026

La fiche affiche l’état et la date du dernier résultat complet, sans assimiler une interruption à une absence de résultat. Une nouvelle tentative peut être différée par le réseau, Android ou les quotas gratuits. Les données déjà saisies, les originaux et la progression restent conservés.

`POST /v1/books` accepte les champs bibliographiques connus : titre, série, numéro, ISBN, artist/writer/publisher, date, genre, synopsis et knownSources. Chemins privés, compte, identifiant d’appareil et progression sont refusés. Le corps JSON est limité à 230 000 octets. Titres/tomes/ISBN et sources web sont vérifiés ; aucune note inventée. Les restrictions historiques de domaine sont retirées dans la version 0.3.21 : Wikipédia, Bedetheque, BDThèque et Amazon sont acceptés comme sources citées.

Une pause suspendue de onze secondes avant le repli respecte l’espacement minimal du service, sans bloquer l’interface. Elle n’a lieu que lorsqu’un indice de couverture est disponible. Après une recherche complète sans correspondance, le client tente la lecture locale de couverture, puis transmet `coverText` (1 800 caractères maximum) et/ou `coverImage` (JPEG base64, 160 ko décodé, au plus 768 px produit côté Android, 1 024 px accepté côté serveur). Seule cette miniature sort de l’app, pas le PDF ni les pages de lecture. Aucune miniature conservée dans la base ou les logs applicatifs. Cloudflare et Mistral traitent temporairement son contenu ; les journaux de plateforme ne sont pas contrôlés par l’app.

Mistral Conversations sans outil (`store=false`, JSON Schema) transcrit les indices de couverture. Recherche textuelle web distincte les vérifie, avec citations issues de `web_search`. Au moins deux indices indépendants concordants ou un ISBN exact sont nécessaires ; tome/ISBN connus restent obligatoires. Un mot mal lu ne doit pas forcer une fiche incorrecte. Les indices seuls ne constituent pas une fiche. Aucun Google Lens automatique ni moteur d’indexation inverse d’images intégré.

Cache : identité principale inchangée pour réutiliser les fiches existantes ; contexte enrichi séparé pour relancer une ancienne absence. Une fiche positive compatible est consultée avant tout quota/appel IA. Aliases positifs uniquement si sûrs ; une couverture reconnue depuis un nom de fichier générique n’est pas associée universellement à ce nom. Réponses négatives : un jour côté serveur (2 000 maximum), sept jours côté Android sauf nouveaux indices. Les fiches positives côté Android sont revisitées après trente jours, servies par la base commune quand disponibles.

Le service renvoie HTTP 429 avec `provider_quota`, `quota`, `pace` ou `album_busy` ; autres défauts HTTP 503. L’app indique « Quota gratuit atteint · recherche différée » pour une limite gratuite, sans inscrire de résultat négatif. Une seule tâche Android, priorité ouvertures récentes puis imports, pauses entre albums, reprise progressive et fusion des résultats lors de l’affichage. Android peut suspendre ce travail, y compris écran éteint.

## Gratuité et essai réel

Compte Mistral gratuit du propriétaire, paiement à l’usage et recharge automatique désactivés, aucune carte ajoutée. Limite interne partagée 100 nouvelles recherches/jour, au moins 10 s entre elles ; limites Mistral indépendantes, qui peuvent être atteintes plus tôt. Les fiches en base restent réutilisables sans IA. Aucun passage payant prévu.

Essai local réel 03/10 : Conversations avec miniature fonctionne, mais lit « Demon » au lieu de « Demain » sur la couverture officielle Delcourt de Demain T02 ; auteurs, éditeur et tome lisibles. Recherche web suivante HTTP 429 « web_search rate limit reached. ». Aucun album importé à partir de cette seule transcription ; réussite complète réelle par couverture non démontrée, réinitialisation du quota inconnue. Les détails/miniatures d’essai sont privés, exclus du source public et de l’APK.

Le contrat enrichi 0.3.19 est déployé, Worker actif `0ed41752`, APK distribué 0.3.20. La version 0.3.21 élargit les sources ; son déploiement coordonne Worker et APK.

## Tests et lancement local

```sh
python3 -m unittest discover -s services/metadata -v
node --test services/metadata/worker/test-worker.mjs
python3 scripts/run-metadata-local.py
```

33 Python et 17 Node/SQLite réussis : cache durable partagé, compatibilité des indices, limites JPEG, vision suivie de vérification web simulée, sources obligatoires, absence d’image en base. Les tests hors ligne ne prouvent pas la qualité réelle de toute la bibliothèque. Le script local lit la clé privée hors projet et écoute uniquement sur 127.0.0.1. Il n’active ni facturation ni déploiement.

`metadata.endpoint=https://bubblebd-metadata.antoine-viallefont.workers.dev` dans `local.properties` ; aucune clé API dans la configuration Android. Vide : catalogues publics seulement. `GET /health` vérifie la configuration, pas le succès d’une recherche. `GET /source` fournit le source AGPL correspondant au Worker déployé. Détails : [Worker](worker/README.md).

Prototype Gemini conservé mais inactif : essais gratuits de recherche 404/429, aucune activation de facturation. Cloud Run et Hugging Face ne sont pas utilisés sous la consigne zéro paiement. Historique des essais : documentation privée du projet.

Sources officielles vérifiées lors des essais :

- https://docs.mistral.ai/api/endpoint/beta/conversations
- https://docs.mistral.ai/studio/agents/agent-tools/websearch
- https://docs.mistral.ai/admin/billing-usage/subscriptions
- https://developers.cloudflare.com/workers/platform/pricing/
- https://developers.cloudflare.com/d1/platform/pricing/
