# День 30: приватный сервис анализа косметики на локальной LLM

## Цель

Развернуть локальную LLM как приватный HTTP-сервис с чатом и показать все требования задания на полезном продукте:

```text
фото этикетки -> локальный OCR -> подтверждённый INCI
текст INCI / точное название из локального каталога
  -> exact ingredient retrieval
  -> компактные проверенные facts + source IDs
  -> qwen3:4b через loopback Ollama /api/chat
  -> strict enum/ID decision + server-side grounding validation
  -> серверная сборка отчёта из allowlist-шаблонов и карточек
  -> временный чат с allowlist topic decision
```

Приложение объясняет предполагаемый тип средства, возможное место в рутине, ключевые ингредиенты, типы кожи и ограничения. Оно не ставит диагноз, не вычисляет неизвестные концентрации/pH и не объявляет продукт «безопасным» или «токсичным».

Стек: Kotlin/JVM 21, Ktor/CIO, `kotlinx.serialization`, Tesseract OCR и прямой `java.net.http.HttpClient` к локальному Ollama. LLM SDK, облачная модель и cloud fallback не используются.

## Как прошлые дни улучшают маленькую модель

- Day 2/27: JSON Schema, строгий parsing и безопасный web upload;
- Day 10: bounded recent chat history;
- Day 21–23: структурные knowledge cards, exact retrieval, filtering;
- Day 24: source IDs, anti-hallucination gate и `unknown` вместо догадки;
- Day 25: RAM session с текущим продуктом и профилем;
- Day 28: только локальный HTTP inference;
- Day 29: Q4, `temperature=0`, `think=false`, короткий prompt, ограниченные context/output и метрики.

Веса модели от чата не меняются. Fine-tuning имеет смысл только после накопления проверенного экспертного датасета; для Day 30 retrieval, schema, validator и evaluation дают более надёжный результат на дешёвом VPS.

## Входы

1. **INCI-текст** — основной сценарий.
2. **Фото JPEG/PNG** — `/api/ocr` возвращает текст; пользователь обязан проверить его перед анализом.
3. **Название** — только точный поиск в локальном вымышленном каталоге. Неизвестный продукт получает `404 product_not_found`, а модель не вызывается.

Локальные runtime-данные:

- `knowledge/ingredient-cards.json`: 24 карточки распространённых INCI;
- `knowledge/sources.json`: 12 источников EC/FDA/AAD/SCCS;
- `catalog/products.json`: четыре вымышленных продукта и демонстрация reformulation;
- `eval/eval-cases.json`: text/name/unknown/prompt-injection сценарии.

Основная регуляторная оговорка: запись ингредиента в CosIng не означает одобрение ингредиента или безопасность готового продукта. Безопасность оценивается для полной формулы производителем.

## Приватность и границы сети

```text
Browser -> Caddy HTTPS :443 -> Ktor 127.0.0.1:8787
                              -> Ollama 127.0.0.1:11434
```

- `APP_HOST` и `OLLAMA_BASE_URL` принимают только loopback;
- raw Ollama `11434` никогда не публикуется — локальный Ollama API не имеет своей аутентификации;
- приватные API требуют `Authorization: Bearer <APP_API_TOKEN>`;
- CORS не включён, UI и API работают same-origin;
- постоянный access token вводится один раз, проверяется через `/api/health` и хранится в `localStorage` этого HTTPS-origin до нажатия «Выйти»;
- token передаётся только в `Authorization`, не рендерится вне password input и не попадает в URL, request body или логи; после успешной проверки или `401` input очищается;
- boot/login/app views не показывают приватный интерфейс до успешной проверки token; общий `401` очищает локальный доступ;
- фото передаётся Tesseract через stdin и не создаёт временный файл;
- INCI, фото и чат не попадают в application/access logs;
- сессии существуют только в RAM, имеют TTL и удаляются через API;
- модель не возвращает пользовательскую прозу: только enum/ID; UI выводит собранный сервером текст через DOM `textContent`;
- CSP, `nosniff`, запрет framing и `no-store` выставляются сервером.
- `/api/health/live` — публичный дешёвый liveness для proxy; dependency readiness в защищённом `/api/health` single-flight и кешируется на 10 секунд;

VPS остаётся инфраструктурой хостинг-провайдера, поэтому «приватный» здесь означает отсутствие стороннего LLM API и закрытый сетевой контур приложения, а не абсолютный физический контроль над сервером.

`localStorage` выбран для удобного постоянного входа и доступен только JavaScript того же origin. Поэтому страница не подключает сторонние scripts, сохраняет строгий CSP и должна открываться только по HTTPS. На чужом устройстве нужно нажать «Выйти».

## Ограничения по умолчанию

```text
model=qwen3:4b Q4
num_ctx=8192
num_predict=192
temperature=0
think=false
1 active inference + 2 queued
12 API requests / 60 seconds
INCI <= 12000 chars
chat <= 2000 chars, last 8 messages
photo <= 5 MiB and <= 20 MP
100 RAM sessions, TTL 30 minutes + periodic RAM cleanup
```

Rate limit и inference queue возвращают `429` и `Retry-After`. Слишком большие данные отклоняются до Ollama. Все `ingredientId` из решения модели проверяются по текущему evidence pack; citations сервер получает только из карточек. Выдуманный ID или лишнее prose-поле превращается в `502`, а пользовательский текст собирается только сервером из allowlist-шаблонов и проверенных данных.

Технический профиль сверён с официальной документацией Ollama: `/api/chat` принимает JSON Schema в `format` и `think=false`, а RAM растёт вместе с context/parallelism. Поэтому сервис использует structured output, `temperature=0`, одну параллельную генерацию и жёсткий предел 8192, хотя сама `qwen3:4b` поддерживает больше:

- [Ollama Chat API](https://docs.ollama.com/api/chat)
- [Structured Outputs](https://docs.ollama.com/capabilities/structured-outputs)
- [Concurrency and queue FAQ](https://docs.ollama.com/faq)
- [qwen3 model tags and sizes](https://ollama.com/library/qwen3/tags)

## Локальная подготовка

Нужны JDK 21, Ollama и Tesseract с языками `eng` и `rus`.

```bash
ollama serve
ollama pull qwen3:4b
ollama list

cp day-30-private-cosmetics-service-kotlin/.env.example \
  day-30-private-cosmetics-service-kotlin/.env
```

`.env.example` разрешает отсутствие token только для loopback local development. На VPS это значение принудительно выключено.

## Offline-проверки

```bash
./gradlew :day-30-private-cosmetics-service-kotlin:test
./gradlew :day-30-private-cosmetics-service-kotlin:build

day-30-private-cosmetics-service-kotlin/scripts/run-local.sh fixture-demo
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh eval-dry-run
```

`fixture-demo` не обращается к модели. `eval-dry-run` проверяет exact retrieval, aliases, актуальную/историческую reformulation, неизвестный продукт и prompt injection.

## Запуск web/API

```bash
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh diagnose
day-30-private-cosmetics-service-kotlin/scripts/run-local.sh
```

Откройте `http://127.0.0.1:8787`. Фото сначала заполняет INCI textarea результатом OCR; анализ начинается только после ручного подтверждения.

### HTTP API

Публичный liveness не запускает проверки зависимостей:

```bash
curl http://127.0.0.1:8787/api/health/live
```

Readiness Ollama, модели и OCR требует token:

```bash
curl http://127.0.0.1:8787/api/health \
  -H "Authorization: Bearer $APP_API_TOKEN"
```

Анализ текста:

```bash
curl http://127.0.0.1:8787/api/analyze/text \
  -H "Authorization: Bearer $APP_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "inciText": "AQUA, GLYCERIN, NIACINAMIDE, PANTHENOL",
    "productName": "Demo serum",
    "profile": {
      "skinType": "sensitive",
      "sensitive": true,
      "allergies": [],
      "goals": ["увлажнение"]
    }
  }'
```

Точное имя из demo-каталога:

```bash
curl http://127.0.0.1:8787/api/analyze/name \
  -H "Authorization: Bearer $APP_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"DemoLab Hydro Balance Serum","profile":{}}'
```

OCR:

```bash
curl http://127.0.0.1:8787/api/ocr \
  -H "Authorization: Bearer $APP_API_TOKEN" \
  -F "photo=@/absolute/path/to/label.jpg"
```

Чат использует `sessionId` из анализа:

```bash
curl http://127.0.0.1:8787/api/chat \
  -H "Authorization: Bearer $APP_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"...","message":"Когда использовать это средство?"}'
```

## VPS Ubuntu 24.04

Deployment не использует Docker и экономит RAM. На чистом VDS обычно существует только `root`, поэтому bootstrap явно создаёт отдельного key-only пользователя `deploy`, копирует уже установленный root SSH public key и даёт ему passwordless sudo. Парольный SSH-вход отключается отдельно и только после проверки второго key-based входа, чтобы не заблокировать сервер.

```bash
sudo ./scripts/setup-vps.sh \
  --install-ollama \
  --deploy-user deploy \
  --copy-root-authorized-keys \
  --configure-ufw \
  --ssh-port 22
```

После bootstrap нужно переподключиться как `deploy` и разместить checkout в его home, а не в `/root`:

```bash
ssh deploy@SERVER_IP
cd /home/deploy/ai-course/day-30-private-cosmetics-service-kotlin

sudo cp /etc/cosmetics-ai/cosmetics-ai.env.example \
  /etc/cosmetics-ai/cosmetics-ai.env
sudo openssl rand -hex 32
# вставить результат только в APP_API_TOKEN внутри root-owned env
sudo chown root:root /etc/cosmetics-ai/cosmetics-ai.env
sudo chmod 0600 /etc/cosmetics-ai/cosmetics-ai.env

./scripts/deploy-vps.sh --pull-model
./scripts/diagnose.sh
```

Когда `ssh deploy@SERVER_IP` уже проверен в отдельном терминале, можно оставить
root только как аварийный key-based вход и отключить SSH-пароли:

```bash
sudo install -o root -g root -m 0644 \
  deploy/sshd-hardening.conf \
  /etc/ssh/sshd_config.d/00-cosmetics-ai-hardening.conf
sudo sshd -t
sudo systemctl reload ssh
```

Конфиг сохраняет только локальный `-L` forwarding, нужный для приватного web UI.
После reload нужно снова проверить входы `deploy` и `root` по ключу в новых SSH-сессиях.

Systemd assets:

- держат Kotlin и Ollama на loopback;
- задают `OLLAMA_MAX_LOADED_MODELS=1`, `OLLAMA_NUM_PARALLEL=1`, `OLLAMA_MAX_QUEUE=2`;
- запускают приложение непривилегированным пользователем;
- включают systemd hardening;
- используют versioned releases и rollback при неуспешном health-check.

### Постоянный публичный HTTPS

Hostname `papaya-hiddenite24599.my-vm.work` уже имеет A и AAAA этого VPS. Отдельный скрипт явно устанавливает официальный stable Caddy, рендерит и валидирует конфиг, открывает только TCP 80/443 и ждёт публичный сертификат:

```bash
sudo ./scripts/enable-public-https.sh \
  --hostname papaya-hiddenite24599.my-vm.work \
  --install-caddy \
  --configure-ufw

./scripts/diagnose-public-https.sh \
  --hostname papaya-hiddenite24599.my-vm.work
```

С другого компьютера, а не с самого VPS, отдельно проверяются реальная внешняя сеть и сертификат по IPv4/IPv6:

```bash
./scripts/check-public-url.sh \
  --hostname papaya-hiddenite24599.my-vm.work
```

Постоянный адрес: `https://papaya-hiddenite24599.my-vm.work`. Caddy автоматически получает/продлевает сертификат и перенаправляет HTTP на HTTPS. HTTP/3 отключён, поэтому наружу не открывается UDP 443. Access log не включён. Приложение и Ollama остаются loopback-only.

`APP_API_TOKEN` создаётся один раз в root-owned `/etc/cosmetics-ai/cosmetics-ai.env`, не меняется при обычных deploy и используется как постоянный код входа. Пользователь вводит его один раз в web-форме; браузер запоминает код до logout. Смена server token отзывает доступ у всех ранее авторизованных устройств.

Для осознанной ротации скрипт читает новое значение скрыто из stdin, атомарно меняет env, проверяет readiness и автоматически возвращает старое значение при ошибке:

```bash
sudo ./scripts/set-access-token.sh
```

Token нельзя передавать аргументом команды, коммитить или показывать в видео.

Конфигурация следует официальным инструкциям Caddy:

- [Install Caddy on Debian/Ubuntu](https://caddyserver.com/docs/install#debian-ubuntu-raspbian)
- [Automatic HTTPS](https://caddyserver.com/docs/automatic-https)
- [Reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)

### SSH tunnel как закрытый fallback

```bash
ssh -L 8787:127.0.0.1:8787 deploy@SERVER_IP
```

После этого браузер открывает `http://127.0.0.1:8787`, но трафик идёт внутри SSH. Это резервный путь для диагностики, если публичный HTTPS временно недоступен.

## Smoke, несколько запросов и лимиты

```bash
./scripts/smoke-test.sh --deep
./scripts/load-test.sh --requests 4 --concurrency 2
./scripts/rate-limit-test.sh
```

- smoke проверяет health, `401`, oversized INCI и реальный grounded answer;
- load test отправляет несколько model-backed запросов, печатает HTTP status и latency;
- rate-limit test дешёво доказывает `429 + Retry-After`, не вызывая LLM.

После rate-limit test нужно дождаться `Retry-After` перед deep smoke.

## Проверка другой модели

12 ГБ RAM позволяют после базового запуска отдельно сравнить `qwen3:4b` и `qwen3:8b` с одинаковыми prompt, context и eval cases. Одновременно загружается только одна модель. Основной безопасный default остаётся `qwen3:4b`; `qwen3:14b` для этого VPS не используется.

## Сценарий видео

1. Показать постоянный HTTPS URL и экран входа, не показывая сам access token.
2. Ввести token, обновить страницу и показать автоматический повторный вход из `localStorage`.
3. Показать `diagnose.sh`/`diagnose-public-https.sh`: Caddy снаружи, Ktor и Ollama только на loopback.
4. Загрузить тестовую этикетку, проверить OCR-текст, затем запустить анализ.
5. Показать report, source links, limitations и model latency.
6. Спросить в чате, когда применять продукт.
7. Ввести неизвестное название и показать, что состав не выдумывается.
8. Запустить четыре запроса с concurrency 2 и показать `429 + Retry-After`.
9. Нажать «Выйти», показать возврат на login и отсутствие token в URL.
10. Выполнить `ollama ps` и подтвердить отсутствие cloud endpoint.

Не показывать в видео `/etc/cosmetics-ai/cosmetics-ai.env`, token, приватные фотографии или SSH-ключи.

## Проверка требований Дня 30

- локальная LLM развёртывается на VPS через Ollama;
- есть документированный HTTP API и web UI;
- чат хранит ограниченную историю в RAM;
- доступ по сети постоянно работает через Caddy HTTPS, а SSH tunnel остаётся fallback;
- несколько запросов проходят bounded queue без неконтролируемого роста RAM;
- rate limit, max body/context/output и session TTL реализованы явно;
- raw Ollama не публикуется;
- результат воспроизводим кодом, fixture/eval, smoke/load scripts и video scenario.
