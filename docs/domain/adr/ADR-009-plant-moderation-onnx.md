# ADR-009: Автоматическая модерация — MobileNetV2 ONNX внутри монолита

Дата: 2026-09-27. Статус: принято (итерация 4).

## Контекст

Раздел 6 требований: поданная заявка «это моё растение» проходит автоматическое
распознавание; ошибка распознавателя — не решение (растение остаётся PENDING,
ничего не теряется). Решение (APPROVED/REJECTED) передаётся командой
`plants.api.PlantModeration.recordDecision` (раздел 4.3).

## Решение

1. **Классификатор — ONNX Runtime внутри монолита**: зависимость
   `com.microsoft.onnxruntime:onnxruntime`, адаптер
   `moderation.adapter.out.classifier.OnnxPlantClassifier`, модель MobileNetV2
   (ImageNet-pretrained). Порт `PlantClassifier` позволяет вынести
   inference-сервис в лабе №2, не меняя домен и application.
2. **Модель скачивается при сборке**: maven-download-plugin качает
   `mobilenetv2-12.onnx` (~13 МБ) в `target/models/mobilenetv2-1.0.onnx`;
   `failOnError=false` — offline-сборка не ломается. Отсутствие модели —
   честная незавершённость: `ClassifierUnavailableException` при classify,
   job RETRY, plant PENDING (ясное сообщение в логе и README).
3. **Порог и классы**: `plant = top-1 ∈ PLANT_CLASS_INDICES && confidence ≥ 0.35`
   (softmax top-1). ImageNet-1K не содержит отдельных классов «дерево» —
   деревья представлены плодами/семенами (жёлудь 988, конский каштан 990,
   инжир 952) и цветами/сельхозрастениями (ромашка 985, венерин башмачок 986,
   кукуруза 987/998, рапс 984 и др. — полный список в коде адаптера).
   Список классов входит в `modelVersion` (наблюдаемость изменений).
4. **Retry без лимита с капом backoff**: экспоненциальный backoff 1с → 2с →
   4с → … → кап 1ч; attempts — наблюдаемость, не лимит (раздел 6: растение
   остаётся PENDING, ничего не теряется).
5. **Транзакции (отступление от «одна tx — один агрегат», раздел 12)**:
   захват due-задания — короткая tx (claim + save, конкурентный захват ловит
   @Version); инференс вне tx; применение — одна tx: сначала
   `PlantModeration.recordDecision` (обязательное обновление заявки), затем
   job DONE («не ставь DONE до обязательного обновления заявки», раздел 14).
   В лабе №2 применение становится saga/компенсацией между сервисами.
6. **Устаревший результат**: конфликт решения
   (`ModerationAlreadyDecidedException` → `DecisionConflictException`) —
   job DONE с reasonCode=STALE; повтор того же решения — no-op (идемпотентность
   plants). Новая заявка (переотправка) — новый Plant с новым id.
7. **Inference-демо**: тег `inference` у `OnnxPlantClassifierIT`; в обычный
   `verify` не входит (failsafe excludedGroups), запуск `./mvnw verify -P
   inference`; без модели — честный skip (assumption), не ошибка.
8. **Сессия ONNX на каждый вызов**: нагрузка лабы №1 мизерная, состояние не
   держим; пул сессий — при выносе inference-сервиса (лаба №2).

## Последствия

- Байты изображения: контракт `media.api.MediaAssets.loadContent` (mimeType, не
  доменный ImageFormat — api не зависит от domain); storageKey не пересекает
  границу media. Права не проверяются: внутренний вызов монолита по assetId
  из задания.
- `adapter.in.events` признан ACL-местом в ContextBoundaryTest (правило уже
  было записано в context-map.md; слушатель PlantSubmittedHandler знает
  plants.api и moderation.application).
- Тесты/демо без модели: `DeterministicPlantClassifier` (зелёное изображение —
  растение) через @TestConfiguration в ModerationApiIT.
- Не входит (другие итерации/лабы): перевод invitation в tournaments при
  решении — итерация 5; уведомления — лаба №4; inference-сервис — лаба №2.
