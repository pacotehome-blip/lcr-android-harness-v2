# Synchronisation des imprimantes — service d'impression ↔ Filgo-Registre ↔ Dataverse

État au 29 oct 2026. Ce qui est fait est marqué ✅, ce qui reste à définir est marqué ⏳.

## 1. Données portées par une ligne (une ligne = une citerne + un format)

| Champ | Service d'impression (`truck_printer`) | Filgo-Registre (`imprimante_citerne`) | Dataverse |
|---|---|---|---|
| Citerne / camion | `truckId` | `truck_id` (+ `truck_key` = majuscules) | ⏳ lookup vers la citerne |
| Format | `format` (GRAND / PETIT) | `format` | ⏳ |
| Compagnie | `company` (TEG_PROPANE, TEG_PETROLE, AUTRE) | `company` | ⏳ |
| Pilote | `driverType` (nom d'enum) | `driver_type` | ⏳ |
| BT-MAC / IP | `target` | `bt_mac` | ⏳ |
| Nom | `printerName` | `printer_name` | ⏳ |
| Version Dataverse | `baseVersion` | `dataverse_version` | `versionnumber` |
| Modifiée à la main | `dirty` | — | — |

Clé unique : (citerne, format). Une citerne peut avoir une imprimante GRAND et une PETIT.

Format : déduit du pilote, jamais saisi — `BROTHER_PJ_862` = GRAND, tous les autres = PETIT.

## 2. Sens des échanges

1. ✅ Filgo-Registre → service : `POST http://127.0.0.1:9182/truck-printer`, en-tête `X-App-Token`.
   Corps : `truckId, company, driverType, target, printerName, format` (+ `ack:true` pour confirmer).
   Réponse 409 `LOCAL_DIRTY` si le service a une ligne modifiée à la main : on demande à l'usager, puis on renvoie avec `ack:true`.
2. ✅ Le service apparie l'imprimante Bluetooth (`createBond`) à partir de la MAC si elle n'est pas déjà appairée.
3. ✅ Dataverse → Filgo-Registre : la citerne d'un registre se lit dans `filgo_registrecompteurs.filgo_actifciterne` (lookup, lu via `_filgo_actifciterne_value` + valeur formatée).
4. ⏳ Filgo-Registre → Dataverse : push des lignes `imprimante_citerne` vers `filgo_print_printers` (table pas encore créée par Jacques). Les colonnes `dataverse_id` / `dataverse_version` sont réservées dans la table locale.
5. ⏳ Dataverse → service (récupération au démarrage) : même corps que le point 1, avec `baseVersion`.

## 3. Points à valider avec Jacques

- La colonne citerne du registre (`filgo_actifciterne`) est vide dans l'export fourni pour les 5 registres ; la lecture est prête, mais les citernes doivent être renseignées.
- Pour les camions TEG (pas de registre) : la citerne est saisie à la main (ligne marquée « saisie manuelle »).
- Identifiant de citerne utilisé comme `truckId` : le libellé de la citerne. À confirmer que c'est bien celui que le deeplink d'impression transmet.
- Colonnes de `filgo_print_printers`, clé de dédoublonnage (citerne + format) et règle de conflit.
