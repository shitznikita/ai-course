# День 35. AI-подготовка реального релиза
## Какую задачу я решаю
Я автоматизирую подготовку релиза для реального репозитория `ai-course`.
Перед pull request или релизом разработчику обычно нужно:
1. понять полный состав изменений;
2. убедиться, что release candidate имеет ожидаемую форму;
3. запустить `git diff --check` и сборку нужного Gradle-модуля;
4. подготовить краткое описание, release notes, риски и сценарий видео;
5. не перепутать мнение модели с фактической готовностью релиза.
## Где участвует AI
Серверная часть сама получает факты из Git и локальных проверок:
- base, merge base, HEAD и tree/index fingerprints;
- полный manifest всех изменённых файлов;
- Git status, additions/deletions, modes и object IDs;
- результаты фиксированных checks;
- итоговый `READY_FOR_HUMAN_REVIEW` или `BLOCKED`.
AI получает только две целые typed evidence items:
- server-owned факт изменения module `README.md`: path, status,
  additions/deletions и manifest fingerprint, без README content/patch;
- маленький reviewed `release-brief.json` с четырьмя фиксированными полями:
  objective, highlights, operational notes и video focus.

Полный manifest передаётся как inventory-only metadata. Содержимое и patches
README, `build.gradle.kts`, source/test и root metadata в model prompt не входят.
AI пишет только человекочитаемые:
- summary;
- release notes;
- setup/process/config/compatibility/operational risks;
- шаги для видео;
- advisory recommendation.
Каждый AI-пункт обязан содержать точные `evidencePaths`.
README fact разрешает только claim о самом изменении документа. Semantic claims
разрешает reviewed brief. Остальные файлы остаются manifest-only.
AI не может approve, merge, tag, publish или deploy.
## Видимый результат
```text
SOURCE: real git release candidate
BRANCH: AICOURSE-5
BASE: origin/main -> ...
MERGE BASE: ...
HEAD: ...
MODULE: day-35-ai-release-prep-kotlin
MANIFEST COVERAGE: N/N complete
ROOT POLICY: PASS
SETTINGS MEMBERSHIP: PASS
CHECK git-diff-check: PASS
CHECK :day-35-ai-release-prep-kotlin:build: PASS
SNAPSHOT AFTER CHECKS: STABLE
AI EVIDENCE COVERAGE: K/N complete items
MANIFEST-ONLY PATHS: ...
PROMPT: .../100000 bytes; CONTEXT <= .../131072
DAY 35 ELIZA CREDENTIAL READS: 0
DAY 35 ELIZA HTTP CALLS: 0
DAY 35 ELIZA LLM CALLS: 0
DRY RUN: PASS; no report written; existing live report untouched
```
Эти counters относятся только к Day 35 Eliza credential/HTTP/LLM; launcher
`installDist` и derived Gradle build — отдельные non-hermetic local checks.
Live mode дополнительно валидирует AI JSON и атомарно создаёт ignored report:
```text
day-35-ai-release-prep-kotlin/reports/release-readiness.md
```
## Стек
- Kotlin/JVM `2.3.21`;
- Java toolchain `21`;
- Gradle application;
- `kotlinx-serialization-json:1.9.0`;
- Git CLI с фиксированными argv;
- прямой REST через `java.net.http.HttpClient`;
- Eliza/OpenRouter route и модель зафиксированы в коде.
High-level LLM SDK и готовые agent frameworks не используются.
## Архитектура
```text
fixture/eval
  -> strict synthetic contract
  -> no provider/credential/client construction

prepare* + validated base ref
  -> pinned provider preflight
  -> clean canonical Git snapshot
  -> complete merge-base..HEAD manifest
  -> exact root + one-day-module policy
  -> git diff --check + derived Gradle build
  -> stable snapshot gate
  -> two whole typed evidence items + prompt budget
  -> dry-run stop OR one Eliza REST call
  -> strict AI validation
  -> final snapshot gate
  -> server-owned readiness + atomic report
```
Production-файлы:
| Файл | Ответственность |
|---|---|
| `AppConfig.kt` | pinned provider, delayed credential source, bounded prose disclosure policy |
| `Models.kt` | strict JSON, typed contracts, limits, SHA-256 |
| `RepositorySnapshot.kt` | bounded commands, Git manifest, policy, checks, snapshots |
| `ReleaseEvidence.kt` | strict reviewed brief, typed evidence admission, prompt |
| `ElizaClient.kt` | one-call direct REST, no redirects, timeouts, response cap |
| `ReleasePlan.kt` | strict model validation and server decision |
| `ReleaseReport.kt` | escaped Markdown and atomic ignored reports |
| `Main.kt` | four modes and repeated snapshot gates |
## Полный manifest
Manifest содержит каждый path из `mergeBase..HEAD`, максимум 80:
- status;
- additions/deletions или `binary=true`;
- old/new Git mode;
- old/new object ID;
- item SHA-256.
Три NUL-terminated Git streams (`--raw`, `--name-status`, `--numstat`)
разбираются отдельно и обязаны иметь одинаковый полный path set.
Используется единая политика `--no-renames`: rename виден как delete + add.
Malformed UTF-8, controls, traversal, duplicate/mismatched streams и 81-й файл
останавливают pipeline без partial coverage.
Разрешён ровно один module вида `day-NN-...-kotlin` и только root paths:
```text
.gitignore
AGENTS.md
README.md
settings.gradle.kts
skills/course-continuity/SKILL.md
```
Module README и `release-brief.json` обязаны быть semantic A/M text changes:
non-binary, positive numstat. Exact module include обязан ровно один раз
присутствовать в HEAD `settings.gradle.kts`; README, build и brief обязаны быть
обычными non-symlink files.
Build task не принимается от caller или модели: сервер выводит только
`:<validated-module>:build`.
## Snapshot и checks
Начальный `SnapshotFingerprint` включает:
- canonical repository path;
- branch и HEAD SHA;
- HEAD tree SHA;
- index tree SHA;
- SHA-256 полного porcelain-v2 status.
Pipeline требует clean tracked/staged/untracked-unignored state и равенство
HEAD tree/index tree. Затем запускаются:
```bash
git diff --check <merge-base>..<head>
./gradlew --no-daemon --console=plain :<validated-module>:build
```
После checks snapshot обязан совпасть byte-for-byte.
В live mode snapshot проверяется ещё раз непосредственно перед report replace.
Late drift отбрасывает AI output и не публикует новый READY report. Предыдущий
live report, если он есть, сохраняется и относится только к embedded snapshot.
То же правило действует для provider preflight, Git/check, credential, HTTP и
model-validation failures: старый report не удаляется и не меняется. Только
успешный post-force snapshot gate разрешает atomic replace.
## Prompt и model contract
Финальный prompt детерминирован: volatile check durations в него не входят.
Порядок фиксирован: identity, sorted manifest, typed README change fact, reviewed
release brief. Commit subjects не собираются и не передаются модели.
Ограничения:
```text
prompt UTF-8 bytes <= 100000
prompt bytes + 4096 framing + 2048 output <= 131072
```
UTF-8 byte считается консервативной верхней границей одного token.
Каждая typed item включается только целиком. Оба элемента обязательны; если
reviewed brief или aggregate evidence/prompt не помещаются, запуск fail-closed
без усечения текста и без model call.
Commit subjects намеренно не входят в prompt: этот необязательный произвольный
prose-канал удалён, потому что он не может служить citation-authorized evidence.
Model возвращает strict JSON без unknown fields:
```json
{
  "summary": [{"text": "...", "evidencePaths": ["..."]}],
  "releaseNotes": [{"text": "...", "evidencePaths": ["..."]}],
  "risks": [{
    "severity": "LOW",
    "text": "...",
    "mitigation": "...",
    "evidencePaths": ["..."]
  }],
  "videoSteps": [{"text": "...", "evidencePaths": ["..."]}],
  "recommendation": "PROCEED"
}
```
Counts, enums, bytes, recursive duplicate JSON keys, controls и каждый
evidence path проверяются до terminal/Markdown/filesystem sinks. Каждый AI item
обязан цитировать `REVIEWED_RELEASE_BRIEF`; README-only semantic claims
отклоняются. Одна shared
unsafe/benign matrix проходит через reviewed brief, полный PromptRenderer, raw
provider content и все model prose/path fields.
Credential-shaped negative fixtures хранятся encoded и декодируются только
в local tests: raw PR patch/blob остаётся допустимым для fail-closed Day 32
cloud review policy без ослабления policy или Day 35 assertions.

Политика не парсит Kotlin/Gradle/Markdown/shell и не содержит call/callee grammar:
она применяется только к bounded brief/model prose. Sink value обязан уже быть
NFKC-canonical и не содержать Unicode FORMAT; canonical nonblankness и
canonical uniqueness проверяются до render. Для inspection декодируются полные
`\uNNNN`/`\UNNNNNNNN`/`\xNN`/octal encodings. Любое non-placeholder значение
в явном credential assignment, call argument или OAuth/Bearer/Basic value
отклоняется независимо от алфавита, регистра или entropy. Literal Windows/UNC paths,
explanatory Authorization/Bearer prose, `Env.LLM_API_KEY`/`Env.LLM_MODEL` и
benign Authorization-prefixed identifiers разрешены; exact placeholders
разрешены только как complete values.
## Четыре режима
### 1. Fixture без Day 35 Eliza calls
```bash
day-35-ai-release-prep-kotlin/scripts/run-release-prep.sh fixture-demo
```
Показывает downstream contract без Git/check/API claims:
```text
SOURCE: synthetic downstream fixture
REAL GIT/CHECK EXECUTION: NOT CLAIMED
MANIFEST COVERAGE: 3/3 complete
AI EVIDENCE COVERAGE: 2/3 complete items
SERVER READINESS: READY_FOR_HUMAN_REVIEW
DAY 35 ELIZA LLM CALLS: 0
```
Fixture report имеет отдельное имя `fixture-release-readiness.md`.
Day 35 Eliza credential source не конструируется; Eliza HTTP/LLM calls нет.
Launcher перед fixture всё равно запускает non-hermetic `installDist`, который
может читать HOME files/caches/configuration и использовать repositories/network.
### 2. Evaluation без Day 35 Eliza calls
```bash
day-35-ai-release-prep-kotlin/scripts/run-release-prep.sh eval-dry-run
```
Проверяет valid contract и отклоняет manifest-only path, README-only semantic
citation, unknown model field и duplicate model key: `5/5 PASS`.
Day 35 Eliza credential source не конструируется, Eliza HTTP/LLM calls и report
отсутствуют. Launcher `installDist` остаётся non-hermetic и может читать local
files/caches/configuration и использовать repositories/network.
### 3. Реальный dry-run
Сначала caller самостоятельно обновляет base ref:
```bash
git fetch origin main
day-35-ai-release-prep-kotlin/scripts/run-release-prep.sh \
  prepare-dry-run --base origin/main
```
Это обязательное доказательство работы в реальном окружении: реальный Git range,
полный manifest и настоящая Gradle build. Day 35 Eliza credential source не
конструируется, Eliza HTTP/LLM calls отсутствуют. Dry-run не создаёт, не заменяет
и не удаляет live report; поэтому старый ignored `release-readiness.md` может
остаться и должен читаться только вместе с его snapshot fingerprint. Launcher
`installDist` и derived build non-hermetic: они могут читать local
files/caches/configuration и использовать repositories/network.
### 4. Optional live prepare
```bash
cp day-35-ai-release-prep-kotlin/.env.example \
  day-35-ai-release-prep-kotlin/.env
# заменить placeholder реальным OAuth token
day-35-ai-release-prep-kotlin/scripts/setup-yandex-ca.sh
git fetch origin main
day-35-ai-release-prep-kotlin/scripts/run-release-prep.sh \
  prepare --base origin/main
```
Provider зафиксирован:
```text
https://api.eliza.yandex.net/openrouter/v1/chat/completions
meta-llama/llama-3.3-70b-instruct
OAuth
```
Любой отличающийся `LLM_API_URL`, `LLM_MODEL` или `LLM_AUTH_SCHEME`
останавливает запуск до lookup ключа и HTTP.
Неуспешный live attempt на любом этапе сохраняет предыдущий report byte-for-byte;
новый файл заменяет его только после успешной финальной snapshot-проверки.
Credential source читает только caller `LLM_API_KEY`, затем bounded non-symlink
Day 35 `.env`, затем Day 1 `.env`.
Launcher не source-ит `.env`; bootstrap, app и in-app children используют
minimal allowlist без JVM/Gradle startup и Git repository/index/object/ref
selectors. Truststore password — direct JVM argument, не inherited `JAVA_OPTS`;
non-hermetic bootstrap/build не входят в Day 35 Eliza counters.
## Проверка
```bash
./gradlew :day-35-ai-release-prep-kotlin:test
./gradlew :day-35-ai-release-prep-kotlin:build
day-35-ai-release-prep-kotlin/scripts/run-release-prep.sh fixture-demo
day-35-ai-release-prep-kotlin/scripts/run-release-prep.sh eval-dry-run
```
На clean committed branch:
```bash
git fetch origin main
day-35-ai-release-prep-kotlin/scripts/run-release-prep.sh \
  prepare-dry-run --base origin/main
```
Запустите real dry-run дважды. Snapshot, manifest и prompt SHA-256 должны
совпасть; check durations могут отличаться.
## Границы доверия
- V1 предназначен только для собственной trusted local release-candidate ветки.
- Gradle выполняет branch-controlled code с правами пользователя; это не sandbox.
- Arbitrary PR heads и CI без sandbox не поддерживаются.
- Pipeline не fetch-ит, push-ит, merge-ит, tag-ит, publish-ит и deploy-ит.
- README/build/source/test/root patches не отправляются в cloud; из repository
  content передаётся только reviewed `release-brief.json`.
- Запрос, уже отправленный до внешнего checkout drift, нельзя отозвать;
  финальный gate гарантирует, что новый report не будет опубликован для stale
  snapshot. Предыдущий report сохраняется неизменным и идентифицирует свой
  snapshot внутри файла.
- Gradle verification не hermetic: JDK, caches, repositories и network влияют.
- Bounded brief/model disclosure policy не заменяет repository-wide secret
  scanner; безопасность произвольного source обеспечивается его
  нетрансмиссией, а не попыткой распарсить все языки.
- Provider route/model/context могут измениться; fixture не доказывает live API.
- Path/snapshot checks рассчитаны на trusted single-user workstation, не на
  hostile concurrent local process.
## Сценарий видео, 3–5 минут
1. Сказать задачу: «Я автоматизирую подготовку релиза для `ai-course`».
2. Показать разделение deterministic facts и AI-authored release prose.
3. Запустить `fixture-demo`: отметить synthetic source/report, отсутствие Day 35
   Eliza credential-source construction и HTTP/LLM calls; `installDist` non-hermetic.
4. Выполнить `git fetch origin main`, затем показать
   `git diff --stat origin/main...HEAD`.
5. Запустить обязательный `prepare-dry-run --base origin/main`.
6. Выделить `N/N` manifest, две typed evidence items, exact module/build task,
   PASS checks, stable snapshot,
   отсутствие Day 35 Eliza credential/HTTP/LLM effects; derived
   Gradle build может использовать local caches/configuration/repositories/network.
   Отметить, что dry-run не меняет старый live report, если он уже существовал.
7. При доступном OAuth ещё раз выполнить `git fetch origin main`, затем один раз
   запустить `prepare` и открыть ignored report.
8. Если доступа нет, честно назвать blocker и не выдавать fixture за live AI.
Финальная фраза:
> Я автоматизировал подготовку релиза для ai-course: pipeline полностью
> инвентаризирует реальный Git release candidate и запускает Gradle-проверки,
> а AI по typed README facts и reviewed release brief готовит release notes,
> риски и сценарий видео; произвольные repository patches в cloud не уходят.
## Что сдавать
- код Day 35;
- видео по сценарию выше;
- задача: **AI-подготовка release-readiness отчёта для реальной ветки ai-course**.
