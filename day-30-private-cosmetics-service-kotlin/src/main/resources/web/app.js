const byId = (id) => document.getElementById(id);
const TOKEN_STORAGE_KEY = "cosmetics.accessToken.v1";
const loopbackHosts = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);

let accessToken = "";
let sessionId = null;
let authEpoch = 0;
let requestController = new AbortController();

class ApiRequestError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
  }
}

class AuthTransitionError extends Error {
  constructor() {
    super("Authentication state changed");
    this.name = "AuthTransitionError";
  }
}

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

function trustedTransport() {
  return window.location.protocol === "https:" || loopbackHosts.has(window.location.hostname);
}

function readStoredToken() {
  try { return localStorage.getItem(TOKEN_STORAGE_KEY)?.trim() || ""; }
  catch (_) { return ""; }
}

function storeToken(token) {
  try {
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
    return true;
  } catch (_) {
    return false;
  }
}

function removeStoredToken() {
  try { localStorage.removeItem(TOKEN_STORAGE_KEY); } catch (_) { /* storage may be unavailable */ }
}

function nextAuthEpoch() {
  authEpoch += 1;
  requestController.abort();
  requestController = new AbortController();
}

function clearPrivateUi() {
  sessionId = null;
  ["photo", "product-name", "name-hint", "inci", "allergies", "goals", "chat-message"].forEach((id) => {
    const node = byId(id);
    if (node) node.value = "";
  });
  byId("skin-type").value = "unknown";
  byId("sensitive").checked = false;
  byId("ocr-note").textContent = "";
  byId("status").textContent = "";
  byId("result").replaceChildren();
  byId("sources").replaceChildren();
  byId("metrics").textContent = "";
  byId("chat-log").replaceChildren();
  byId("result-panel").classList.add("hidden");
  byId("chat-panel").classList.add("hidden");
}

function setLoginMessage(message = "", error = false) {
  const node = byId("login-error");
  node.textContent = message;
  node.classList.toggle("error", error);
}

function showLogin(message = "", error = false, clearStored = false) {
  nextAuthEpoch();
  accessToken = "";
  if (clearStored) removeStoredToken();
  clearPrivateUi();
  byId("boot-view").hidden = true;
  byId("app-view").hidden = true;
  byId("login-view").hidden = false;
  byId("login-token").value = "";
  setLoginMessage(message, error);
  window.setTimeout(() => byId("login-token").focus(), 0);
}

function updateHealth(health) {
  const node = byId("health");
  node.textContent = `${health.status} · ${health.model} · OCR ${health.ocrReady ? "ok" : "нет"}`;
  node.classList.toggle("ready", health.status === "ready");
}

function enterApp(token, health) {
  nextAuthEpoch();
  accessToken = token;
  const persisted = storeToken(token);
  byId("login-token").value = "";
  byId("boot-view").hidden = true;
  byId("login-view").hidden = true;
  byId("app-view").hidden = false;
  setLoginMessage();
  updateHealth(health);
  if (!persisted) {
    setStatus("Браузер запретил постоянное хранение: после закрытия страницы код потребуется снова.", true);
  }
}

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

async function fetchJson(path, options = {}, token = "", signal = undefined) {
  const headers = new Headers(options.headers || {});
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(path, {
    ...options,
    headers,
    signal,
    credentials: "omit",
    cache: "no-store",
    redirect: "error",
    referrerPolicy: "no-referrer",
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (_) { body = null; }
  if (!response.ok) {
    const retry = response.headers.get("Retry-After");
    const detail = body?.message || `HTTP ${response.status}`;
    throw new ApiRequestError(retry ? `${detail} Повторите через ${retry} с.` : detail, response.status);
  }
  return body;
}

async function verifyAccessToken(candidate) {
  return fetchJson("/api/health", {}, candidate);
}

async function api(path, options = {}) {
  if (!accessToken) throw new AuthTransitionError();
  const requestEpoch = authEpoch;
  try {
    const body = await fetchJson(path, options, accessToken, requestController.signal);
    if (requestEpoch !== authEpoch) throw new AuthTransitionError();
    return body;
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 401 && requestEpoch === authEpoch) {
      showLogin("Код доступа изменён или недействителен. Введите актуальный код.", true, true);
      throw new AuthTransitionError();
    }
    if (error?.name === "AbortError" || requestEpoch !== authEpoch) throw new AuthTransitionError();
    throw error;
  }
}

async function refreshHealth() {
  try {
    updateHealth(await api("/api/health"));
  } catch (error) {
    if (error instanceof AuthTransitionError) return;
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
  } catch (error) {
    if (!(error instanceof AuthTransitionError)) setStatus(error.message, true);
  }
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
  } catch (error) {
    if (!(error instanceof AuthTransitionError)) setStatus(error.message, true);
  }
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
  } catch (error) {
    if (!(error instanceof AuthTransitionError)) appendChat("Ошибка", error.message);
  }
});

function appendChat(author, text) {
  const item = element("div", null, "chat-message");
  item.append(element("strong", author), element("p", text));
  byId("chat-log").append(item);
}

byId("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!trustedTransport()) {
    setLoginMessage("Постоянный вход разрешён только через HTTPS или локальный SSH-туннель.", true);
    return;
  }

  const input = byId("login-token");
  const candidate = input.value.trim();
  if (candidate.length < 24) {
    setLoginMessage("Код доступа должен содержать не менее 24 символов.", true);
    return;
  }

  const submit = byId("login-submit");
  const verificationEpoch = authEpoch;
  submit.disabled = true;
  setLoginMessage("Проверяем код доступа…");
  try {
    const health = await verifyAccessToken(candidate);
    if (verificationEpoch !== authEpoch) throw new AuthTransitionError();
    enterApp(candidate, health);
  } catch (error) {
    if (error instanceof AuthTransitionError) {
      return;
    } else if (error instanceof ApiRequestError && error.status === 401) {
      removeStoredToken();
      input.value = "";
      setLoginMessage("Неверный код доступа.", true);
      input.focus();
    } else {
      setLoginMessage("Сервис временно недоступен. Попробуйте ещё раз, код не был сохранён.", true);
    }
  } finally {
    submit.disabled = false;
  }
});

byId("logout-button").addEventListener("click", () => {
  const activeSession = sessionId;
  const token = accessToken;
  if (activeSession && token) {
    fetchJson(`/api/sessions/${encodeURIComponent(activeSession)}`, { method: "DELETE" }, token).catch(() => {});
  }
  showLogin("Доступ удалён с этого устройства.", false, true);
});

window.addEventListener("storage", (event) => {
  if (event.key !== TOKEN_STORAGE_KEY) return;
  if (event.newValue === null) {
    showLogin("Вы вышли в другой вкладке.", false, false);
  } else if (event.newValue.trim() !== accessToken) {
    window.location.reload();
  }
});

async function bootstrap() {
  try { sessionStorage.removeItem("cosmeticsApiToken"); } catch (_) { /* legacy storage may be unavailable */ }
  if (!trustedTransport()) {
    byId("login-submit").disabled = true;
    showLogin("Откройте этот сайт по HTTPS: код нельзя отправлять через обычный HTTP.", true, false);
    return;
  }

  const saved = readStoredToken();
  if (!saved) {
    showLogin();
    return;
  }

  const verificationEpoch = authEpoch;
  try {
    const health = await verifyAccessToken(saved);
    if (verificationEpoch !== authEpoch || readStoredToken() !== saved) throw new AuthTransitionError();
    enterApp(saved, health);
  } catch (error) {
    if (error instanceof AuthTransitionError) {
      return;
    } else if (error instanceof ApiRequestError && error.status === 401) {
      showLogin("Сохранённый код больше не действует. Введите актуальный код.", true, true);
    } else {
      showLogin("Не удалось связаться с сервисом. Обновите страницу через несколько секунд.", true, false);
    }
  }
}

bootstrap();
