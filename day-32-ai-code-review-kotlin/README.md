# День 32: безопасный AI code review для pull request

## Цель

Kotlin CLI получает **ограниченный diff pull request**, запрашивает review у Eliza
прямым REST-вызовом через `java.net.http.HttpClient`, валидирует результат и
обновляет один комментарий на русском языке. PR title, имена файлов, patches и
содержимое changed files — недоверенные данные, а не команды.

## Архитектура и граница доверия

```text
same-repository, non-draft pull request
  -> pull_request_target workflow from trusted base branch
  -> checkout only immutable base SHA (no credentials)
  -> Kotlin GitHub REST client: metadata/files/patches/changed blobs as inert data
  -> shared CloudContentPolicy: raw path/patch/decoded blob preflight
  -> typed diff with added-line anchors and visible truncation
  -> trusted base checkout: git ls-files allowlist -> structured chunks
  -> hybrid hash + lexical/path RAG over documentation AND existing code
  -> whole file/evidence items -> typed TransmittedReviewInput
  -> at most 3 bounded review batches -> final policy gate -> direct Eliza REST
  -> validate and count coverage only from transmitted input
  -> GitHub REST: replace one sticky Russian comment
```

Workflow [`.github/workflows/ai-code-review.yml`](../.github/workflows/ai-code-review.yml)
имеет минимальные permissions:

```yaml
contents: read
pull-requests: write
```

`pull_request_target` использует secret только при guards: PR направлен в
**default branch этого repository**, head тоже находится в этом repository и
`draft == false`. Это важно: произвольная same-repository base branch тоже может
содержать подменённый wrapper/script и поэтому не считается trusted. Workflow
checkout-ит явный
`${{ github.event.pull_request.base.sha }}` с `persist-credentials: false`;
head revision не checkout-ится, не `source`-ится, не build-ится и не выполняется.
Kotlin GitHub REST client получает metadata/files только после запуска trusted
base revision; они остаются inert data.

RAG строится заново в памяти на каждом run и читает только tracked файлы
trusted base checkout. В allowlist входят root `AGENTS.md`/`README.md`, docs,
module README/API schemas, Kotlin/Java source и конфигурация. `.git`, `.env`,
`.certs`, build/runtime/reports/index/state, symlinks, binary и oversized файлы
исключаются. Common private-key, Bearer/provider-token и credential-literal
patterns fail closed, но это дополнительная эвристика, а не замена secret
scanning и review repository policy. Та же `CloudContentPolicy` проверяет raw
changed path, provider patch, bounded full diff и decoded text blob до
prompt/model, а
`ElizaLlmClient` повторяет final prompt gate. При совпадении весь cloud review
останавливается: model HTTP calls = 0, coverage = 0, а diagnostic не содержит
путь, matched value, patch или blob.
Только basename `.env.example` разрешён как шаблон конфигурации, но его patch и
decoded content всё равно проходят credential scanning. `.env`, остальные
`.env*` варианты и директории с таким именем остаются запрещёнными путями.
Policy извлекает целые quoted/escaped object values, поэтому распознаёт
`apiKey`, `password`, prefixed AWS secret keys и значения с обычными password
символами вроде `$`. `Authorization` проверяется для scheme, принятой узкой
валидацией `AppConfig` (включая configured `OAuth`), в plain, quoted JSON и
backslash-escaped JSON формах; exemption получают только exact известные
placeholder values или структурные environment/template references без
произвольного literal fallback.

Evidence pack квотирует документацию и код, сохраняет точные `sourceId` и line
ranges; privileged cache отсутствует. Evidence chunk никогда не режется
посередине: item либо передаётся целиком, либо не авторизуется для citation.

Changed head content не входит в trusted corpus: patch и bounded blobs передаются
отдельными недоверенными блоками. Binary changes остаются metadata-only.

`TransmittedReviewInput` — единственный source of truth после prompt budget. Он
содержит только целые переданные file items, exact statuses/patches и целые RAG
items. Поздний файл, incomplete/truncated patch или не поместившийся source не
считается reviewed; validator отвергает omitted path/line/sourceId. Полное file
content может быть целиком omitted ради budget, если полный patch поместился;
это явно отражается в prompt и coverage.

В CI repository secret `secrets.LLM_API_KEY` напрямую маппится на одноимённую
переменную. Заполненный
`.env` не создаётся; `GITHUB_TOKEN` используется только для GitHub REST и не
попадает ни в prompt, ни в comment.

Official actions pinned полными SHA, проверенными по GitHub tag refs 17 июля 2026:

| Action | Tag | Immutable SHA |
|---|---|---|
| `actions/checkout` | `v4.2.2` | `11bd71901bbe5b1630ceea73d27597364c9af683` |
| `actions/setup-java` | `v4.7.1` | `c5195efecf7bdfc987ee8bae7a71cb8b11521c00` |

### Ограничение introducing PR

`pull_request_target` выполняет workflow из base branch. Поэтому PR, который
впервые добавляет или меняет reviewer, не может быть проверен новой версией до
merge. После merge откройте отдельный non-draft PR из этого же repository в
default branch. Fork PR, draft PR и PR в другую base branch намеренно
пропускаются.

## Стек

- Kotlin/JVM 21 + Gradle;
- `kotlinx.serialization` для bounded JSON;
- direct Eliza/OpenRouter REST через `java.net.http.HttpClient`;
- GitHub Actions + GitHub REST без исполнения PR head.

## Подготовка

Offline modes требуют только JDK 21:

```bash
./gradlew :day-32-ai-code-review-kotlin:test
./gradlew :day-32-ai-code-review-kotlin:build
```

Для live локального review:

```bash
cp day-32-ai-code-review-kotlin/.env.example \
  day-32-ai-code-review-kotlin/.env
day-32-ai-code-review-kotlin/scripts/setup-yandex-ca.sh
```

OAuth token хранится только в ignored `.env` или environment. Environment
вызывающего процесса имеет приоритет над `.env`.

## Команды

```bash
# Deterministic committed fixtures: no Eliza, no GitHub token.
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="fixture-demo"

# Committed evaluation cases and guards without a live model.
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="eval-dry-run"

# Bounded prompt/input shape; no Eliza call.
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="prompt-dry-run"

# One bounded Eliza call over the committed fixture; no GitHub comment.
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="live-fixture"

# CI only: GitHub Actions supplies PR identity, SHAs and GITHUB_TOKEN.
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="ci"
```

`fixture-demo` показывает изменённый файл, diff stats, минимум один
documentation и один code chunk, затем валидированные `BUG`, `ARCHITECTURE` и
`RECOMMENDATION`. `eval-dry-run` прогоняет nullability, architecture,
recommendation, clean/binary/oversized cases, prompt injection и
malformed/ungrounded output. `prompt-dry-run` печатает bounded prompt metadata,
число transmitted/omitted files, только авторизованные source IDs и целые явно
размеченные data blocks — без OAuth token,
`GITHUB_TOKEN` и Authorization header.

`live-fixture` делает максимум один Eliza call и пишет только прошедший validation
report в ignored `reports/review.md`; в GitHub ничего не публикуется.

## Cloud privacy policy

Workflow отправляет bounded diff/changed text и выбранные RAG chunks только в
настроенный Eliza endpoint. Включайте его лишь когда policy приватного
repository разрешает такую обработку кода и документации. Если cloud processing
запрещён, не добавляйте `LLM_API_KEY`: нужен отдельный self-hosted runner с
локальным provider. GitHub-hosted runner не предполагает Ollama. MCP здесь не
нужен — GitHub REST и trusted base checkout дают детерминированный контекст с
меньшей privileged surface.

## Expected GitHub comment

В PR появляется один sticky comment:

```text
<!-- ai-code-review:day-32 -->
# 🤖 AI Code Review

## Потенциальные баги / Potential bugs
### `HIGH` — Возможный NPE (`src/ReviewTarget.kt:6`)
nullable result разыменовывается через `!!`; при `null` запрос завершится
исключением.

**Что сделать:** добавить guard и test.

## Покрытие и ограничения
- GitHub сообщил файлов: `…`; получено: `…`.
- Проверено: только file items, реально отправленные в успешный model call.
- Неполный patch: `…`; неполное содержимое: `…`.

_Комментарий обновляется при новом push. Ассистент не approve-ит PR и не
запускает код из ветки PR._
```

Без подтверждённых проблем раздел честно сообщает `Высокоуверенных проблем не
найдено.` и сохраняет coverage/truncation. Marker в первой строке позволяет
безопасно найти и обновить только bot comment. Renderer использует только
прошедший validation structured review result.

## GitHub Actions setup и видео

В repository settings добавьте Actions secret:

```text
LLM_API_KEY=<OAuth token for Eliza>
```

Нужен именно repository secret `LLM_API_KEY`.

После merge Day 32:

1. покажите workflow guards, SHA pins, minimal permissions и base-only checkout;
2. покажите fixture target/diff и запустите `fixture-demo`;
3. укажите doc+code RAG evidence и три review sections с anchors;
4. создайте отдельный non-draft same-repository PR с документированным плохим patch;
5. покажите Actions run и один Russian sticky comment с coverage/limits;
6. push-ните исправление и покажите, что тот же comment обновился, а finding исчез;
7. закройте demo PR без merge; introducing PR, fork PR и draft PR skipped.

Не показывайте `.env`, `LLM_API_KEY`, `GITHUB_TOKEN`, truststore, raw request,
полный private diff или raw model response.

## Лимиты и fail-closed поведение

- максимум 60 changed files, 600 KB changed content и 3 model calls по умолчанию;
- если GitHub сообщает больше configured changed-file limit, run fail-closes до
  file/diff/blob fetch и Eliza: частичный review непроверенного хвоста запрещён;
- full diff/patch/blob и RAG corpus имеют независимые file/byte/chunk budgets;
- prompt budget работает целыми file/evidence items, без byte slicing item;
- binary, incomplete, skipped, omitted и truncated inputs не считаются reviewed
  и всегда отражаются в coverage;
- finding допустим только для exact transmitted path и добавленной строки его
  полного transmitted patch;
- каждый finding обязан сослаться только на целиком transmitted `sourceId`;
- sensitive changed path/patch/blob останавливает review до Eliza и публикует
  только non-echo diagnostic с coverage 0;
- sensitive model-controlled `title`/`detail`/`recommendation` отклоняются
  validator до `ValidatedFinding`, renderer и sticky comment;
- malformed JSON, неизвестные enum/path/line/source, дубли и excess prose
  заменяются короткой diagnostic summary, raw model output не публикуется;
- reviewer публикует только comment: он не approve-ит и не request-changes PR.

## Проверка перед PR

```bash
./gradlew :day-32-ai-code-review-kotlin:test
./gradlew :day-32-ai-code-review-kotlin:build
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="fixture-demo"
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="eval-dry-run"
day-32-ai-code-review-kotlin/scripts/run-review.sh --args="prompt-dry-run"
git status --short --ignored
```
