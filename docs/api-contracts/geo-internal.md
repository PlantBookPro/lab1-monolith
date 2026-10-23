# geo-service — внутренний контракт

Потребитель (Feign): `tournaments` (`GeoClustersClient`). Контроллер:
`geo-service/.../adapter/in/web/InternalClusterController.java`.

## POST /internal/clusters/assignments

Кластеризация состава эпохи глобального турнира (ADR-012). Вызывается в
tx открытия эпохи; повтор по тому же `epochId` идемпотентен — снимки
кластеров неизменны.

Запрос:

```json
{
  "epochId": "e7b9...",
  "members": [
    { "entryId": "5b1c...", "userId": "0c9f...",
      "latitude": 55.75, "longitude": 37.61, "locationVersion": 3 }
  ]
}
```

Ответ:

```json
[
  { "snapshotId": "3a6d...", "clusterKey": "u4pr",
    "entryIds": ["5b1c...", "8f2e..."] }
]
```

Точность geohash — `plantarena.geo.geohash-precision` (по умолчанию 4).
Пустой состав — пустой список (эпоха без квалификационного окна).
