# День 33. Ассистент поддержки пользователей

Мини-сервис поддержки вымышленного продукта на Kotlin:

- отвечает на вопросы по FAQ и документации через RAG;
- получает текущий тикет и связанного пользователя через настоящий MCP
  Streamable HTTP;
- использует только synthetic JSON без реальных клиентов и PII;
- проверяет source/fact IDs и fail-closes в `unknown`;
- одинаковый вопрос маршрутизируется к разным ответам по контексту тикета.

Формат сдачи: **код + видео 3–5 минут**.

## Что доказывает результат

В offline `fixture-demo` вопрос:

```text
Почему не работает авторизация?
```

задаётся для двух тикетов:

- `TCK-1001`: `ACCOUNT_LOCKED`, пользователь `LOCKED`;
- `TCK-1002`: `INVALID_OTP`, часы устройства отстают на 241 секунду.

Ассистент получает оба контекста через MCP, обогащает ими retrieval query и
выбирает разные инструкции:

- ожидание и сброс пароля для защитной блокировки;
- синхронизация времени и новый OTP для `CLOCK_SKEW`.

В demo нет cloud-запросов: `LLM calls=0`.

## Соответствие заданию

| Требование | Реализация |
|---|---|
| Отвечать о продукте | FAQ, authentication, billing и escalation docs |
| Использовать RAG | structured chunks, stable source IDs, `hash-v1`, hybrid lexical/vector retrieval |
| Учитывать пользователя/тикет | immutable `SupportContext` и allowlisted `ContextFact` |
| Подключить CRM/JSON через MCP | synthetic `fixtures/support-data.json` и два read-only MCP tools |
| Ответить с учётом тикета | одинаковый auth-вопрос даёт разные grounded diagnosis/actions |
| Видео + код | offline modes, expected output и сценарий записи ниже |

## Архитектура

```text
ticketId + question
        |
        v
strict input validation
        |
        v
loopback MCP discovery: tools/list
        |
        +--> support_get_ticket(ticketId)
        |
        +--> support_get_user(ticket.userId)
        |
        v
sanitized immutable SupportContext + ContextFact IDs
        |
        v
question + typed ticket facts -> hybrid hash-v1 RAG
        |
        v
bounded KnowledgeEvidencePack
        |
        v
typed TransmittedSupportInput + explicit untrusted-data delimiters
        |
        +--> fixture responder (offline)
        |
        +--> direct java.net.http.HttpClient -> Eliza (live)
        |
        v
strict JSON decode -> source/fact/current-ticket validator
        |
        v
server-owned terminal rendering or canonical safe unknown
```

Один `KnowledgeEvidencePack` является source of truth для:

- prompt evidence;
- `allowedSourceIds`;
- deterministic fixture responder;
- grounding validator;
- terminal citations.

## Стек

- Kotlin/JVM `2.3.21`;
- Java toolchain `21`;
- `kotlinx-serialization-json 1.9.0`;
- официальный MCP Kotlin SDK `0.13.0`;
- Ktor/CIO `3.4.3`, Streamable HTTP;
- прямой LLM REST через `java.net.http.HttpClient`;
- Eliza/OpenRouter:
  `https://api.eliza.yandex.net/openrouter/v1/chat/completions`;
- модель по умолчанию:
  `meta-llama/llama-3.3-70b-instruct`.

High-level LLM SDK и готовый agent framework не используются. MCP SDK
используется только как protocol client/server.
Live endpoint pinned целиком: scheme, host, default port, path и отсутствие
query. Custom `LLM_API_URL` rejected до HTTP. Run script проверяет endpoint до
чтения fallback token из Day 1 `.env`, поэтому shared OAuth credential нельзя
скомбинировать с caller/local custom destination.

## Synthetic support data

[fixtures/support-data.json](fixtures/support-data.json) обязан содержать:

```json
{
  "synthetic": true,
  "users": [],
  "tickets": []
}
```

`JsonSupportDataRepository` fail-closes, если:

- `synthetic` не равен `true`;
- JSON содержит неизвестные поля;
- ID не соответствуют `TCK-<digits>` / `USR-<digits>`;
- ID повторяются;
- ticket ссылается на отсутствующего user;
- строки, числа или общий файл превышают limits;
- fixture является symlink;
- fixture больше 1 MB: размер проверяется на открытом `NOFOLLOW_LINKS`
  channel до JSON decode/allocation;
- в схему пытаются добавить email, телефон, пароль, token, CVC или произвольный
  CRM blob.

Перед live call дополнительно проверяется SHA-256 reviewed fixture. Локально
изменённый JSON по тому же пути не считается committed synthetic dataset и
останавливается до HTTP. При осознанном изменении fixture fingerprint нужно
обновить вместе с кодом и privacy review.

Committed users вымышлены. Реальные CRM records в Day 33 v1 не
поддерживаются.

## MCP

Embedded server доступен только по:

```text
http://127.0.0.1:3033/mcp
```

Он рекламирует **ровно два** read-only tool:

### `support_get_ticket`

Input:

```json
{"ticketId": "TCK-1001"}
```

Output:

```json
{"found": true, "ticket": {"id": "TCK-1001", "...": "allowlisted fields"}}
```

### `support_get_user`

Input:

```json
{"userId": "USR-1001"}
```

Client не принимает произвольный user ID от пользователя. Сначала он получает
ticket, затем вызывает `support_get_user` ровно для `ticket.userId`.
Декодированные MCP records повторно проходят те же ID/string/number limits
перед созданием `SupportContext`, поэтому чужой loopback listener не может
обойти repository policy структурно валидным, но oversized ответом.

Нет list-all/search/write tools. Ассистент не меняет пароль, account state,
оплату, ticket status или CRM.

## RAG corpus

Committed allowlist:

- [knowledge/faq.md](knowledge/faq.md);
- [knowledge/authentication.md](knowledge/authentication.md);
- [knowledge/billing.md](knowledge/billing.md);
- [knowledge/escalation.md](knowledge/escalation.md).

Markdown режется по `##` headings. Stable source ID берётся из файла и
короткого section key:

```text
authentication.md#auth-locked
authentication.md#auth-otp-clock
billing.md#billing-retry
```

Fingerprint — SHA-256 от source/heading/ordinal/text. Offline embeddings
`hash-v1` детерминированы. Retrieval объединяет lexical и cosine score,
использует top-K, exact error-code boost и отдельный question relevance gate.
Перед live call путь, SHA-256 всех четырёх knowledge files и каждый
transmitted evidence source/text сверяются с reviewed corpus.

Query включает вопрос и только typed facts текущего тикета:

```text
Category
Product area
Error code
Failed auth attempts
Device clock skew
Account state
Plan
```

Сгенерированный index:

```text
runtime/rag-index.json
```

Он локальный и ignored.
Cache читается через bounded `NOFOLLOW_LINKS` channel: файл больше 5 MB,
malformed JSON или symlink игнорируется и безопасно пересобирается из
reviewed knowledge corpus. Cache считается полностью недоверенным: его exact
count/unique source IDs, path, heading, text, заново вычисленный fingerprint и
детерминированный `hash-v1` embedding сверяются со свежими chunks. При любом
duplicate/tampered/stale значении cache пересобирается, а retrieval получает
fresh chunk metadata/text и только проверенные embeddings.

Persistence создаёт temp с уникальным именем через atomic `CREATE_NEW` +
`NOFOLLOW_LINKS` внутри проверенного non-symlink runtime directory, делает
`force(true)`, затем выполняет atomic replace с безопасным fallback и удаляет
temp в `finally`. Предсказуемый legacy `rag-index.json.tmp` не открывается и не
удаляется, поэтому его symlink не может перезаписать victim.

## Model JSON contract

Live Eliza получает один bounded request с `response_format=json_object`,
`temperature=0` и output limit. Локальный strict decoder требует:

```json
{
  "status": "answered",
  "answer": "Краткий grounded ответ",
  "actionSteps": ["Шаг 1"],
  "knowledgeSourceIds": ["authentication.md#auth-locked"],
  "contextFactIds": ["ticket.errorCode", "user.accountState"],
  "clarifyingQuestion": null
}
```

Validator отклоняет:

- malformed JSON и extra/missing fields;
- неизвестные или duplicate source/fact IDs;
- IDs вне текущих evidence/context packs;
- слишком длинный answer/actions;
- `answered` без RAG source или ticket fact;
- `unknown` с citations/actions;
- ссылки на другой `TCK-*` или `USR-*`;
- инструкции отправить/сообщить password, OTP, token, CVC/CVV, card number или
  secret;
- unsafe text в `clarifyingQuestion`;
- answer без минимального overlap с cited evidence.

Nullability contract:

- `answered` обязан иметь `clarifyingQuestion=null`;
- `unknown` обязан иметь непустой `clarifyingQuestion` и пустые
  actions/source/fact IDs.

Невалидный model output заменяется canonical `unknown`. Raw output модели не
печатается.

## Privacy и trust boundary

В cloud разрешён только committed synthetic fixture Day 33. Prompt содержит:

- текущий synthetic ticket/user context;
- allowlisted typed facts;
- bounded RAG chunks;
- bounded RAM-only recent history текущего тикета.

Не отправляются:

- `.env`, OAuth token или `Authorization` header;
- email, телефон, password, OTP, CVC, полный номер карты;
- другие тикеты/пользователи;
- raw CRM blobs;
- persisted customer chat.

Ticket summary и docs явно маркируются как untrusted data. Вхождения prompt
boundary markers внутри ticket text нейтрализуются.
Question и history проходят тот же marker sanitization. Каждый `ChatTurn`
содержит `ticketId`; assistant API отклоняет history другого тикета, даже если
caller обходит CLI. Sensitive-looking user values — email, phone/card number,
6–8 digit OTP, assigned password/token/API key, CVC/CVV, Bearer/OAuth value,
JWT или private-key marker — fail-close локально до HTTP без echo значения.

Реальная CRM потребует отдельный `SupportDataSource` adapter, authorization,
redaction, retention policy и privacy review.

## Подготовка

Offline modes не требуют token:

```bash
./gradlew :day-33-support-assistant-kotlin:test
./gradlew :day-33-support-assistant-kotlin:build
```

Для live Eliza:

```bash
cp day-33-support-assistant-kotlin/.env.example \
  day-33-support-assistant-kotlin/.env
```

Заполните только локальный `.env`:

```text
LLM_API_KEY=<OAuth token>
```

Caller environment имеет приоритет над `.env`. Если локальный Day 33 token не
задан, run script использует только `LLM_API_KEY` из Day 1 `.env`; остальные
настройки оттуда не импортируются.
Fallback читается только после проверки canonical Eliza URL.

Если Java не доверяет Yandex CA:

```bash
day-33-support-assistant-kotlin/scripts/setup-yandex-ca.sh
```

`.env` и `.certs/` ignored.

## Команды

### MCP smoke

```bash
day-33-support-assistant-kotlin/scripts/run-support.sh --args="mcp-smoke"
```

Проверяет настоящий embedded MCP, `tools/list`, `TCK-1001` и связанного
`USR-1001`, без LLM.

### Deterministic fixture demo

```bash
day-33-support-assistant-kotlin/scripts/run-support.sh --args="fixture-demo"
```

Использует настоящий MCP + hash RAG + deterministic responder. Network LLM
calls: `0`.

### Prompt dry run

```bash
day-33-support-assistant-kotlin/scripts/run-support.sh \
  --args="prompt-dry-run TCK-1001 Почему не работает авторизация?"
```

Печатает budgets, allowed source/fact IDs и названия data boundaries. Не
печатает prompt body, token, header или raw model output.

### Offline evaluation

```bash
day-33-support-assistant-kotlin/scripts/run-support.sh --args="eval-dry-run"
```

Проверяет committed scenarios и safety cases: locked account, OTP clock skew,
billing, unknown/weak retrieval, missing ticket/user, prompt injection ignored
при обычном grounded `answered`, malformed JSON, forged/duplicate IDs и
ticket-switch isolation, а также unsafe credential-sharing action.

### Один live Eliza answer

```bash
day-33-support-assistant-kotlin/scripts/run-support.sh \
  --args="ask TCK-1001 Почему не работает авторизация?"
```

Максимум один bounded Eliza call. Missing ticket или weak retrieval
останавливаются локально до создания LLM client.

### Interactive chat

```bash
day-33-support-assistant-kotlin/scripts/run-support.sh --args="chat"
```

Команды:

```text
/ticket TCK-1002
/context
/sources
/clear
/exit
```

История хранится только в RAM и bounded числом turns. `/ticket` очищает
history и last evidence до следующего вопроса.
При сборке prompt evidence chunks удаляются целиком с конца pack, пока
полученный prompt не войдёт в `PROMPT_MAX_CHARS`; если не помещается даже один
chunk, ответ становится локальным `unknown`, а не exception.

## Expected fixture output

Сокращённый фрагмент:

```text
=== ACCOUNT_LOCKED ===
QUESTION: Почему не работает авторизация?
TICKET:
  id=TCK-1001
  errorCode=ACCOUNT_LOCKED
MCP TOOLS USED: support_get_ticket, support_get_user
CURRENT CONTEXT FACTS:
  ticket.errorCode = ACCOUNT_LOCKED
RETRIEVED SOURCES:
  authentication.md#auth-locked
ANSWER: Для текущего тикета причина входа — защитная блокировка...
ACTION STEPS:
  1. Не повторяйте вход 15 минут.
KNOWLEDGE SOURCES: authentication.md#auth-locked
CONTEXT FACT IDS: ticket.errorCode, ticket.failedAuthAttempts, user.accountState
CHECK: grounded=true, MCP context valid=true, current-ticket isolation valid=true, LLM calls=0

=== INVALID_OTP / CLOCK_SKEW ===
...
KNOWLEDGE SOURCES: authentication.md#auth-otp-clock
CONTEXT FACT IDS: ticket.errorCode, ticket.deviceClockSkewSeconds, user.accountState
CHECK: grounded=true, MCP context valid=true, current-ticket isolation valid=true, LLM calls=0
```

## Failure behavior

| Ситуация | Поведение |
|---|---|
| Missing ticket | только `support_get_ticket`, local `NOT_FOUND`, `LLM calls=0` |
| Missing linked user | local `NOT_FOUND`, `LLM calls=0` |
| Weak/off-topic retrieval | canonical `unknown`, `LLM calls=0` |
| MCP tools отличаются от exact allowlist | fail closed |
| MCP port уже занят | embedded server не стартует и не подключается к чужому listener |
| Custom LLM destination | rejected до shared-token fallback и HTTP |
| Fixture/knowledge изменены без review | local cloud preflight, HTTP не вызывается |
| Oversized/symlink/duplicate/tampered/stale RAG cache | ignored и rebuilt из fresh reviewed corpus; unique temp не следует symlinks |
| Sensitive-looking value в question/history | local cloud preflight, `LLM calls=0` |
| Non-2xx/malformed/oversized LLM response | canonical `unknown`, raw body не печатается |
| Forged/duplicate source/fact IDs | canonical `unknown` |
| Cross-ticket/user reference | canonical `unknown`, isolation check fails |
| Ticket switch | history и last evidence очищаются |

## Основные limits

Настраиваются через `.env.example`:

```text
QUESTION_MAX_CHARS=500
RAG_TOP_K=4
RAG_MIN_RELEVANCE=0.12
RAG_MAX_CHUNK_CHARS=1800
RAG_MAX_EVIDENCE_CHARS=5200
PROMPT_MAX_CHARS=12000
CHAT_HISTORY_TURNS=4
LLM_MAX_OUTPUT_TOKENS=700
LLM_MAX_RESPONSE_BYTES=131072
```

MCP host разрешён только как `127.0.0.1` или `localhost`.

## Видео 3–5 минут

1. Показать этот README, `synthetic=true`, два auth tickets и knowledge docs.
2. Показать два read-only MCP tools и запустить `mcp-smoke`.
3. Запустить `fixture-demo` для одинакового вопроса на `TCK-1001` и
   `TCK-1002`.
4. Подчеркнуть разные context facts, diagnosis, actions, source IDs и fact IDs.
5. Показать:
   `CHECK: grounded=true, MCP context valid=true, current-ticket isolation valid=true, LLM calls=0`.
6. При настроенном OAuth выполнить один `ask TCK-1001 ...`; не показывать
   `.env`, token, header или raw request.
7. Завершить missing ticket или off-topic вопросом, который локально даёт
   safe unknown без выдуманного ответа.

## Acceptance checklist

- [x] Kotlin/JVM 21, multi-file module.
- [x] Direct Eliza REST, без high-level LLM SDK.
- [x] Official MCP Kotlin SDK `0.13.0`.
- [x] Loopback Streamable HTTP MCP.
- [x] Ровно два read-only tools.
- [x] Strict synthetic JSON repository.
- [x] FAQ/docs RAG со stable source IDs и hash embeddings.
- [x] Ticket-aware retrieval query.
- [x] Bounded immutable evidence/prompt input.
- [x] Strict model JSON and grounding validator.
- [x] Server-owned current context rendering.
- [x] Ticket-switch history isolation.
- [x] Offline smoke/demo/evaluation modes.
- [x] Unit and integration tests.
- [x] Secrets/runtime/build outputs ignored.

## Ограничения

- `hash-v1` нужен для repeatable offline demo, но уступает semantic embeddings
  на большом corpus.
- Grounding validator снижает риск hallucination, но не является формальным
  доказательством истинности model prose.
- Sensitive-value detector намеренно консервативен: сомнительный live input
  лучше остановить локально и переформулировать без credentials/PII.
- MCP server transient: после завершения CLI он останавливается.
- Chat не сохраняется между process restarts.
- Day 33 v1 не выполняет support actions и не подключается к реальной CRM.
