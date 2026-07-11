const byId = (id) => document.getElementById(id);
const tokenInput = byId("token");
let sessionId = null;

const productTypeLabels = {
  face_cleanser: "Очищение лица",
  face_toner: "Тонер для лица",
  face_serum: "Сыворотка для лица",
  face_moisturizer: "Увлажняющее средство",
  face_sunscreen: "Солнцезащитное средство",
  other: "Другое средство",
  unknown: "Тип не определён",
};
const statusLabels = { answered: "Анализ готов", needs_clarification: "Нужно уточнение" };
const confidenceLabels = { low: "Низкая уверенность", medium: "Средняя уверенность", high: "Высокая уверенность" };
const rinseLabels = { yes: "да", no: "нет", unknown: "уточнить по этикетке" };

tokenInput.value = sessionStorage.getItem("cosmeticsApiToken") || "";
tokenInput.addEventListener("input", () => sessionStorage.setItem("cosmeticsApiToken", tokenInput.value));
tokenInput.addEventListener("change", refreshHealth);

function setStatus(message, error = false) {
  const node = byId("status");
  node.textContent = message;
  node.classList.toggle("error", error);
}

function profile() {
  const list = (id) => byId(id).value.split(",").map((value) => value.trim()).filter(Boolean);
  return {
    skinType: byId("skin-type").value,
    sensitive: byId("sensitive").checked,
    allergies: list("allergies"),
    goals: list("goals"),
  };
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  const token = tokenInput.value.trim();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(path, { ...options, headers });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (_) { body = null; }
  if (!response.ok) {
    const retry = response.headers.get("Retry-After");
    const detail = body?.message || `HTTP ${response.status}`;
    throw new Error(retry ? `${detail} Повторите через ${retry} с.` : detail);
  }
  return body;
}

async function refreshHealth() {
  try {
    const hasToken = Boolean(tokenInput.value.trim());
    const health = await api(hasToken ? "/api/health" : "/api/health/live");
    const node = byId("health");
    node.textContent = hasToken
      ? `${health.status} · ${health.model} · OCR ${health.ocrReady ? "ok" : "нет"}`
      : "Сервис запущен · введите API token";
    node.classList.toggle("ready", health.status === "ready" || health.status === "alive");
  } catch (error) {
    byId("health").textContent = "Сервис недоступен";
  }
}

byId("photo-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const file = byId("photo").files[0];
  if (!file) return;
  const data = new FormData();
  data.append("photo", file);
  setStatus("Распознаём этикетку локальным OCR…");
  try {
    const result = await api("/api/ocr", { method: "POST", body: data });
    byId("inci").value = result.extractedText;
    byId("ocr-note").textContent = `Качество: ${result.quality}. Обязательно проверьте текст перед анализом.`;
    setStatus("OCR готов. Исправьте ошибки в INCI и запустите анализ.");
  } catch (error) { setStatus(error.message, true); }
});

byId("text-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  await analyze("/api/analyze/text", {
    inciText: byId("inci").value,
    productName: byId("name-hint").value || null,
    profile: profile(),
  });
});

byId("name-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  await analyze("/api/analyze/name", { name: byId("product-name").value, profile: profile() });
});

async function analyze(path, payload) {
  setStatus("Локальная модель анализирует подтверждённые факты…");
  try {
    const result = await api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    sessionId = result.sessionId;
    renderAnalysis(result);
    setStatus(result.sessionId ? "Анализ завершён." : "Нужно больше данных для анализа.");
  } catch (error) { setStatus(error.message, true); }
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function listBlock(title, values) {
  const block = element("section", null, "report-block");
  block.append(element("h3", title));
  const list = element("ul");
  (values.length ? values : ["Нет данных"]).forEach((value) => list.append(element("li", value)));
  block.append(list);
  return block;
}

function renderAnalysis(data) {
  const report = data.report;
  const root = byId("result");
  root.replaceChildren();
  const heading = element("div", null, "report-heading");
  heading.append(element("span", statusLabels[report.status] || report.status, "badge"));
  heading.append(element("span", confidenceLabels[report.confidence] || report.confidence, "badge muted"));
  heading.append(element("h3", productTypeLabels[report.productType] || report.productType));
  root.append(heading, element("p", report.summary, "summary"));
  root.append(listBlock("Может подойти", report.suitableSkinTypes));
  root.append(listBlock("Ключевые ингредиенты", report.keyIngredients.map((item) => `${item.ingredientId}: ${item.whyItMatters}`)));
  root.append(listBlock("Как использовать", [
    `Время: ${report.routine.timeOfDay.join(", ") || "не определено"}`,
    `Шаг: ${report.routine.step}`,
    report.routine.directions,
    `Смывать: ${rinseLabels[report.routine.rinseOff] || report.routine.rinseOff}`,
  ]));
  root.append(listBlock("Осторожность", report.cautions));
  root.append(listBlock("Ограничения", report.limitations));
  root.append(element("p", report.disclaimer, "disclaimer"));
  renderSources(data.sources || []);
  byId("metrics").textContent = data.model
    ? `${data.model.name} · ${data.model.latencyMs} ms · tokens ${data.model.promptTokens ?? "?"}/${data.model.completionTokens ?? "?"}`
    : "Модель не вызывалась: данных недостаточно.";
  byId("result-panel").classList.remove("hidden");
  byId("chat-panel").classList.toggle("hidden", !sessionId);
  byId("chat-log").replaceChildren();
}

function renderSources(sources) {
  const root = byId("sources");
  root.replaceChildren(element("h3", "Источники"));
  const list = element("ul", null, "sources-list");
  sources.forEach((source) => {
    const link = element("a", `${source.organization}: ${source.title}`);
    link.href = source.url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    const item = element("li");
    item.append(link);
    list.append(item);
  });
  if (!sources.length) list.append(element("li", "Нет источников: анализ ожидает уточнения."));
  root.append(list);
}

byId("chat-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!sessionId) return;
  const input = byId("chat-message");
  const message = input.value.trim();
  if (!message) return;
  appendChat("Вы", message);
  input.value = "";
  try {
    const result = await api("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId, message }),
    });
    appendChat("Локальная модель", result.reply.answer);
    renderSources(result.sources || []);
  } catch (error) { appendChat("Ошибка", error.message); }
});

function appendChat(author, text) {
  const item = element("div", null, "chat-message");
  item.append(element("strong", author), element("p", text));
  byId("chat-log").append(item);
}

refreshHealth();
