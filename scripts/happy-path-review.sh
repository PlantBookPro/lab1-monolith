#!/bin/bash
# ---------------------------------------------------------------------------
# Happy-path ревью API Plant Arena (лаба №1).
#
# Сквозной прогон через REST API с логированием всех запросов/ответов:
#   модератор → приватный турнир → 3 участника → глобальный турнир
#   (автоматическая ONNX-модерация) → приглашения → старт → 2 раунда
#   голосования с выбыванием → результаты.
#
# Сценарий фиксированный: 3 участника (p1, p2, p3); в раунде 1 выбывает p3,
# в раунде 2 — p2, победитель — p1. Каждому участнику нужно 2 растения
# (разные fingerprint: пара (owner, fingerprint) — один активный резерв,
# допущение 4, ADR-010): одно в глобальный турнир, другое в приватный.
#
# Выход: markdown-отчёт со всеми запросами/ответами и итоговой таблицей.
# Код возврата: 0 — все шаги успешны, 1 — есть неуспешные, 2 — ошибка окружения.
#
# Параметры (переменные окружения):
#   BASE_URL         базовый URL API (по умолч. http://localhost:8080/api/v1)
#   ADMIN_ID         UUID админа; если пуст — ищется в БД по ADMIN_EMAIL
#   ADMIN_EMAIL      email bootstrap-админа (по умолч. admin@plantarena.local)
#   DB_USER          пользователь PostgreSQL для поиска админа (postgres)
#   DB_NAME          база PostgreSQL для поиска админа (plantarena)
#   SRC_IMAGE        JPEG-источник для 6 кропов-«ромашек» (по умолчанию —
#                    эталонная daisy.jpg из тестовых ресурсов)
#   IMAGES_DIR       каталог с готовыми p1a.jpg..p3b.jpg (тогда кропы
#                    не генерируются; обход sips-зависимости)
#   OUT              файл отчёта (по умолч. docs/review/<дата>-happy-path-review.md)
#   DEADLINE_SECONDS запас до дедлайна регистрации приватного турнира (150)
#   ROUND_SECONDS    длительность раунда голосования (15)
#   MODERATION_TRIES попыток опроса модерации по 2 с (20)
#
# Требования: запущенное приложение (docker compose up), curl, jq, python3;
# для автогенерации изображений — sips (macOS) либо IMAGES_DIR с готовыми.
# ---------------------------------------------------------------------------
set -euo pipefail

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@plantarena.local}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-plantarena}"
SRC_IMAGE="${SRC_IMAGE:-$ROOT/src/test/resources/moderation/reference/daisy.jpg}"
OUT="${OUT:-$ROOT/docs/review/$(date +%F)-happy-path-review.md}"
DEADLINE_SECONDS="${DEADLINE_SECONDS:-150}"
ROUND_SECONDS="${ROUND_SECONDS:-15}"
MODERATION_TRIES="${MODERATION_TRIES:-20}"

command -v curl  >/dev/null || { echo "curl не найден" >&2; exit 2; }
command -v jq    >/dev/null || { echo "jq не найден" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 не найден" >&2; exit 2; }

HEALTH_URL="${BASE_URL%/api/v1}"; HEALTH_URL="${HEALTH_URL%/}/actuator/health"
curl -s -m 3 "$HEALTH_URL" | grep -q '"UP"' \
  || { echo "Приложение недоступно: $HEALTH_URL (docker compose up --build)" >&2; exit 2; }

# --- админ: явный ID или поиск в БД по email -------------------------------
if [ -z "${ADMIN_ID:-}" ]; then
  ADMIN_ID=$(docker compose -f "$ROOT/docker-compose.yml" exec -T postgres \
    psql -U "$DB_USER" -d "$DB_NAME" -t -A -c \
    "select id from identity.app_user where email_normalized = '$ADMIN_EMAIL'" \
    2>/dev/null | tr -d '[:space:]' || true)
fi
[ -n "${ADMIN_ID:-}" ] \
  || { echo "ADMIN_ID не задан и не найден в БД по '$ADMIN_EMAIL'" >&2; exit 2; }
ADMIN="$ADMIN_ID"

# --- изображения: готовый каталог или 6 кропов эталонной ромашки -----------
if [ -n "${IMAGES_DIR:-}" ]; then
  IMG="$IMAGES_DIR"
else
  command -v sips >/dev/null \
    || { echo "sips не найден: передайте IMAGES_DIR с p1a.jpg..p3b.jpg" >&2; exit 2; }
  [ -r "$SRC_IMAGE" ] || { echo "SRC_IMAGE не найден: $SRC_IMAGE" >&2; exit 2; }
  IMG=$(mktemp -d)
  # (высота ширина смещениеY смещениеX) — кропы внутри пары участника различны
  CROPS=( "190 210 5 5 p1a" "185 195 40 15 p1b" "180 200 10 10 p2a" \
          "175 190 45 20 p2b" "170 185 15 8 p3a" "165 180 50 25 p3b" )
  for c in "${CROPS[@]}"; do
    set -- $c
    sips -c "$1" "$2" --cropOffset "$3" "$4" "$SRC_IMAGE" --out "$IMG/$5.jpg" >/dev/null
  done
fi
for f in p1a p1b p2a p2b p3a p3b; do
  [ -r "$IMG/$f.jpg" ] || { echo "Нет изображения $IMG/$f.jpg" >&2; exit 2; }
done

# --- дата/время (портабельно через python3) ---------------------------------
epoch_now() { date +%s; }
fmt_utc()   { python3 -c "import datetime;print(datetime.datetime.fromtimestamp($1, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))"; }
to_epoch()  { python3 -c "import datetime;print(int(datetime.datetime.fromisoformat('$1'.replace('Z','+00:00')).timestamp()))"; }

TMP=$(mktemp)
STEP=0
FAIL=0
SUMMARY=()
STATUS=""
BODY=""

# call <заголовок> <method> <path> <user|-> <body|-> [multipart-file]
call() {
  local title="$1" method="$2" path="$3" user="$4" body="${5:-}" mp="${6:-}"
  local args=(-s -o "$TMP" -w '%{http_code}' -X "$method" "$BASE_URL$path")
  if [ -n "$mp" ]; then
    args+=(-F "file=@$mp")
  else
    args+=(-H 'Content-Type: application/json')
    [ -n "$body" ] && [ "$body" != "-" ] && args+=(-d "$body")
  fi
  [ -n "$user" ] && [ "$user" != "-" ] && args+=(-H "X-Demo-User-Id: $user")
  STATUS=$(curl "${args[@]}")
  BODY=$(cat "$TMP")
  if echo "$BODY" | jq -e . >/dev/null 2>&1; then BODY=$(echo "$BODY" | jq .); fi

  local req_block
  req_block=$'```http'
  req_block+=$'\n'"$method $path"
  [ -n "$user" ] && [ "$user" != "-" ] && req_block+=$'\n'"X-Demo-User-Id: $user"
  if [ -n "$mp" ]; then
    req_block+=$'\n'"Content-Type: multipart/form-data; file=$(basename "$mp")"
  elif [ -n "$body" ] && [ "$body" != "-" ]; then
    req_block+=$'\n'"$body"
  fi
  req_block+=$'\n```'

  cat >> "$OUT" <<EOF
### $title

$req_block

**Ответ: \`$STATUS\`**

\`\`\`json
$BODY
\`\`\`

EOF
}

# ok <ожидаемый статус> <что проверено> — фиксирует успех/провал последнего ответа
ok() {
  local expect="$1" what="$2"
  if [ "$STATUS" = "$expect" ]; then
    SUMMARY+=("✅|${what}|$STATUS")
  else
    SUMMARY+=("❌|${what}|$STATUS (ожидался $expect)")
    FAIL=1
  fi
}

hdr() { printf '%s\n' "$1" >> "$OUT"; }
jqid() { echo "$BODY" | jq -r "$1"; }

# ---------------------------------------------------------------------------
RUN=$(date +%s)
mkdir -p "$(dirname "$OUT")"
: > "$OUT"

hdr "# Happy-path ревью API — Plant Arena (лаба №1)"
hdr ""
hdr "**Дата:** $(date '+%Y-%m-%d %H:%M')  "
hdr "**Окружение:** \`$BASE_URL\`, профиль dev, демо-идентификация \`X-Demo-User-Id\` (ADR-005).  "
hdr "**Параметры:** дедлайн регистрации +${DEADLINE_SECONDS} с, раунд ${ROUND_SECONDS} с, доля выбывания 0.5, минимум 2 участника."
hdr ""
hdr "Сценарий: создание модератора → приватный турнир → 3 участника → регистрация в глобальный турнир (автоматическая модерация ONNX) → приглашения и регистрация в приватный турнир → старт → 2 раунда голосования с выбыванием → результаты. В раунде 1 выбывает p3, в раунде 2 — p2, победитель — p1."
hdr ""
hdr "Каждому участнику нужно **2 разных растения**: пара (owner, fingerprint) допускает только один активный резерв (допущение 4, ADR-010) — одно изображение уходит в глобальный турнир, другое в приватный."
hdr ""

# --- 1. Модератор ----------------------------------------------------------
STEP=1
hdr "## 1. Модератор"
hdr ""
call "1.1 Создание пользователя-модератора (админ)" POST /users "$ADMIN" \
  "{\"email\":\"review-mod-$RUN@plantarena.local\",\"password\":\"password-9\",\"displayName\":\"Review Moderator\"}"
MOD=$(jqid '.id')
ok 201 "Создан модератор (user)"

call "1.2 Назначение роли модератора (админ, идемпотентно)" PUT "/users/$MOD/roles/moderator" "$ADMIN"
ok 200 "Роль moderator назначена"

# --- 2. Участники ----------------------------------------------------------
STEP=2
hdr "## 2. Участники (создаёт модератор)"
hdr ""
P=()
for i in 1 2 3; do
  call "2.$i Создание участника p$i (модератор)" POST /users "$MOD" \
    "{\"email\":\"review-p$i-$RUN@plantarena.local\",\"password\":\"password-9\",\"displayName\":\"Participant $i\"}"
  P[$i]=$(jqid '.id')
  ok 201 "Создан участник p$i"
done

# --- 3. Координаты ---------------------------------------------------------
STEP=3
hdr "## 3. Профили: координаты (обязательны для глобального турнира)"
hdr ""
LOCS=("55.7558 37.6173" "55.7560 37.6180" "59.9390 30.3150" "59.9395 30.3160")
for i in 1 2 3; do
  set -- ${LOCS[$i]}
  call "3.$i p$i задаёт локацию" PUT /me/location "${P[$i]}" \
    "{\"latitude\":$1,\"longitude\":$2}"
  ok 200 "Локация p$i сохранена"
done

# --- 4. Растения для глобального турнира -----------------------------------
STEP=4
hdr "## 4. Растения для глобального турнира: загрузка → заявка → автоматическая модерация"
hdr ""
hdr "Изображения — разные кропы эталонной ромашки (daisy.jpg, ADR-009): классификатор MobileNetV2 ONNX должен распознать растение. Задания модерации выполняются scheduler'ом автоматически (2 с), без ручного запуска."
hdr ""
PLANT_G=()
for i in 1 2 3; do
  call "4.$(( i*3-2 )) p$i загружает изображение (multipart)" POST /files "${P[$i]}" "-" "$IMG/p${i}a.jpg"
  ASSET=$(jqid '.id')
  ok 201 "Файл p${i}a загружен"

  call "4.$(( i*3-1 )) p$i подаёт растение" POST /plants "${P[$i]}" \
    "{\"assetId\":\"$ASSET\",\"title\":\"Ромашка p$i (global)\"}"
  PLANT_G[$i]=$(jqid '.id')
  ok 201 "Растение p$i (global) создано (PENDING)"

  # автоматическая модерация: poll до APPROVED
  ST=""
  for t in $(seq 1 "$MODERATION_TRIES"); do
    ST=$(curl -s -H "X-Demo-User-Id: ${P[$i]}" "$BASE_URL/plants/${PLANT_G[$i]}/moderation" | jq -r '.moderationStatus')
    [ "$ST" = "APPROVED" ] && break
    sleep 2
  done
  call "4.$(( i*3 )) p$i проверяет решение модерации (после автоматической обработки scheduler'ом)" \
    GET "/plants/${PLANT_G[$i]}/moderation" "${P[$i]}"
  ok 200 "Модерация p$i (global): $(jqid '.moderationStatus') / $(jqid '.reason')"
  [ "$(jqid '.moderationStatus')" = "APPROVED" ] || { SUMMARY+=("❌|Модерация p$i не APPROVED: $ST|—"); FAIL=1; }
done

# --- 5. Глобальный турнир --------------------------------------------------
STEP=5
hdr "## 5. Регистрация в глобальный турнир (автоматом, без приглашений)"
hdr ""
call "5.0 Конфигурация глобального турнира (публично)" GET /global - ""
ok 200 "Конфигурация глобального турнира получена"
hdr ""
hdr "Подача заявки — без приглашений и без модератора: своё APPROVED-растение → сразу в очередь (QUEUED). Резерв изображения создаётся автоматически."
hdr ""
for i in 1 2 3; do
  call "5.$i p$i подаёт заявку в глобальный турнир" POST /global/entries "${P[$i]}" \
    "{\"plantId\":\"${PLANT_G[$i]}\"}"
  ok 201 "p$i в очереди глобального турнира ($(jqid '.status'))"

  call "5.$(( 10+i )) p$i проверяет своё участие" GET /me/global-entry "${P[$i]}"
  ok 200 "Участие p$i: $(jqid '.status')"
done

# --- 6. Приватный турнир ---------------------------------------------------
STEP=6
hdr "## 6. Приватный турнир (создаёт модератор)"
hdr ""
call "6.1 Модератор создаёт тег" POST /tags "$MOD" "{\"name\":\"review-happy-path-$RUN\"}"
TAG=$(jqid '.id')
ok 201 "Тег создан"

DL_E=$(( $(epoch_now) + DEADLINE_SECONDS ))
DEADLINE=$(fmt_utc "$DL_E")
call "6.2 Модератор создаёт турнир (DRAFT): дедлайн $DEADLINE, раунд ${ROUND_SECONDS} с, доля выбывания 0.5, минимум 2" \
  POST /tournaments "$MOD" \
  "{\"name\":\"Review Happy Path Cup\",\"description\":\"Ревью happy path: 3 участника, 2 раунда\",\"registrationDeadline\":\"$DEADLINE\",\"roundDurationSeconds\":$ROUND_SECONDS,\"eliminationFraction\":0.5,\"minParticipants\":2,\"tagIds\":[\"$TAG\"]}"
T=$(jqid '.id')
ok 201 "Турнир создан ($(jqid '.status'))"

call "6.3 Открытие регистрации" POST "/tournaments/$T/open-registration" "$MOD"
ok 200 "Регистрация открыта ($(jqid '.status'))"

for i in 1 2 3; do
  call "6.$(( 3+i )) Модератор приглашает p$i" POST "/tournaments/$T/invitations" "$MOD" \
    "{\"userId\":\"${P[$i]}\"}"
  ok 201 "Приглашение p$i создано"
done

# --- 7. Растения для приватного турнира + принятие приглашений -------------
STEP=7
hdr "## 7. Растения для приватного турнира + принятие приглашений"
hdr ""
hdr "Второе растение на участника — другой кроп (другой fingerprint): пара (owner, fingerprint) не может иметь два активных резерва."
hdr ""
PLANT_T=(); INV=()
for i in 1 2 3; do
  call "7.$(( i*4-3 )) p$i загружает второе изображение" POST /files "${P[$i]}" "-" "$IMG/p${i}b.jpg"
  ASSET=$(jqid '.id')
  ok 201 "Файл p${i}b загружен"

  call "7.$(( i*4-2 )) p$i подаёт второе растение" POST /plants "${P[$i]}" \
    "{\"assetId\":\"$ASSET\",\"title\":\"Ромашка p$i (private)\"}"
  PLANT_T[$i]=$(jqid '.id')
  ok 201 "Растение p$i (private) создано"

  ST=""
  for t in $(seq 1 "$MODERATION_TRIES"); do
    ST=$(curl -s -H "X-Demo-User-Id: ${P[$i]}" "$BASE_URL/plants/${PLANT_T[$i]}/moderation" | jq -r '.moderationStatus')
    [ "$ST" = "APPROVED" ] && break
    sleep 2
  done

  call "7.$(( i*4-1 )) p$i смотрит свои приглашения" GET /me/invitations "${P[$i]}"
  INV[$i]=$(echo "$BODY" | jq -r --arg t "$T" '.[] | select(.tournamentId==$t) | .id')
  ok 200 "Приглашение p$i найдено"

  call "7.$(( i*4 )) p$i принимает приглашение с растением (private)" \
    POST "/invitations/${INV[$i]}/accept" "${P[$i]}" "{\"plantId\":\"${PLANT_T[$i]}\"}"
  ok 200 "p$i принял: $(jqid '.status')"
done

# --- 8. Старт --------------------------------------------------------------
STEP=8
hdr "## 8. Старт турнира"
hdr ""
hdr "Дедлайн регистрации наступает — старт выполняет scheduler (fixedDelay 2 с); demo-ручка \`run-due\` вызывает тот же use case (идемпотентно, processed=0 если scheduler уже успел)."
hdr ""
WAIT=$(( DL_E - $(epoch_now) + 3 ))
[ "$WAIT" -gt 0 ] && sleep "$WAIT"
call "8.1 Продвижение наступивших дедлайнов (админ; тот же use case, что scheduler)" \
  POST /internal/demo/jobs/run-due "$ADMIN"
ok 200 "Дедлайны обработаны: processed=$(jqid '.processed')"

call "8.2 Статус турнира" GET "/tournaments/$T" "$MOD"
ok 200 "Турнир: $(jqid '.status')"

call "8.3 Участники турнира" GET "/tournaments/$T/entries" "$MOD"
ok 200 "Участия получены"
E=()
for i in 1 2 3; do
  E[$i]=$(echo "$BODY" | jq -r --arg u "${P[$i]}" '.[] | select(.userId==$u) | .id')
done

# --- 9. Раунд 1 ------------------------------------------------------------
STEP=9
hdr "## 9. Раунд 1 — голосование, выбывает p3"
hdr ""
call "9.1 Раунды турнира (окно раунда 1)" GET "/tournaments/$T/rounds" "${P[1]}"
W1=$(echo "$BODY" | jq -r '[.[] | select(.status=="OPEN")][0].id // [.[] | select(.status=="RUNNING")][0].id // .[-1].id')
W1_CLOSES=$(echo "$BODY" | jq -r --arg w "$W1" '.[] | select(.id==$w) | .closesAt')
ok 200 "Окно раунда 1: $W1 (closesAt $W1_CLOSES)"

call "9.2 p1 голосует DISLIKE против p3" PUT "/windows/$W1/entries/${E[3]}/vote" "${P[1]}" '{"value":"DISLIKE"}'
ok 200 "Голос p1 учтён (score $(jqid '.score'))"
call "9.3 p2 голосует DISLIKE против p3" PUT "/windows/$W1/entries/${E[3]}/vote" "${P[2]}" '{"value":"DISLIKE"}'
ok 200 "Голос p2 учтён (score $(jqid '.score'))"
call "9.4 p3 голосует LIKE за p1" PUT "/windows/$W1/entries/${E[1]}/vote" "${P[3]}" '{"value":"LIKE"}'
ok 200 "Голос p3 учтён (score $(jqid '.score'))"

call "9.5 Лидерборд раунда 1" GET "/tournaments/$T/leaderboard?windowId=$W1" "${P[1]}"
ok 200 "Лидерборд раунда 1 получен"

# --- 10. Раунд 2 -----------------------------------------------------------
STEP=10
hdr "## 10. Закрытие раунда 1 → раунд 2 — выбывает p2"
hdr ""
WAIT=$(( $(to_epoch "$W1_CLOSES") - $(epoch_now) + 3 ))
[ "$WAIT" -gt 0 ] && sleep "$WAIT"
call "10.1 Продвижение дедлайна закрытия окна (админ)" POST /internal/demo/jobs/run-due "$ADMIN"
ok 200 "Окно раунда 1 закрыто"

call "10.2 Раунды: открыт раунд 2" GET "/tournaments/$T/rounds" "${P[1]}"
W2=$(echo "$BODY" | jq -r '[.[] | select(.status=="OPEN")][0].id // [.[] | select(.status=="RUNNING")][0].id')
W2_CLOSES=$(echo "$BODY" | jq -r --arg w "$W2" '.[] | select(.id==$w) | .closesAt')
ok 200 "Окно раунда 2: $W2 (closesAt $W2_CLOSES)"

call "10.3 p1 голосует DISLIKE против p2" PUT "/windows/$W2/entries/${E[2]}/vote" "${P[1]}" '{"value":"DISLIKE"}'
ok 200 "Голос p1 учтён (score $(jqid '.score'))"
call "10.4 p3 (выбывший в раунде 1, допущение 9 — голосует до FINISHED) DISLIKE против p2" \
  PUT "/windows/$W2/entries/${E[2]}/vote" "${P[3]}" '{"value":"DISLIKE"}'
ok 200 "Голос выбывшего p3 учтён (score $(jqid '.score'))"

call "10.5 Лидерборд раунда 2" GET "/tournaments/$T/leaderboard?windowId=$W2" "${P[1]}"
ok 200 "Лидерборд раунда 2 получен"

# --- 11. Финал -------------------------------------------------------------
STEP=11
hdr "## 11. Финал: последний выживший — победитель"
hdr ""
WAIT=$(( $(to_epoch "$W2_CLOSES") - $(epoch_now) + 3 ))
[ "$WAIT" -gt 0 ] && sleep "$WAIT"
call "11.1 Продвижение дедлайна закрытия окна (админ)" POST /internal/demo/jobs/run-due "$ADMIN"
ok 200 "Окно раунда 2 закрыто"

call "11.2 Статус турнира" GET "/tournaments/$T" "$MOD"
ok 200 "Турнир: $(jqid '.status')"

call "11.3 Результаты турнира" GET "/tournaments/$T/results" "$MOD"
ok 200 "Результаты получены"
WINNER=$(jqid '.winnerEntryId')
if [ "$WINNER" = "${E[1]}" ]; then
  SUMMARY+=("✅|Победитель — p1 (entry ${E[1]})|—")
else
  SUMMARY+=("❌|Победитель не p1: $WINNER|—"); FAIL=1
fi

# --- 12. Пост-состояние ----------------------------------------------------
STEP=12
hdr "## 12. Пост-состояние: судьба растений"
hdr ""
for i in 1 2 3; do
  call "12.$i Растение p$i (private) после турнира" GET "/plants/${PLANT_T[$i]}" "${P[$i]}"
  ok 200 "Растение p$i: $(jqid '.lifeStatus')"
done

# --- итог ------------------------------------------------------------------
hdr "## Итог"
hdr ""
hdr "| Статус | Шаг | HTTP |"
hdr "|---|---|---|"
for s in "${SUMMARY[@]}"; do IFS='|' read -r a b c <<< "$s"; hdr "| $a | $b | $c |"; done
hdr ""
if [ "$FAIL" = "0" ]; then
  hdr "**Результат: happy path пройден полностью — все шаги успешны. ✅**"
else
  hdr "**Результат: есть неуспешные шаги — см. таблицу. ❌**"
fi

echo "Отчёт: $OUT"
[ "$FAIL" = "0" ] || exit 1
