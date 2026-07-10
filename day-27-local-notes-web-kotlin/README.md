# Day 27: Локальный Веб-Анализатор Заметок

## Цель

Интегрировать локальную LLM в реальное приложение: загрузить заметку через браузер, отправить её в локальную модель и показать структурированный ответ без облачных моделей.

Приложение принимает одну UTF-8 заметку `.txt` или `.md`, обрабатывает её в памяти и возвращает:

- краткое резюме;
- решения;
- задачи с опциональными ответственным и сроком;
- риски;
- открытые вопросы.

```text
Browser -> Ktor at 127.0.0.1:8787 -> java.net.http.HttpClient -> Ollama at 127.0.0.1:11434 -> qwen3:14b
```

Ktor отвечает только за локальную веб-страницу и multipart upload. Запрос к LLM идёт напрямую через `java.net.http.HttpClient` на Ollama `/api/chat`; SDK, OAuth, API-ключи и cloud fallback не используются.

## Что Важно Для Приватности

- Web server привязан только к `127.0.0.1` по умолчанию; `APP_HOST` принимает только `localhost`, `127.0.0.1` или `::1`.
- `OLLAMA_BASE_URL` принимает только plain loopback HTTP URL; адрес облака, query string и путь будут отклонены при запуске.
- Загруженный файл, извлечённый текст и отчёт не пишутся в файлы, базу или историю. Они существуют только в памяти текущего HTTP-запроса и в браузере.
- HTML-интерфейс выводит текст заметки и модели через DOM `textContent`, а не исполняет его как HTML.

Это не RAG и не чат по истории заметок. В первой версии не поддерживаются PDF, DOCX и OCR.

## Подготовка

Нужны JDK 21, Gradle Wrapper и установленный [Ollama](https://ollama.com/). Один раз скачайте модель (около 9.3 GB). Если daemon ещё не запущен, оставьте в первом терминале:

```bash
ollama serve
```

Во втором терминале скачайте модель и убедитесь, что она установлена:

```bash
ollama pull qwen3:14b
ollama list
```

Если `ollama serve` уже запущен как background service, отдельный терминал для него не нужен.

## Запуск

Соберите модуль:

```bash
./gradlew :day-27-local-notes-web-kotlin:build
```

Проверьте локальный daemon и наличие модели:

```bash
day-27-local-notes-web-kotlin/scripts/run-notes-web.sh --args="diagnose"
```

Запустите веб-приложение:

```bash
day-27-local-notes-web-kotlin/scripts/run-notes-web.sh
```

В терминале появится адрес:

```text
WEB APP: http://127.0.0.1:8787
```

Откройте его в браузере, выберите `.txt` или `.md` и нажмите **«Проанализировать локально»**. Для воспроизводимого demo используйте [fixtures/demo-note.md](fixtures/demo-note.md).

## Локальный HTTP API

Проверка готовности модели:

```bash
curl http://127.0.0.1:8787/api/health
```

Пример ожидаемого ответа:

```json
{
  "ready": true,
  "model": "qwen3:14b",
  "ollamaVersion": "..."
}
```

Загрузка заметки без браузера:

```bash
curl -F 'note=@day-27-local-notes-web-kotlin/fixtures/demo-note.md' \
  http://127.0.0.1:8787/api/analyze
```

Успешный ответ содержит только безопасные metadata файла, отчёт и метрики модели — исходный текст заметки обратно не возвращается:

```json
{
  "source": { "fileName": "demo-note.md", "format": "md", "charCount": 0 },
  "report": {
    "summary": "...",
    "decisions": [],
    "actionItems": [{ "task": "...", "owner": null, "deadline": null }],
    "risks": [],
    "openQuestions": []
  },
  "model": { "name": "qwen3:14b", "latencyMs": 0 }
}
```

При проблеме API возвращает JSON с `code`, понятным `message` и, когда возможно, `hint`: например, `ollama serve` для недоступного daemon или `ollama pull qwen3:14b` для отсутствующей модели.

## Формат И Ограничения Заметки

- Ровно один multipart-файл в поле `note`.
- Расширение: только `.txt` или `.md`; MIME type не является доверенным источником.
- Кодировка: строгий UTF-8.
- Размер: до `1_048_576` bytes и `24_000` Kotlin characters по умолчанию.
- Пустые, неподдерживаемые, не-UTF-8 и превышающие лимит файлы отклоняются без обрезания.

Модель получает JSON Schema в параметре `format`, `stream=false`, `think=false`, `temperature=0` и системную инструкцию не считать текст заметки командами. Это делает структуру отчёта стабильнее. См. [Ollama chat API](https://docs.ollama.com/api/chat) и [structured outputs](https://docs.ollama.com/capabilities/structured-outputs).

## Настройки

Создайте локальный `.env` рядом с этим README или задайте переменные окружения. Shell environment имеет приоритет над `.env`.

```text
APP_HOST=127.0.0.1
APP_PORT=8787
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_MODEL=qwen3:14b
OLLAMA_REQUEST_TIMEOUT_SECONDS=300
OLLAMA_KEEP_ALIVE=5m
NOTES_MAX_UPLOAD_BYTES=1048576
NOTES_MAX_CHARS=24000
```

Безопасные значения находятся в [.env.example](.env.example). `.env` уже игнорируется Git.

## Проверка

Автоматические in-process Ktor tests не требуют установленного Ollama: они подменяют анализатор и проверяют главную страницу, valid `.md`, пустые/неподдерживаемые/слишком большие файлы, missing/duplicate multipart field, недоступный/отсутствующий local model и запрет внешних URL.

```bash
./gradlew :day-27-local-notes-web-kotlin:test
```

Полная локальная проверка с реальной моделью:

```bash
./gradlew :day-27-local-notes-web-kotlin:build
day-27-local-notes-web-kotlin/scripts/run-notes-web.sh --args="diagnose"
day-27-local-notes-web-kotlin/scripts/run-notes-web.sh
curl http://127.0.0.1:8787/api/health
curl -F 'note=@day-27-local-notes-web-kotlin/fixtures/demo-note.md' \
  http://127.0.0.1:8787/api/analyze
ollama ps
```

## Сценарий Видео

1. Показать `ollama list`: `qwen3:14b` установлена локально.
2. В одном терминале запустить приложение и показать `WEB APP: http://127.0.0.1:8787`.
3. Открыть этот loopback URL в браузере; health badge подтвердит локальный Ollama и модель.
4. Через file picker выбрать `fixtures/demo-note.md`, нажать кнопку и показать все пять разделов структурированного отчёта, модель и latency.
5. Выполнить `ollama ps`: модель была задействована локально. Не показывать реальные приватные заметки или `.env` других дней.

## Проверка Требований

- Есть реальное веб-приложение с загрузкой заметки.
- Приложение отправляет запрос в локальную LLM через прямой HTTP API.
- Ответ приходит обратно и отображается в браузере.
- Нет облачных моделей и fallback на облако.
- README содержит команды, API smoke test, fixture и сценарий видео.
