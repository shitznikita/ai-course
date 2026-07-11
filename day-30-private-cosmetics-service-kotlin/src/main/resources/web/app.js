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
  catalog: "взят из локального каталога",
  local_catalog: "взят из локального каталога",
  catalog_category: "взят из локального каталога",
  product_type_hint: "выбран вами",
  user_hint: "выбран вами",
  explicit_hint: "выбран вами",
  deterministic_hint: "выбран вами",
  name_hint: "определён по названию",
  model: "определён локальной моделью",
  local_model: "определён локальной моделью",
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
    byId("ocr-note").textContent = `Локальный Tesseract · предварительная оценка: ${result.quality}.${uncertain} Проверьте состав перед анализом.`;
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
    const partial = isPartialAnalysis(result.report, result.input);
    setStatus(result.sessionId
      ? partial ? "Частичный разбор готов. Чат будет отвечать только по подтверждённой части." : "Анализ готов. Чат по продукту открыт справа."
      : "Отчёт готов, но для чата нужно больше распознанных данных.");
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
  const fallbackTitle = productType !== "unknown" ? `${productTypeLabels[productType]} #${workspaceSequence}` : `Продукт ${workspaceSequence}`;
  const title = suppliedName || fallbackTitle;
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

function uniqueStrings(values) {
  return [...new Set(normalizedValues(values).map((value) => String(value).trim()).filter(Boolean))];
}

function normalizeGroundedClaims(values) {
  return normalizedValues(values).map((value) => {
    if (typeof value === "string") return { text: value, sourceIds: [] };
    return {
      text: String(value?.text || "").trim(),
      sourceIds: uniqueStrings(value?.sourceIds),
    };
  }).filter((claim) => claim.text);
}

function analysisCoverage(input = {}) {
  const parsed = Math.max(0, Number(input.parsedIngredientCount) || 0);
  const recognizedIngredients = Math.max(0, Number(input.recognizedIngredientCount) || 0);
  const recognized = Math.min(parsed || Number.MAX_SAFE_INTEGER, Math.max(0, Number(input.matchedFragmentCount ?? recognizedIngredients) || 0));
  return {
    parsed,
    recognized,
    recognizedIngredients,
    percent: parsed > 0 ? Math.round((recognized / parsed) * 100) : 0,
    unknown: normalizedValues(input.unknownIngredients).length,
  };
}

function isPartialAnalysis(report = {}, input = {}) {
  const coverage = analysisCoverage(input);
  return report.status === "needs_clarification" || coverage.recognized < coverage.parsed;
}

function createSourceRegistry(sources) {
  const entries = [];
  const seen = new Set();
  normalizedValues(sources).forEach((source) => {
    const id = String(source?.id || "").trim();
    if (!id || seen.has(id)) return;
    seen.add(id);
    entries.push(source);
  });
  return {
    entries,
    byId: new Map(entries.map((source) => [String(source.id), source])),
    numberById: new Map(entries.map((source, index) => [String(source.id), index + 1])),
  };
}

function ingredientLabel(id) {
  const value = String(id || "ингредиент").replace(/[_-]+/g, " ").trim();
  return value.replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

function typeSourceLabel(source) {
  if (!source) return null;
  return productTypeSourceLabels[source] || String(source).replace(/_/g, " ");
}

function sourceTypeLabel(type) {
  if (!type) return "";
  const labels = {
    clinical_study: "Клиническое исследование",
    consumer_guidance: "Рекомендации для потребителей",
    dermatology_guidance: "Дерматологические рекомендации",
    ingredient_review: "Обзор ингредиента",
    regulation: "Нормативный документ",
    regulatory_database: "Регуляторная база",
    regulatory_guidance: "Регуляторное руководство",
    regulatory_overview: "Обзор регулирования",
    professional_guidance: "Профессиональная рекомендация",
    scientific_opinion: "Научное заключение",
    scientific_safety_assessment: "Научная оценка безопасности",
    safety_opinion: "Заключение по безопасности",
  };
  return labels[type] || String(type).replace(/_/g, " ");
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

function renderClaimCitations(sourceIds, registry) {
  const sources = uniqueStrings(sourceIds).map((id) => registry.byId.get(id)).filter(Boolean);
  if (!sources.length) return null;
  const refs = element("span", null, "claim-citations");
  refs.setAttribute("aria-label", "Источники утверждения");
  sources.forEach((source) => {
    const number = registry.numberById.get(String(source.id));
    const link = safeLink(source, String(number));
    link.classList.add("claim-citation");
    link.title = `${source.organization}: ${source.title}`;
    link.setAttribute("aria-label", `Источник ${number}: ${source.organization}, ${source.title}`);
    refs.append(link);
  });
  return refs;
}

function appendClaim(container, claim, registry) {
  container.append(element("span", claim.text, "claim-copy"));
  const citations = renderClaimCitations(claim.sourceIds, registry);
  if (citations) container.append(citations);
}

function renderGroundedList(title, claims, registry, options = {}) {
  const section = element("section", null, `report-section ${options.className || ""}`.trim());
  section.append(element("h3", title));
  if (options.description) section.append(element("p", options.description, "section-description"));
  const list = element("ul", null, "claim-list");
  if (claims.length) {
    claims.forEach((claim) => {
      const item = element("li");
      appendClaim(item, claim, registry);
      list.append(item);
    });
  } else {
    list.append(element("li", options.emptyText || "Недостаточно подтверждённых данных.", "empty-claim"));
  }
  section.append(list);
  return section;
}

function compactIngredientInsight(text) {
  return String(text || "")
    .replace(/^Функции в локальной карточке:\s*/i, "")
    .replace(/\.\s*Роль зависит от концентрации и всей формулы\.?$/i, ".")
    .trim();
}

function renderIngredientSection(items, registry) {
  const section = element("section", null, "report-section ingredient-section");
  section.append(element("h3", "Что подтверждено по ингредиентам"));
  const grid = element("div", null, "ingredient-grid");
  if (!items.length) {
    grid.append(element("p", "В подтверждённой части нет выбранных ключевых ингредиентов.", "empty-claim"));
  } else {
    items.forEach((item) => {
      const card = element("article", null, "ingredient-card");
      card.append(element("h4", ingredientLabel(item.ingredientId)));
      const claim = element("p", null, "ingredient-claim");
      appendClaim(claim, {
        text: compactIngredientInsight(item.whyItMatters),
        sourceIds: uniqueStrings(item.sourceIds),
      }, registry);
      card.append(claim);
      grid.append(card);
    });
  }
  section.append(grid);
  if (items.length) section.append(element("p", "Фактическая роль ингредиента зависит от концентрации и всей формулы.", "scope-note"));
  return section;
}

function renderRoutineSection(routine, registry, productTypeSource) {
  const section = element("section", null, "report-section routine-section");
  const heading = element("div", null, "section-title-row");
  heading.append(element("h3", "Как использовать"));
  if (!normalizedValues(routine.sourceIds).length) {
    const label = productTypeSource === "user_hint" ? "По выбранному типу" : "Базовая схема по типу";
    heading.append(element("span", label, "evidence-tag"));
  }
  section.append(heading);
  const facts = element("dl", null, "routine-facts");
  [
    ["Время", normalizedValues(routine.timeOfDay).join(", ") || "уточните по этикетке"],
    ["Шаг", routine.step || "не определён"],
    ["Смывать", rinseLabels[routine.rinseOff] || routine.rinseOff || "уточнить"],
  ].forEach(([term, value]) => {
    facts.append(element("dt", term), element("dd", value));
  });
  section.append(facts, element("p", routine.directions || "Сверьте способ применения с упаковкой.", "routine-directions"));
  const citations = renderClaimCitations(routine.sourceIds, registry);
  if (citations) {
    const footer = element("div", null, "section-evidence");
    footer.append(element("span", "Основание:"), citations);
    section.append(footer);
  }
  return section;
}

function renderHighlights(claims, registry) {
  if (!claims.length) return null;
  const section = element("section", null, "report-highlights");
  section.append(element("h3", "Главное о продукте"));
  const list = element("div", null, "highlight-list");
  claims.forEach((claim) => {
    const item = element("div", null, "highlight-item");
    appendClaim(item, claim, registry);
    list.append(item);
  });
  section.append(list);
  return section;
}

function restoreAnalysisInput(input, report) {
  byId("inci").value = input.inciText || "";
  byId("name-hint").value = input.productName || "";
  const allowedType = Object.prototype.hasOwnProperty.call(productTypeLabels, input.productTypeHint || "") ? input.productTypeHint : report.productType;
  byId("product-type-hint").value = allowedType === "unknown" ? "" : allowedType;
  setInputTab("inci");
  byId("analyze-panel").scrollIntoView({ behavior: "smooth", block: "start" });
  window.setTimeout(() => byId("inci").focus(), 0);
}

function renderCoverageAlert(report, input) {
  const coverage = analysisCoverage(input);
  const coveragePartial = coverage.recognized < coverage.parsed;
  const section = element("section", null, `coverage-alert ${coveragePartial ? "partial" : "complete"}`);
  const icon = element("span", coveragePartial ? "!" : "✓", "coverage-icon");
  icon.setAttribute("aria-hidden", "true");
  const body = element("div", null, "coverage-body");
  body.append(element("h3", coveragePartial ? "Разбор покрывает только часть состава" : "Покрытие состава достаточное"));
  const detail = coverage.parsed
    ? `Сопоставлено ${coverage.recognized} из ${coverage.parsed} фрагментов (${coverage.percent}%); найдено ${coverage.recognizedIngredients} карточек INCI.${coverage.unknown ? ` Ещё ${coverage.unknown} нужно проверить.` : ""}`
    : "Состав не удалось уверенно разделить на ингредиенты.";
  body.append(element("p", detail));
  const progress = element("progress", null, "coverage-progress");
  progress.max = Math.max(coverage.parsed, 1);
  progress.value = coverage.recognized;
  progress.setAttribute("aria-label", `Сопоставлено ${coverage.recognized} из ${coverage.parsed} фрагментов состава`);
  body.append(progress);
  if (coveragePartial || coverage.unknown || report.status === "needs_clarification") {
    const edit = element("button", "Проверить и исправить INCI", "text-button");
    edit.type = "button";
    edit.addEventListener("click", () => restoreAnalysisInput(input, report));
    body.append(edit);
  }
  section.append(icon, body);
  return section;
}

function renderTechnicalDetails(report, input) {
  const corrections = correctionValues(input.ingredientCorrections).map(correctionLabel).filter(Boolean);
  const unknown = uniqueStrings(input.unknownIngredients);
  const limitations = uniqueStrings(report.limitations);
  const details = element("details", null, "technical-details");
  const summary = element("summary");
  summary.append(element("strong", "Технические детали"));
  const total = corrections.length + unknown.length + limitations.length;
  if (total) summary.append(element("span", `${total} позиций`));
  details.append(summary);
  const content = element("div", null, "technical-content");

  if (corrections.length) {
    const group = element("section", null, "technical-group");
    group.append(element("h4", "Автоматические исправления OCR"));
    const list = element("ul");
    corrections.forEach((value) => list.append(element("li", value)));
    group.append(list);
    content.append(group);
  }
  if (unknown.length) {
    const group = element("section", null, "technical-group");
    group.append(element("h4", `Не распознано на этикетке · ${unknown.length}`));
    group.append(element("p", "Эти фрагменты не использовались для выводов. Сверьте их с упаковкой."));
    const list = element("ul", null, "technical-scroll");
    unknown.forEach((value) => list.append(element("li", value)));
    group.append(list);
    content.append(group);
  }
  if (limitations.length) {
    const group = element("section", null, "technical-group");
    group.append(element("h4", "Ограничения анализа"));
    const list = element("ul");
    limitations.forEach((value) => list.append(element("li", value)));
    group.append(list);
    content.append(group);
  }
  const recognized = Number(input.recognizedIngredientCount) || 0;
  const evidence = Number(input.evidenceIngredientCount ?? recognized) || 0;
  if (evidence < recognized) {
    content.append(element("p", `Локальной модели передано ${evidence} приоритетных карточек из ${recognized}; детерминированные проверки используют все распознанные карточки.`, "technical-note"));
  }
  if (!content.childNodes.length) content.append(element("p", "Дополнительных технических замечаний нет.", "technical-note"));
  details.append(content);
  return details;
}

function renderReport(data) {
  const report = data.report;
  const input = data.input || {};
  const registry = createSourceRegistry(data.sources || []);
  const partial = isPartialAnalysis(report, input);
  const highlights = normalizeGroundedClaims(report.highlights);
  const skinFit = normalizeGroundedClaims(report.skinFit ?? report.suitableSkinTypes);
  const cautions = normalizeGroundedClaims(report.cautions);
  const keyIngredients = normalizedValues(report.keyIngredients);
  const root = byId("result");
  root.replaceChildren();

  const heading = element("div", null, "report-verdict");
  const badges = element("div", null, "report-badges");
  const status = report.status === "needs_clarification" ? "Нужно уточнение" : partial ? "Частичный разбор" : (statusLabels[report.status] || report.status);
  badges.append(element("span", status, `badge status-badge ${partial ? "partial" : "complete"}`));
  badges.append(element("span", confidenceLabels[report.confidence] || report.confidence, `badge confidence-badge confidence-${report.confidence || "unknown"}`));
  heading.append(badges);
  heading.append(element("h3", productTypeLabels[report.productType] || report.productType));
  const source = typeSourceLabel(input.productTypeSource);
  if (source) heading.append(element("p", `Тип продукта: ${source}.`, "type-provenance"));
  if (report.summary) {
    const summary = element("p", null, "summary");
    appendClaim(summary, { text: report.summary, sourceIds: uniqueStrings(report.summarySourceIds) }, registry);
    heading.append(summary);
  }
  root.append(heading, renderCoverageAlert(report, input));

  const routine = report.routine || { timeOfDay: [], step: "не определён", directions: "Сверьте способ применения с этикеткой.", rinseOff: "unknown" };
  const primary = element("div", null, "report-primary");
  const highlightSection = renderHighlights(highlights, registry);
  if (highlightSection) primary.append(highlightSection);
  if (report.status === "answered") {
    const columns = element("div", null, "report-columns");
    columns.append(
      renderRoutineSection(routine, registry, input.productTypeSource),
      renderGroundedList(
        partial ? "Сигналы по распознанной части" : "Совместимость и профиль",
        skinFit,
        registry,
        {
          className: "skin-fit-section",
          description: partial ? "Это не оценка совместимости всего продукта." : "Вывод основан только на подтверждённых карточках состава.",
        },
      ),
    );
    primary.append(columns);
  } else if (skinFit.length) {
    primary.append(renderGroundedList("Совпадения с профилем", skinFit, registry, {
      className: "skin-fit-section",
      description: "Это точные совпадения по распознанной части, а не оценка всего продукта.",
    }));
  }
  if (keyIngredients.length) primary.append(renderIngredientSection(keyIngredients, registry));
  if (cautions.length) {
    primary.append(renderGroundedList("На что обратить внимание", cautions, registry, { className: "caution-section" }));
  }
  if (primary.childNodes.length) root.append(primary);
  root.append(renderTechnicalDetails(report, input));
  if (report.disclaimer) root.append(element("p", report.disclaimer, "disclaimer"));

  renderSources(registry);
  byId("metrics").textContent = data.model
    ? `${data.model.name} · ${data.model.latencyMs} ms · tokens ${data.model.promptTokens ?? "?"}/${data.model.completionTokens ?? "?"}`
    : "Модель не вызывалась: данных недостаточно.";
}

function safeLink(source, label) {
  try {
    const url = new URL(source?.url, window.location.origin);
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

function renderSources(registry) {
  const root = byId("sources");
  root.replaceChildren();
  root.hidden = registry.entries.length === 0;
  if (!registry.entries.length) return;
  const details = element("details", null, "source-details");
  const summary = element("summary");
  summary.append(element("strong", "Источники"), element("span", String(registry.entries.length), "count-badge"));
  details.append(summary, element("p", "Номер рядом с утверждением ведёт к источнику, который поддерживает именно это утверждение.", "sources-intro"));
  const list = element("ol", null, "source-registry");
  registry.entries.forEach((source) => {
    const item = element("li", null, "source-entry");
    const heading = element("div", null, "source-heading");
    heading.append(safeLink(source, `${source.organization}: ${source.title}`));
    const type = sourceTypeLabel(source.type);
    if (type) heading.append(element("span", type, "source-type"));
    item.append(heading);
    if (source.notes) item.append(element("p", source.notes, "source-notes"));
    list.append(item);
  });
  details.append(list);
  root.append(details);
}

function renderWorkspaceList() {
  const root = byId("workspace-list");
  root.replaceChildren();
  byId("workspace-empty").hidden = productWorkspaces.length > 0;
  byId("workspace-count").textContent = String(productWorkspaces.length);
  byId("workspace-toggle-count").textContent = String(productWorkspaces.length);

  productWorkspaces.forEach((workspace) => {
    const row = element("div", null, "workspace-row");
    const input = workspace.analysis.input || {};
    const partial = isPartialAnalysis(workspace.analysis.report || {}, input);
    const select = element("button", null, `workspace-item${workspace.id === activeWorkspaceId ? " active" : ""}${partial ? " partial" : ""}`);
    select.type = "button";
    select.setAttribute("aria-pressed", String(workspace.id === activeWorkspaceId));
    select.append(element("span", productTypeInitials[workspace.productType] || "КС", `product-avatar${partial ? " partial" : ""}`));
    const copy = element("span", null, "product-copy");
    copy.append(
      element("strong", workspace.title),
      element("span", `${partial ? "Частичный разбор" : "Анализ готов"} · ${input.matchedFragmentCount ?? input.recognizedIngredientCount ?? 0}/${input.parsedIngredientCount ?? 0}`, `product-quality ${partial ? "partial" : "complete"}`),
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
    byId("chat-context").textContent = "";
    byId("prompt-chips").replaceChildren();
    return;
  }
  renderReport(workspace.analysis);
  byId("result-panel").classList.remove("hidden");
  byId("chat-panel").classList.toggle("hidden", !workspace.sessionId);
  if (workspace.sessionId) renderChat(workspace);
}

function renderChat(workspace) {
  byId("chat-title").textContent = workspace.title;
  const coverage = analysisCoverage(workspace.analysis.input || {});
  const partial = isPartialAnalysis(workspace.analysis.report || {}, workspace.analysis.input || {});
  const lowConfidence = workspace.analysis.report?.confidence === "low";
  byId("chat-context").textContent = coverage.recognized < coverage.parsed
    ? `Частичный отчёт · сопоставлено ${coverage.recognized}/${coverage.parsed} фрагментов. Ответы не выходят за эти данные.`
    : lowConfidence
      ? `Состав сопоставлен полностью, но уверенность интерпретации низкая.`
      : `Сопоставлено ${coverage.recognized}/${coverage.parsed} фрагментов состава.`;
  renderPromptChips(workspace);
  const root = byId("chat-log");
  root.replaceChildren();
  if (!workspace.messages.length) {
    const emptyText = partial
      ? "Чат отвечает только по подтверждённой части состава и не будет угадывать непрочитанные ингредиенты."
      : "Спросите, когда использовать средство, кому оно может подойти или на что обратить внимание.";
    root.append(element("p", emptyText, "chat-empty"));
    return;
  }
  workspace.messages.forEach((message) => {
    const clarification = message.status === "needs_clarification" ? " needs-clarification" : "";
    const item = element("div", null, `chat-message ${message.role}${clarification}`);
    const author = message.role === "user" ? "Вы" : message.role === "error" ? "Ошибка" : "Локальная модель";
    item.append(element("strong", author), element("p", message.text));
    if (message.sources?.length) {
      const evidence = element("details", null, "chat-evidence");
      const summary = element("summary", `Источники ответа · ${message.sources.length}`);
      const list = element("ul");
      message.sources.forEach((source) => {
        const row = element("li");
        row.append(safeLink(source, `${source.organization}: ${source.title}`));
        list.append(row);
      });
      evidence.append(summary, list);
      item.append(evidence);
    }
    const limitations = uniqueStrings(message.limitations);
    if (limitations.length) {
      const note = element("details", null, "chat-limitations");
      note.append(element("summary", "Ограничения ответа"));
      const list = element("ul");
      limitations.forEach((value) => list.append(element("li", value)));
      note.append(list);
      item.append(note);
    }
    root.append(item);
  });
  window.setTimeout(() => { root.scrollTop = root.scrollHeight; }, 0);
}

function renderPromptChips(workspace) {
  const root = byId("prompt-chips");
  root.replaceChildren();
  const partial = isPartialAnalysis(workspace.analysis.report || {}, workspace.analysis.input || {});
  const prompts = partial ? [
    ["Какие ключевые ингредиенты удалось распознать?", "Что распознано?"],
    ["Какие ограничения есть у этого анализа?", "Ограничения"],
    ["Какие предосторожности и риски есть в распознанной части состава?", "Осторожность"],
  ] : [
    ["Для чего и когда использовать это средство?", "Краткий обзор"],
    ["Когда и на каком шаге использовать это средство?", "Когда использовать?"],
    ["Подойдёт ли это средство моему типу кожи?", "Подойдёт моей коже?"],
  ];
  prompts.forEach(([prompt, label]) => {
    const button = element("button", label);
    button.type = "button";
    button.dataset.chatPrompt = prompt;
    root.append(button);
  });
}

byId("chat-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const workspace = activeWorkspace();
  if (!workspace?.sessionId) return;
  const input = byId("chat-message");
  const message = input.value.trim();
  if (!message) return;
  const submit = event.submitter || event.currentTarget.querySelector("button[type='submit']");
  appendWorkspaceMessage(workspace, { role: "user", text: message, sources: [], limitations: [] });
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
    appendWorkspaceMessage(workspace, {
      role: "assistant",
      status: result.reply.status,
      text: result.reply.answer,
      sources: result.sources || [],
      limitations: result.reply.limitations || [],
    });
  } catch (error) {
    if (!(error instanceof AuthTransitionError)) appendWorkspaceMessage(workspace, { role: "error", text: error.message, sources: [], limitations: [] });
  } finally {
    setButtonBusy(submit, false);
    renderWorkspaceList();
    if (activeWorkspaceId === workspace.id) renderChat(workspace);
  }
});

byId("prompt-chips").addEventListener("click", (event) => {
  const button = event.target.closest("[data-chat-prompt]");
  if (!button || !byId("prompt-chips").contains(button)) return;
  byId("chat-message").value = button.dataset.chatPrompt;
  byId("chat-message").focus();
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
