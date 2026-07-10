const form = document.querySelector("#analyze-form");
const fileInput = document.querySelector("#note-file");
const selectedFile = document.querySelector("#selected-file");
const analyzeButton = document.querySelector("#analyze-button");
const health = document.querySelector("#health");
const errorPanel = document.querySelector("#error-panel");
const errorMessage = document.querySelector("#error-message");
const errorHint = document.querySelector("#error-hint");
const resultPanel = document.querySelector("#result-panel");

fileInput.addEventListener("change", () => {
  const [file] = fileInput.files;
  selectedFile.textContent = file
    ? `${file.name} · ${formatBytes(file.size)}`
    : "Файл ещё не выбран";
  hideError();
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const [file] = fileInput.files;
  if (!file) {
    showError("Сначала выберите заметку .txt или .md.");
    return;
  }

  const extension = file.name.split(".").pop().toLowerCase();
  if (extension !== "txt" && extension !== "md") {
    showError("Поддерживаются только заметки .txt и .md.");
    return;
  }

  setBusy(true);
  hideError();
  resultPanel.hidden = true;

  try {
    const data = new FormData();
    data.append("note", file, file.name);
    const response = await fetch("/api/analyze", {
      method: "POST",
      body: data,
      cache: "no-store",
    });
    const payload = await readJson(response);
    if (!response.ok) {
      throw payload;
    }
    renderReport(payload);
  } catch (error) {
    showError(error?.message || "Не удалось связаться с локальным приложением.", error?.hint);
  } finally {
    setBusy(false);
  }
});

async function refreshHealth() {
  try {
    const response = await fetch("/api/health", { cache: "no-store" });
    const payload = await readJson(response);
    if (!response.ok) {
      throw payload;
    }
    health.className = "health health-ready";
    health.textContent = `Локальная модель готова: ${payload.model} · Ollama ${payload.ollamaVersion}`;
  } catch (error) {
    health.className = "health health-error";
    health.textContent = error?.message || "Не удалось проверить локальный Ollama.";
  }
}

async function readJson(response) {
  try {
    return await response.json();
  } catch (_) {
    return { message: "Сервер вернул ответ в неподдерживаемом формате." };
  }
}

function renderReport(payload) {
  const { source, report, model } = payload;
  document.querySelector("#source-meta").textContent =
    `${source.fileName} · .${source.format} · ${source.charCount.toLocaleString("ru-RU")} символов`;
  document.querySelector("#summary").textContent = report.summary;

  renderTextList("#decisions", report.decisions, "Явные решения не найдены.");
  renderActionItems(report.actionItems);
  renderTextList("#risks", report.risks, "Явные риски не найдены.");
  renderTextList("#open-questions", report.openQuestions, "Открытые вопросы не найдены.");

  const metrics = [`Модель: ${model.name}`, `latency: ${model.latencyMs} ms`];
  if (model.promptTokens != null) metrics.push(`вход: ${model.promptTokens} токенов`);
  if (model.completionTokens != null) metrics.push(`выход: ${model.completionTokens} токенов`);
  if (model.outputTokensPerSecond != null) {
    metrics.push(`скорость: ${model.outputTokensPerSecond.toFixed(1)} токенов/с`);
  }
  document.querySelector("#model-meta").textContent = metrics.join(" · ");
  resultPanel.hidden = false;
  resultPanel.scrollIntoView({ behavior: "smooth", block: "start" });
}

function renderTextList(selector, items, emptyMessage) {
  const list = document.querySelector(selector);
  clearChildren(list);
  const values = items.length ? items : [emptyMessage];
  values.forEach((value) => {
    const item = document.createElement("li");
    item.textContent = value;
    if (!items.length) item.className = "empty-item";
    list.append(item);
  });
}

function renderActionItems(items) {
  const list = document.querySelector("#action-items");
  clearChildren(list);
  if (!items.length) {
    const item = document.createElement("li");
    item.className = "empty-item";
    item.textContent = "Явные задачи не найдены.";
    list.append(item);
    return;
  }

  items.forEach((action) => {
    const item = document.createElement("li");
    const details = [];
    if (action.owner) details.push(`ответственный: ${action.owner}`);
    if (action.deadline) details.push(`срок: ${action.deadline}`);
    item.textContent = details.length ? `${action.task} (${details.join(", ")})` : action.task;
    list.append(item);
  });
}

function clearChildren(element) {
  while (element.firstChild) {
    element.removeChild(element.firstChild);
  }
}

function showError(message, hint = "") {
  errorMessage.textContent = message;
  errorHint.textContent = hint || "";
  errorHint.hidden = !hint;
  errorPanel.hidden = false;
}

function hideError() {
  errorPanel.hidden = true;
  errorMessage.textContent = "";
  errorHint.textContent = "";
}

function setBusy(isBusy) {
  analyzeButton.disabled = isBusy;
  analyzeButton.textContent = isBusy ? "Локальная модель анализирует…" : "Проанализировать локально";
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(1)} KiB`;
}

refreshHealth();
