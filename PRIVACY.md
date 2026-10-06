# Données et confidentialité

Dernière mise à jour : 6 octobre 2026 — BubbleBD 0.3.32.

BubbleBD est un projet bénévole maintenu par Antoine Viallefont. L'application ne demande pas de compte BubbleBD. Elle n'intègre ni publicité ni outil de mesure d'audience. Pour une question générale, utilisez les [tickets du projet](https://github.com/AntoineViallefont/BubbleBD/issues). Ces tickets sont publics : ne publiez pas d'informations personnelles. Pour une vulnérabilité, utilisez le signalement privé dans l'onglet Security.

## Lecture et bibliothèque

La détection des cases s'effectue sur l'appareil avec un modèle embarqué. La bibliothèque, la progression, les réglages, les fiches et leurs modifications sont conservés dans le stockage de l'application. La lecture d'un album disponible localement ne nécessite pas le service Mistral.

Les fichiers sélectionnés restent sous votre contrôle. Retirer un album de BubbleBD supprime ses copies privées et son entrée dans la bibliothèque, pas son original. Désinstaller l'application efface ses données locales : sauvegardez ce qui vous importe avant de désinstaller. Il n'existe pas de synchronisation de la bibliothèque entre appareils.

## Recherche des informations d'un album

À l'import d'un nouvel album ou lors d'une recherche demandée explicitement, BubbleBD peut consulter des catalogues et sites publics, puis le service commun hébergé chez Cloudflare utilisant Mistral. Une fiche figée est exclue des recherches. Une recherche déjà demandée peut continuer en arrière-plan.

Selon les indices disponibles, les recherches transmettent le titre, la série, le tome, l'ISBN et éventuellement les auteurs, l'éditeur, la date, le genre, un extrait du résumé, des adresses de sources, du texte extrait de la couverture et une miniature JPEG de couverture. Le contenu complet de la BD et la progression de lecture ne sont pas envoyés au service bibliographique. La détection des cases ne transmet pas les planches.

Les services contactés reçoivent les données nécessaires aux requêtes et les informations techniques de connexion, notamment l'adresse IP de leur interlocuteur. Les images distantes peuvent être téléchargées pour afficher les couvertures.

Le service partagé conserve les fiches trouvées et leurs sources pour les réutiliser lors de recherches équivalentes. Ces fiches positives n'ont pas de date d'expiration automatique dans la configuration actuelle ; les réponses négatives sont mises en cache un jour. La couverture envoyée n'est pas enregistrée dans la base D1. Aucune modification personnelle de fiche n'est synchronisée comme contribution communautaire. Une valeur saisie manuellement peut toutefois être utilisée comme indice lors d'une nouvelle recherche : n'insérez pas d'information personnelle dans les champs d'un album.

Les journaux applicatifs du service sont désactivés. Cela ne constitue pas une garantie d'absence de journaux ou de conservation par Cloudflare, Mistral, Microsoft ou les catalogues consultés. Les conditions de ces fournisseurs s'appliquent. Le statut d'exclusion des données de l'entraînement dans le compte Mistral n'a pas été vérifié ; l'offre gratuite peut utiliser les entrées/sorties pour améliorer ses modèles. N'utilisez pas la recherche bibliographique avec des contenus confidentiels.

- [Confidentialité Mistral](https://legal.mistral.ai/terms/privacy-policy)
- [Utilisation des données par Mistral](https://help.mistral.ai/en/articles/347617-do-you-use-my-user-data-to-train-your-artificial-intelligence-models)
- [Confidentialité Cloudflare](https://www.cloudflare.com/privacypolicy/)
- [Confidentialité Microsoft](https://privacy.microsoft.com/privacystatement)

Les catalogues disponibles et leurs adresses sont visibles dans le code et les sources des fiches. Le réseau, les quotas et l'exactitude des résultats restent des limites de la bêta. La recherche Mistral est gratuite pour les lecteurs mais n'est pas garantie illimitée.

## OneDrive facultatif

La connexion OneDrive passe par Microsoft avec votre accord. Elle permet de consulter les fichiers autorisés et de télécharger les albums pour les lire ; elle ne crée pas de compte BubbleBD. Les jetons sont conservés dans l'espace privé de l'application. Vous pouvez révoquer l'accès depuis votre compte Microsoft.

## GitHub et volontaires

Le code, les tickets et les inscriptions de volontaires sur GitHub sont publics et hébergés par GitHub. Votre pseudonyme et ce que vous y écrivez sont visibles. Aucun e-mail Google n'est nécessaire pour se porter volontaire maintenant. Ne joignez pas de BD complète, d'identifiant ou de document privé. Si un test Google Play est organisé, un mode d'inscription adapté sera annoncé séparément. Aucune liste d'adresses Google n'est collectée par ce dépôt.

Pour éviter le service partagé dans une compilation personnelle, laissez `metadata.endpoint` vide. Cela ne désactive pas les consultations directes des catalogues ; une lecture sans accès réseau s'effectue avec les albums déjà disponibles localement.
