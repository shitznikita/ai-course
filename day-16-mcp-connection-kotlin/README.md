# Day 16: Подключение MCP

## Цель

День 16 показывает минимальный клиентский шаг в MCP: установить соединение с готовым public remote MCP server и получить список доступных tools через `tools/list`.

Свой MCP server и LLM-запросы здесь не используются. Задача дня — увидеть, что MCP является протоколом discovery и tool schemas, а не заменой API или LLM framework.

Стек: Kotlin CLI, Gradle, MCP Kotlin SDK client, Ktor CIO/SSE, Streamable HTTP transport.

## MCP Server

Default server:

```text
https://mcp.deepwiki.com/mcp
```

Это публичный no-auth DeepWiki MCP endpoint. На момент реализации он возвращает tools:

```text
read_wiki_structure
read_wiki_contents
ask_question
```

## Архитектура

- `Config` читает `MCP_SERVER_URL`, `MCP_CLIENT_NAME`, `MCP_TIMEOUT_SECONDS` из `.env` или environment variables.
- `McpToolLister` использует официальный MCP Kotlin SDK client и `StreamableHttpClientTransport`.
- `ToolPrinter` печатает стабильный список tools, required arguments и краткую сводку input schema.
- `RawJsonRpcProbe` показывает учебный JSON-RPC обмен через `java.net.http.HttpClient`.
- `Main` выбирает режим `list` или `raw-check`.

## Настройка

Секреты не нужны. При желании можно переопределить endpoint:

```bash
cp day-16-mcp-connection-kotlin/.env.example day-16-mcp-connection-kotlin/.env
```

`.env` не коммитится.

## Запуск

```bash
./gradlew :day-16-mcp-connection-kotlin:build
day-16-mcp-connection-kotlin/scripts/run-mcp.sh
day-16-mcp-connection-kotlin/scripts/run-mcp.sh --args="raw-check"
```

Failure demo:

```bash
MCP_SERVER_URL=https://invalid.example/mcp day-16-mcp-connection-kotlin/scripts/run-mcp.sh
```

## Ожидаемый Вывод

Основной режим печатает:

```text
Day 16: MCP connection
CONNECTING TO MCP SERVER
SERVER: https://mcp.deepwiki.com/mcp
CLIENT: ai-course-day-16-mcp-client

CONNECTED
TOOLS RETURNED: 3

1. read_wiki_structure
   description: Get a list of documentation topics for a GitHub repository. Args: repoName...
   required: repoName
   input schema: type=object, properties=repoName:string

CHECK: connection ok, tools/list ok
```

`raw-check` дополнительно показывает ответы `initialize` и `tools/list` в JSON-RPC/SSE форме.

## Видео

Сценарий:

1. Показать `.env.example`, где нет токенов.
2. Показать `build.gradle.kts`: используется MCP SDK client, а не LLM SDK.
3. Показать `McpToolLister`: `Client`, `StreamableHttpClientTransport`, `client.listTools()`.
4. Запустить `day-16-mcp-connection-kotlin/scripts/run-mcp.sh`.
5. Показать `CONNECTED`, `TOOLS RETURNED: 3` и names/required args.
6. Запустить `raw-check`, чтобы увидеть `initialize` и `tools/list` как JSON-RPC.
7. Запустить failure demo с неверным URL и показать понятную ошибку.

## Проверка Требований

- MCP-соединение устанавливается: да.
- Список tools возвращается: да.
- Используется существующий public MCP server: да.
- Свой MCP server не реализуется: да.
- LLM API не вызывается: да.
- Секреты не нужны и не коммитятся: да.
