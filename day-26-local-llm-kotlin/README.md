# Day 26: Запуск Локальной LLM

## Цель

Запустить локальную генеративную LLM, обратиться к ней через CLI и HTTP API и получить ответы на три запроса разной сложности.

Выбранный runtime — [Ollama](https://ollama.com/), модель — [`qwen3:14b`](https://ollama.com/library/qwen3%3A14b). Это 14.8B-модель в Q4_K_M-квантизации размером около 9.3 GB. На Apple Silicon она использует unified memory.

## Что Делает Код

```text
Kotlin CLI -> http://127.0.0.1:11434/api/chat -> Ollama -> qwen3:14b -> ответ
```

Код использует только `java.net.http.HttpClient` и локальный Ollama API:

- `diagnose` получает версию daemon и список установленных моделей;
- `demo` после diagnostic preflight отправляет ровно три chat HTTP-запроса возрастающей сложности;
- `ask <текст>` отправляет один пользовательский запрос;
- ответ выводится вместе с моделью, input/output token counts, latency и скоростью генерации.

`OLLAMA_BASE_URL` проходит строгую проверку: допустимы только `http://127.0.0.1:11434`, `http://localhost:11434` или `[::1]`. Поэтому программа не сможет случайно отправить prompt в Eliza или облачный endpoint.

Стек: Kotlin CLI, Gradle, JDK 21, `kotlinx-serialization-json`, прямой REST через `java.net.http.HttpClient`. OAuth, API-ключи и high-level LLM SDK не используются.

## Подготовка

Ollama уже должен быть установлен. Первоначальная загрузка модели требует интернет и около 9.3 GB диска; после неё все запросы Day 26 идут в loopback API локальной машины.

В первом терминале запустите daemon, только если он ещё не работает:

```bash
ollama serve
```

Во втором терминале скачайте модель и проверьте её наличие:

```bash
ollama pull qwen3:14b
ollama list
```

Если `qwen3:14b` слишком медленна для конкретной машины, можно вручную задать `OLLAMA_MODEL=qwen3:8b`; default и основной сценарий задания остаются на 14B.

## CLI и HTTP API

CLI smoke test:

```bash
ollama run qwen3:14b "Ответь одним предложением: что такое локальная LLM?"
```

Эквивалентный локальный HTTP API-запрос:

```bash
curl http://127.0.0.1:11434/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen3:14b",
    "messages": [{"role": "user", "content": "Что такое локальная LLM?"}],
    "stream": false,
    "think": false
  }'
```

Ollama API не требует аутентификации при обращении через `localhost`. Документация: [chat API](https://docs.ollama.com/api/chat), [tags API](https://docs.ollama.com/api/tags), [локальная аутентификация](https://docs.ollama.com/api/authentication).

## Запуск Kotlin Demo

Сборка:

```bash
./gradlew :day-26-local-llm-kotlin:build
```

Диагностика локального daemon и модели:

```bash
day-26-local-llm-kotlin/scripts/run-local-llm.sh --args="diagnose"
```

Три обязательных запроса:

```bash
day-26-local-llm-kotlin/scripts/run-local-llm.sh
```

Один произвольный запрос:

```bash
day-26-local-llm-kotlin/scripts/run-local-llm.sh --args='ask "Назови один плюс локальной модели."'
```

Настройки можно положить в локальный `.env` (он игнорируется Git):

```text
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_MODEL=qwen3:14b
OLLAMA_REQUEST_TIMEOUT_SECONDS=300
OLLAMA_KEEP_ALIVE=5m
```

Переменные окружения из shell имеют приоритет над `.env`. Смотрите безопасные значения в `.env.example`.

## Ожидаемый Вывод

`diagnose` покажет адрес `127.0.0.1`, версию Ollama и статус `qwen3:14b (installed)`. Если daemon не запущен, программа подскажет `ollama serve`; если модель отсутствует — `ollama pull qwen3:14b`.

`demo` напечатает три блока:

```text
========== REQUEST 1/3: 1. SIMPLE — one fact ==========
PROMPT:
В одном предложении: что такое локальная LLM?
ANSWER:
...
MODEL USED: qwen3:14b
TOKENS: input=..., output=...
LATENCY: client=..., ollama=..., load=..., generation=...
SPEED: ... tokens/s

RESULT: 3/3 responses are non-empty.
```

После demo можно дополнительно подтвердить, что модель загружена в память:

```bash
ollama ps
```

## Видео

1. Показать README и `ollama list` — модель установлена локально.
2. Показать `ollama run qwen3:14b "..."` и ответ через CLI.
3. Показать HTTP URL `127.0.0.1:11434` и выполнить Kotlin `diagnose`.
4. Запустить Kotlin `demo`, показать все три ответа и метрики.
5. Выполнить `ollama ps` и пояснить: Q4-квантизация делает 14B-модель доступной локально, а Day 26 не использует облачный endpoint.

Не показывайте в видео `.env` других дней с реальными токенами.

## Проверка Требований

- Локальная LLM установлена и запущена: `ollama serve` + `qwen3:14b`.
- К ней можно обратиться через CLI: `ollama run ...`.
- К ней можно обратиться через HTTP API: `/api/chat` из Kotlin и `curl`.
- Простой запрос получает ответ: первый запрос demo.
- Минимум три запроса разной сложности: `demo` выполняет 3/3.
- Видео + код: README содержит сценарий записи и все команды.
