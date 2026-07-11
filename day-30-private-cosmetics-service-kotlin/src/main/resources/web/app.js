const byId = (id) => document.getElementById(id);
const all = (selector) => Array.from(document.querySelectorAll(selector));
const TOKEN_STORAGE_KEY = "cosmetics.accessToken.v1";
const loopbackHosts = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
const MAX_PRODUCT_WORKSPACES = 12;
const MAX_WORKSPACE_MESSAGES = 24;

let accessToken = "";
let localNoAuthMode = false;
let authEpoch = 0;
let requestController = new AbortController();
let productWorkspaces = [];
let activeWorkspaceId = null;
let workspaceSequence = 0;
let workspaceDrawerReturnFocus = null;

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
const productTypeInitials = {
  face_cleanser: "О",
  face_toner: "Т",
  face_serum: "С",
  face_moisturizer: "К",
  face_sunscreen: "SPF",
  other: "КС",
  unknown: "?",
};
const productTypeSourceLabels = {
  catalog: "по локальному каталогу",
  local_catalog: "по локальному каталогу",
  catalog_category: "по локальному каталогу",
  product_type_hint: "по выбранному типу",
  user_hint: "по выбранному типу",
  explicit_hint: "по выбранному типу",
  deterministic_hint: "по выбранному типу",
  name_hint: "по названию",
  model: "по локальной модели",
  local_model: "по локальной модели",
  unknown: "не определён",
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

function clearProductFields() {
  ["photo", "product-name", "name-hint", "inci", "chat-message"].forEach((id) => {
    const node = byId(id);
    if (node) node.value = "";
  });
  byId("product-type-hint").value = "";
  byId("ocr-note").textContent = "";
  setStatus();
}

function clearWorkspaceMemory() {
  productWorkspaces = [];
  activeWorkspaceId = null;
  workspaceSequence = 0;
  renderWorkspaceList();
  renderActiveWorkspace();
}

function clearPrivateUi() {
  clearProductFields();
  ["allergies", "goals"].forEach((id) => { byId(id).value = ""; });
  byId("skin-type").value = "unknown";
  byId("sensitive").checked = false;
  setInputTab("photo");
  clearWorkspaceMemory();
  closeWorkspaceDrawer();
}

function setLoginMessage(message = "", error = false) {
  const node = byId("login-error");
  node.textContent = message;
  node.classList.toggle("error", error);
}

function showLogin(message = "", error = false, clearStored = false) {
  nextAuthEpoch();
  accessToken = "";
  localNoAuthMode = false;
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
  const status = health.status === "ready" ? "Готов" : "Ограниченный режим";
  const ocr = health.ocrReady ? "Tesseract готов" : "Tesseract недоступен";
  node.textContent = `${status} · ${health.model} · ${ocr}`;
  node.classList.toggle("ready", health.status === "ready");
}

function enterApp(token, health, { localMode = false } = {}) {
  nextAuthEpoch();
  accessToken = token;
  localNoAuthMode = localMode;
  const persisted = localMode || storeToken(token);
  byId("login-token").value = "";
  byId("boot-view").hidden = true;
  byId("login-view").hidden = true;
  byId("app-view").hidden = false;
  byId("logout-button").textContent = localMode ? "Очистить" : "Выйти";
  setLoginMessage();
  updateHealth(health);
  renderWorkspaceList();
  if (!persisted) {
    setStatus("Браузер запретил постоянное хранение: после закрытия страницы код потребуется снова.", true);
  }
}

function setStatus(message = "", error = false) {
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
  if (!accessToken && !localNoAuthMode) throw new AuthTransitionError();
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

function setButtonBusy(button, busy, busyText = "Подождите…") {
  if (!button) return;
  if (busy) {
    button.dataset.idleText = button.textContent;
    button.textContent = busyText;
    button.disabled = true;
  } else {
    button.textContent = button.dataset.idleText || button.textContent;
    button.disabled = false;
    delete button.dataset.idleText;
  }
}

function setInputTab(tabName) {
  const supported = new Set(["photo", "inci", "name"]);
  const active = supported.has(tabName) ? tabName : "photo";
  all("[data-input-tab]").forEach((button) => {
    const selected = button.dataset.inputTab === active;
    button.classList.toggle("active", selected);
    button.setAttribute("aria-selected", String(selected));
    button.tabIndex = selected ? 0 : -1;
  });
  byId("photo-input-panel").hidden = active !== "photo";
  byId("inci-input-panel").hidden = active !== "inci";
  byId("name-input-panel").hidden = active !== "name";
  byId("text-form").hidden = active === "name";
}

all("[data-input-tab]").forEach((button, index, buttons) => {
  button.addEventListener("click", () => setInputTab(button.dataset.inputTab));
  button.addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight"].includes(event.key)) return;
    event.preventDefault();
    const offset = event.key === "ArrowRight" ? 1 : -1;
    const next = buttons[(index + offset + buttons.length) % buttons.length];
    setInputTab(next.dataset.inputTab);
    next.focus();
  });
});

byId("photo-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const fileInput = byId("photo");
  const file = fileInput.files[0];
  if (!file) return;
  const data = new FormData();
  data.append("photo", file);
  const submit = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  setButtonBusy(submit, true, "Распознаём…");
  setStatus("Локальный Tesseract читает блок Ingredients…");
  try {
    const result = await api("/api/ocr", { method: "POST", body: data });
    byId("inci").value = result.extractedText;
    const uncertain = result.uncertainFragments?.length
      ? ` Неразборчивые фрагменты: ${result.uncertainFragments.join(", ")}.`
      : "";
    byId("ocr-note").textContent = `Локальный Tesseract · качество: ${result.quality}.${uncertain} Проверьте состав перед анализом.`;
    setStatus("Состав распознан. Выберите тип средства, исправьте явные ошибки и запустите анализ.");
    byId("product-type-hint").focus();
  } catch (error) {
    if (!(error instanceof AuthTransitionError)) setStatus(error.message, true);
  } finally {
    fileInput.value = "";
    setButtonBusy(submit, false);
  }
});

byId("text-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  await analyze("/api/analyze/text", {
    inciText: byId("inci").value,
    productName: byId("name-hint").value.trim() || null,
    productTypeHint: byId("product-type-hint").value || null,
    profile: profile(),
  }, submit);
});

byId("name-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  await analyze("/api/analyze/name", { name: byId("product-name").value, profile: profile() }, submit);
});

async function analyze(path, payload, submit) {
  setButtonBusy(submit, true, "Анализируем…");
  setStatus("Ollama сопоставляет состав с локальными карточками…");
  try {
    const result = await api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const workspace = createWorkspace(result);
    productWorkspaces.unshift(workspace);
    const evicted = productWorkspaces.splice(MAX_PRODUCT_WORKSPACES);
    evicted.forEach((item) => deleteBackendSession(item.sessionId));
    activeWorkspaceId = workspace.id;
    renderWorkspaceList();
    renderActiveWorkspace();
    setStatus(result.sessionId ? "Анализ готов. Чат по продукту открыт справа." : "Отчёт готов, но для чата нужно больше распознанных данных.");
    if (window.matchMedia("(max-width: 900px)").matches) {
      window.setTimeout(() => byId("result-panel").scrollIntoView({ behavior: "smooth", block: "start" }), 0);
    }
  } catch (error) {
    if (!(error instanceof AuthTransitionError)) setStatus(error.message, true);
  } finally {
    setButtonBusy(submit, false);
  }
}

function createWorkspace(analysis) {
  workspaceSequence += 1;
  const productType = analysis.report?.productType || "unknown";
  const suppliedName = analysis.input?.productName?.trim();
  const title = suppliedName || (productType !== "unknown" ? productTypeLabels[productType] : null) || `Продукт ${workspaceSequence}`;
  const id = globalThis.crypto?.randomUUID?.() || `workspace-${Date.now()}-${workspaceSequence}`;
  return {
    id,
    sessionId: analysis.sessionId || null,
    title,
    productType,
    analysis,
    messages: [],
  };
}

function activeWorkspace() {
  return productWorkspaces.find((workspace) => workspace.id === activeWorkspaceId) || null;
}

function deleteBackendSession(sessionId) {
  if (!sessionId) return;
  api(`/api/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE" }).catch(() => {});
}

function appendWorkspaceMessage(workspace, message) {
  workspace.messages.push(message);
  if (workspace.messages.length > MAX_WORKSPACE_MESSAGES) {
    workspace.messages.splice(0, workspace.messages.length - MAX_WORKSPACE_MESSAGES);
  }
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function normalizedValues(values) {
  return Array.isArray(values) ? values.filter((value) => value !== null && value !== undefined && String(value).trim()) : [];
}

function listBlock(title, values, className = "") {
  const block = element("section", null, `report-block${className ? ` ${className}` : ""}`);
  block.append(element("h3", title));
  const list = element("ul");
  const items = normalizedValues(values);
  (items.length ? items : ["Нет данных"]).forEach((value) => list.append(element("li", String(value))));
  block.append(list);
  return block;
}

function ingredientLabel(id) {
  const value = String(id || "ингредиент").replace(/[_-]+/g, " ").trim();
  return value.replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

function typeSourceLabel(source) {
  if (!source) return null;
  return productTypeSourceLabels[source] || String(source).replace(/_/g, " ");
}

function correctionValues(value) {
  if (Array.isArray(value)) return value;
  if (value && typeof value === "object") {
    return Object.entries(value).map(([from, to]) => ({ from, to }));
  }
  return [];
}

function correctionLabel(correction) {
  if (typeof correction === "string") return correction;
  if (!correction || typeof correction !== "object") return String(correction || "");
  const from = correction.rawName || correction.original || correction.from || correction.raw || correction.ocrText || correction.input;
  const to = correction.canonicalInci || correction.corrected || correction.to || correction.normalized || correction.canonical || correction.output;
  if (from && to) return `«${from}» → «${to}»`;
  if (to) return String(to);
  return Object.values(correction).filter((item) => ["string", "number"].includes(typeof item)).join(" → ");
}

function appendMetaCard(root, label, value) {
  const card = element("div", null, "meta-card");
  card.append(element("span", label), element("strong", value));
  root.append(card);
}

function renderReport(data) {
  const report = data.report;
  const input = data.input || {};
  const root = byId("result");
  root.replaceChildren();

  const heading = element("div", null, "report-heading");
  heading.append(element("span", statusLabels[report.status] || report.status, "badge"));
  heading.append(element("span", confidenceLabels[report.confidence] || report.confidence, "badge muted"));
  heading.append(element("h3", productTypeLabels[report.productType] || report.productType));
  root.append(heading, element("p", report.summary, "summary"));

  const meta = element("div", null, "analysis-meta");
  appendMetaCard(meta, "Распознано", `${input.recognizedIngredientCount ?? 0} из ${input.parsedIngredientCount ?? 0}`);
  if ((input.evidenceIngredientCount ?? input.recognizedIngredientCount ?? 0) < (input.recognizedIngredientCount ?? 0)) {
    appendMetaCard(meta, "В отчёте", `${input.evidenceIngredientCount} приоритетных карточек`);
  }
  appendMetaCard(meta, "Неизвестных позиций", String(input.unknownIngredients?.length ?? 0));
  const source = typeSourceLabel(input.productTypeSource);
  if (source) appendMetaCard(meta, "Тип определён", source);
  root.append(meta);

  const corrections = correctionValues(input.ingredientCorrections).map(correctionLabel).filter(Boolean);
  if (corrections.length) {
    const correctionCard = element("section", null, "corrections-card");
    correctionCard.append(element("h3", "Исправления OCR"));
    const list = element("ul");
    corrections.forEach((value) => list.append(element("li", value)));
    correctionCard.append(list);
    root.append(correctionCard);
  }

  const routine = report.routine || { timeOfDay: [], step: "не определён", directions: "Сверьте способ применения с этикеткой.", rinseOff: "unknown" };
  const grid = element("div", null, "report-grid");
  grid.append(listBlock("Может подойти", report.suitableSkinTypes));
  grid.append(listBlock("Ключевые ингредиенты", normalizedValues(report.keyIngredients).map((item) => `${ingredientLabel(item.ingredientId)} — ${item.whyItMatters}`)));
  grid.append(listBlock("Как использовать", [
    `Время: ${normalizedValues(routine.timeOfDay).join(", ") || "не определено"}`,
    `Шаг: ${routine.step || "не определён"}`,
    routine.directions,
    `Смывать: ${rinseLabels[routine.rinseOff] || routine.rinseOff}`,
  ]));
  grid.append(listBlock("Осторожность", report.cautions));
  if (normalizedValues(input.unknownIngredients).length) {
    grid.append(listBlock(
      "Проверьте на этикетке",
      normalizedValues(input.unknownIngredients).map((item) => `Не распознано: ${item}`),
      "full-width",
    ));
  }
  grid.append(listBlock("Ограничения", report.limitations, "full-width"));
  root.append(grid, element("p", report.disclaimer, "disclaimer"));

  renderSources(data.sources || []);
  byId("metrics").textContent = data.model
    ? `${data.model.name} · ${data.model.latencyMs} ms · tokens ${data.model.promptTokens ?? "?"}/${data.model.completionTokens ?? "?"}`
    : "Модель не вызывалась: данных недостаточно.";
}

function safeLink(source, label) {
  try {
    const url = new URL(source.url, window.location.origin);
    if (!["http:", "https:"].includes(url.protocol)) return element("span", label);
    const link = element("a", label);
    link.href = url.href;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    return link;
  } catch (_) {
    return element("span", label);
  }
}

function renderSources(sources) {
  const root = byId("sources");
  root.replaceChildren(element("h3", "Источники"));
  const list = element("ul", null, "sources-list");
  normalizedValues(sources).forEach((source) => {
    const item = element("li");
    item.append(safeLink(source, `${source.organization}: ${source.title}`));
    list.append(item);
  });
  if (!sources.length) list.append(element("li", "Нет источников: анализ ожидает уточнения."));
  root.append(list);
}

function renderWorkspaceList() {
  const root = byId("workspace-list");
  root.replaceChildren();
  byId("workspace-empty").hidden = productWorkspaces.length > 0;
  byId("workspace-count").textContent = String(productWorkspaces.length);
  byId("workspace-toggle-count").textContent = String(productWorkspaces.length);

  productWorkspaces.forEach((workspace) => {
    const row = element("div", null, "workspace-row");
    const select = element("button", null, `workspace-item${workspace.id === activeWorkspaceId ? " active" : ""}`);
    select.type = "button";
    select.setAttribute("aria-pressed", String(workspace.id === activeWorkspaceId));
    select.append(element("span", productTypeInitials[workspace.productType] || "КС", "product-avatar"));
    const copy = element("span", null, "product-copy");
    const input = workspace.analysis.input || {};
    copy.append(
      element("strong", workspace.title),
      element("span", `${productTypeLabels[workspace.productType] || workspace.productType} · ${input.recognizedIngredientCount ?? 0}/${input.parsedIngredientCount ?? 0} ингредиентов`),
    );
    select.append(copy, element("span", String(workspace.messages.filter((item) => item.role === "user").length), "message-count"));
    select.addEventListener("click", () => selectWorkspace(workspace.id));

    const remove = element("button", "×", "icon-button workspace-remove");
    remove.type = "button";
    remove.title = "Удалить продукт из этой вкладки";
    remove.setAttribute("aria-label", `Удалить ${workspace.title}`);
    remove.addEventListener("click", () => removeWorkspace(workspace.id));
    row.append(select, remove);
    root.append(row);
  });
}

function selectWorkspace(id) {
  if (!productWorkspaces.some((workspace) => workspace.id === id)) return;
  activeWorkspaceId = id;
  renderWorkspaceList();
  renderActiveWorkspace();
}

function removeWorkspace(id) {
  const workspace = productWorkspaces.find((item) => item.id === id);
  if (!workspace) return;
  productWorkspaces = productWorkspaces.filter((item) => item.id !== id);
  if (activeWorkspaceId === id) activeWorkspaceId = productWorkspaces[0]?.id || null;
  renderWorkspaceList();
  renderActiveWorkspace();
  deleteBackendSession(workspace.sessionId);
}

function renderActiveWorkspace() {
  const workspace = activeWorkspace();
  if (!workspace) {
    byId("result-panel").classList.add("hidden");
    byId("chat-panel").classList.add("hidden");
    byId("result").replaceChildren();
    byId("sources").replaceChildren();
    byId("metrics").textContent = "";
    byId("chat-log").replaceChildren();
    return;
  }
  renderReport(workspace.analysis);
  byId("result-panel").classList.remove("hidden");
  byId("chat-panel").classList.toggle("hidden", !workspace.sessionId);
  if (workspace.sessionId) renderChat(workspace);
}

function renderChat(workspace) {
  byId("chat-title").textContent = workspace.title;
  const root = byId("chat-log");
  root.replaceChildren();
  if (!workspace.messages.length) {
    root.append(element("p", "Отчёт готов. Спросите, когда использовать средство, кому оно может подойти или на что обратить внимание.", "chat-empty"));
    return;
  }
  workspace.messages.forEach((message) => {
    const item = element("div", null, `chat-message ${message.role}`);
    const author = message.role === "user" ? "Вы" : message.role === "error" ? "Ошибка" : "Локальная модель";
    item.append(element("strong", author), element("p", message.text));
    if (message.sources?.length) {
      const links = element("div", null, "chat-sources");
      message.sources.slice(0, 3).forEach((source) => links.append(safeLink(source, source.organization || "Источник")));
      item.append(links);
    }
    root.append(item);
  });
  window.setTimeout(() => { root.scrollTop = root.scrollHeight; }, 0);
}

byId("chat-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const workspace = activeWorkspace();
  if (!workspace?.sessionId) return;
  const input = byId("chat-message");
  const message = input.value.trim();
  if (!message) return;
  const submit = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  appendWorkspaceMessage(workspace, { role: "user", text: message, sources: [] });
  input.value = "";
  renderChat(workspace);
  renderWorkspaceList();
  setButtonBusy(submit, true, "…");
  try {
    const result = await api("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId: workspace.sessionId, message }),
    });
    appendWorkspaceMessage(workspace, { role: "assistant", text: result.reply.answer, sources: result.sources || [] });
  } catch (error) {
    if (!(error instanceof AuthTransitionError)) appendWorkspaceMessage(workspace, { role: "error", text: error.message, sources: [] });
  } finally {
    setButtonBusy(submit, false);
    renderWorkspaceList();
    if (activeWorkspaceId === workspace.id) renderChat(workspace);
  }
});

all("[data-chat-prompt]").forEach((button) => {
  button.addEventListener("click", () => {
    byId("chat-message").value = button.dataset.chatPrompt;
    byId("chat-message").focus();
  });
});

function startNewAnalysis() {
  activeWorkspaceId = null;
  clearProductFields();
  setInputTab("photo");
  renderWorkspaceList();
  renderActiveWorkspace();
  closeWorkspaceDrawer();
  byId("analyze-panel").scrollIntoView({ behavior: "smooth", block: "start" });
}

byId("new-analysis-button").addEventListener("click", startNewAnalysis);
byId("new-analysis-inline").addEventListener("click", startNewAnalysis);

function openWorkspaceDrawer() {
  if (!window.matchMedia("(max-width: 900px)").matches) return;
  const sidebar = byId("workspace-sidebar");
  workspaceDrawerReturnFocus = document.activeElement;
  sidebar.inert = false;
  sidebar.removeAttribute("aria-hidden");
  sidebar.setAttribute("role", "dialog");
  sidebar.setAttribute("aria-modal", "true");
  sidebar.classList.add("open");
  byId("workspace-toggle").setAttribute("aria-expanded", "true");
  byId("workspace-backdrop").hidden = false;
  document.body.classList.add("drawer-open");
  byId("workspace-close").focus();
}

function closeWorkspaceDrawer() {
  const sidebar = byId("workspace-sidebar");
  const wasOpen = sidebar.classList.contains("open");
  sidebar.classList.remove("open");
  sidebar.removeAttribute("role");
  sidebar.removeAttribute("aria-modal");
  const mobile = window.matchMedia("(max-width: 900px)").matches;
  sidebar.inert = mobile;
  if (mobile) sidebar.setAttribute("aria-hidden", "true");
  else sidebar.removeAttribute("aria-hidden");
  byId("workspace-toggle").setAttribute("aria-expanded", "false");
  byId("workspace-backdrop").hidden = true;
  document.body.classList.remove("drawer-open");
  if (wasOpen && workspaceDrawerReturnFocus?.isConnected) workspaceDrawerReturnFocus.focus();
  workspaceDrawerReturnFocus = null;
}

byId("workspace-toggle").addEventListener("click", () => {
  if (byId("workspace-sidebar").classList.contains("open")) closeWorkspaceDrawer();
  else openWorkspaceDrawer();
});
byId("workspace-close").addEventListener("click", closeWorkspaceDrawer);
byId("workspace-backdrop").addEventListener("click", closeWorkspaceDrawer);
window.addEventListener("keydown", (event) => {
  const sidebar = byId("workspace-sidebar");
  if (event.key === "Escape") {
    closeWorkspaceDrawer();
    return;
  }
  if (event.key !== "Tab" || !sidebar.classList.contains("open")) return;
  const focusable = all("#workspace-sidebar button:not([disabled]), #workspace-sidebar textarea:not([disabled]), #workspace-sidebar a[href]")
    .filter((node) => !node.hidden && node.offsetParent !== null);
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
});
window.addEventListener("resize", () => {
  const mobile = window.matchMedia("(max-width: 900px)").matches;
  if (!mobile || !byId("workspace-sidebar").classList.contains("open")) closeWorkspaceDrawer();
});

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
  setButtonBusy(submit, true, "Проверяем…");
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
    setButtonBusy(submit, false);
  }
});

byId("logout-button").addEventListener("click", () => {
  const token = accessToken;
  const sessionIds = productWorkspaces.map((workspace) => workspace.sessionId).filter(Boolean);
  if (localNoAuthMode) {
    sessionIds.forEach((sessionId) => {
      fetchJson(`/api/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE" }).catch(() => {});
    });
    nextAuthEpoch();
    clearPrivateUi();
    setStatus("Временная история и поля очищены.");
    return;
  }
  sessionIds.forEach((sessionId) => {
    fetchJson(`/api/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE" }, token).catch(() => {});
  });
  showLogin("Доступ и временная история продуктов удалены с этого устройства.", false, true);
});

window.addEventListener("storage", (event) => {
  if (event.key !== TOKEN_STORAGE_KEY) return;
  if (localNoAuthMode) return;
  if (event.newValue === null) {
    showLogin("Вы вышли в другой вкладке.", false, false);
  } else if (event.newValue.trim() !== accessToken) {
    window.location.reload();
  }
});

async function bootstrap() {
  try { sessionStorage.removeItem("cosmeticsApiToken"); } catch (_) { /* legacy storage may be unavailable */ }
  setInputTab("photo");
  renderWorkspaceList();
  closeWorkspaceDrawer();
  if (!trustedTransport()) {
    byId("login-submit").disabled = true;
    showLogin("Откройте этот сайт по HTTPS: код нельзя отправлять через обычный HTTP.", true, false);
    return;
  }

  const saved = readStoredToken();
  if (!saved) {
    if (loopbackHosts.has(window.location.hostname)) {
      try {
        const health = await fetchJson("/api/health");
        enterApp("", health, { localMode: true });
        return;
      } catch (error) {
        if (!(error instanceof ApiRequestError) || error.status !== 401) {
          showLogin("Не удалось связаться с локальным сервисом. Обновите страницу через несколько секунд.", true, false);
          return;
        }
      }
    }
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
