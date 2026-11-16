const QUESTIONS = {
  context_shift: {
    type: "noul",
    instructions: "Does `current_turn` mark a meaningful transition from the preceding turns in `conversation_prefix` into a new topic, event, scene, or conversational phase? Judge the boundary at the current turn, not whether a shift happened somewhere earlier.",
    criteria: {
      true: "The current turn begins or clearly establishes a meaningfully different topic, event, scene, or phase.",
      false: "The current turn continues, elaborates, or locally responds within the same topic, event, scene, or phase."
    }
  }
};

const INTERVENTION_THRESHOLD = 0.4;

const runtime = {
  settings: null,
  log: null,
  observer: null,
  pageObserver: null,
  baselineKeys: new Set(),
  active: null,
  lastSendText: null,
  lastSendDetectedPerf: 0
};

function nowIso() { return new Date().toISOString(); }

function nodeText(node) {
  let text = "";
  for (const child of node.childNodes) {
    if (child.nodeType === Node.TEXT_NODE) text += child.textContent;
    else if (child.nodeName === "EM") text += `*${child.textContent.trim()}*`;
    else if (child.nodeName === "BR") text += "\n";
    else text += nodeText(child);
  }
  return text;
}

function textFrom(side) {
  const bubble = side.querySelector(".chat");
  if (!bubble) return side.innerText.trim();
  const paragraphs = bubble.querySelectorAll(":scope > p");
  return paragraphs.length
    ? [...paragraphs].map((p) => nodeText(p).trim()).filter(Boolean).join("\n")
    : nodeText(bubble).trim();
}

function findConversationLog() {
  const explicit = document.querySelector('[role="log"]');
  if (explicit) return explicit;

  const row = document.querySelector("[data-index][data-key]");
  let candidate = row?.parentElement || null;
  while (candidate && candidate !== document.body) {
    if (candidate.querySelectorAll("[data-index][data-key]").length > 1) return candidate;
    candidate = candidate.parentElement;
  }
  return null;
}

async function publishStatus(state, detail = null) {
  await chrome.storage.local.set({
    feasibilityStatus: {
      state,
      detail,
      room_url: `${location.origin}${location.pathname}`,
      updated_at: nowIso()
    }
  });
}

function visibleTurns() {
  const log = runtime.log?.isConnected ? runtime.log : findConversationLog();
  if (!log) return [];
  const turns = [];
  const selector = '[data-sentry-component="LeftTextContent"], [data-sentry-component="RightTextContent"], [data-sentry-component="NarratorBubble"]';
  for (const row of log.querySelectorAll("[data-index][data-key]")) {
    const scope = row.querySelector(".swiper-slide-active") || row;
    [...scope.querySelectorAll(selector)].forEach((side, offset) => {
      const component = side.dataset.sentryComponent;
      turns.push({
        key: offset ? `${row.dataset.key}#${offset}` : row.dataset.key,
        index: Number(row.dataset.index) + offset / 100,
        speaker: component === "RightTextContent" ? "human" : "assistant",
        text: textFrom(side),
        side
      });
    });
  }
  return turns.sort((a, b) => a.index - b.index);
}

function showIndicator(text = "JEV 응답 기다리는 중…") {
  let indicator = document.querySelector("#zjf-waiting-indicator");
  if (!indicator) {
    indicator = document.createElement("div");
    indicator.id = "zjf-waiting-indicator";
    document.body.appendChild(indicator);
  }
  indicator.textContent = text;
}

function startWaitingIndicator(active) {
  const render = () => {
    const seconds = (performance.now() - active.requestStartedPerf) / 1000;
    showIndicator(`JEV 응답 기다리는 중 · ${seconds.toFixed(1)}s`);
  };
  render();
  active.waitingTimer = setInterval(render, 100);
}

function stopWaitingIndicator(active) {
  if (active?.waitingTimer) clearInterval(active.waitingTimer);
  if (active) active.waitingTimer = null;
  document.querySelector("#zjf-waiting-indicator")?.remove();
}

function revealAll() {
  document.querySelectorAll(".zjf-hidden-reply").forEach((node) => node.classList.remove("zjf-hidden-reply"));
  document.querySelector("#zjf-waiting-indicator")?.remove();
}

function showContextShiftPopup(score, onReveal) {
  document.querySelector("#zjf-context-shift-popup")?.remove();
  const popup = document.createElement("section");
  popup.id = "zjf-context-shift-popup";
  popup.setAttribute("role", "dialog");
  popup.setAttribute("aria-modal", "false");
  popup.setAttribute("aria-labelledby", "zjf-context-shift-title");
  popup.innerHTML = `
    <button class="zjf-popup-close" type="button" aria-label="닫기">×</button>
    <strong id="zjf-context-shift-title">대화 흐름이 바뀐 것 같아요</strong>
    <p>JEV context shift ${score.toFixed(3)} · 응답을 보기 전에 잠시 확인해 보세요.</p>
    <button class="zjf-popup-continue" type="button">응답 보기</button>
  `;
  popup.querySelector(".zjf-popup-close").addEventListener("click", onReveal);
  popup.querySelector(".zjf-popup-continue").addEventListener("click", onReveal);
  document.body.appendChild(popup);
}

function hideAssistant(turn) {
  if (!runtime.active || runtime.active.finished) return;
  turn.side.classList.add("zjf-hidden-reply");
  runtime.active.assistantKey = turn.key;
  if (!runtime.active.assistantFirstDetectedAt) {
    runtime.active.assistantFirstDetectedAt = nowIso();
    runtime.active.assistantFirstDetectedPerf = performance.now();
    if (runtime.active.logCreated) {
      updateLog(runtime.active.eventId, {
        zeta_response_detected_at: runtime.active.assistantFirstDetectedAt,
        assistant_message_key: runtime.active.assistantKey
      });
    }
  }
}

function requestState(userText) {
  const allTurns = visibleTurns().filter((turn) => turn.text);
  const latest = allTurns.at(-1);
  const userAlreadyPresent = latest?.speaker === "human" && latest.text === userText;
  const throughUser = userAlreadyPresent
    ? allTurns
    : [...allTurns, { speaker: "human", text: userText }];
  const prefix = throughUser.slice(-Number(runtime.settings.maxTurns));
  const conversationPrefix = prefix.map((turn, index) => ({
    turn_index: String(index + 1),
    speaker: turn.speaker,
    text: turn.text
  }));
  return {
    conversationPrefix,
    payload: {
      state: {
        conversation_prefix: conversationPrefix,
        current_turn: conversationPrefix.at(-1)
      },
      model: runtime.settings.model,
      questions: QUESTIONS
    }
  };
}

async function finishActive(active, releaseReason) {
  if (runtime.active !== active || active.finished) return;
  const finishedPerf = performance.now();
  active.finished = true;
  stopWaitingIndicator(active);
  revealAll();
  document.querySelector("#zjf-context-shift-popup")?.remove();
  runtime.active = null;

  const decisionFinishedPerf = active.decisionFinishedPerf ?? finishedPerf;
  await updateLog(active.eventId, {
    status: "completed",
    assistant_message_key: active.assistantKey,
    zeta_response_detected_at: active.assistantFirstDetectedAt,
    released_at: nowIso(),
    release_reason: releaseReason,
    total_zeta_hidden_ms: active.assistantFirstDetectedPerf === null
      ? 0
      : Math.max(0, finishedPerf - active.assistantFirstDetectedPerf),
    popup_hold_after_jev_ms: active.popupShown
      ? Math.max(0, finishedPerf - decisionFinishedPerf)
      : 0
  });
  const result = active.result;
  const score = active.contextShiftScore;
  publishStatus(
    result?.ok ? "last_request_succeeded" : "last_request_failed",
    result?.ok
      ? `최근 요청 성공 · score ${Number.isFinite(score) ? score.toFixed(3) : "없음"} · ${Math.round(result.latencyMs ?? 0)}ms`
      : (result?.error || "Unknown error")
  );
  scan();
}

async function evaluateFor(userText, triggerSource) {
  if (runtime.active || !runtime.settings.enabled) return;
  const request = requestState(userText);
  if (!request.conversationPrefix.length) return;
  publishStatus("requesting", `JEV 요청 중 · ${request.conversationPrefix.length} turns`);
  const startedPerf = performance.now();
  const active = {
    eventId: crypto.randomUUID(),
    userKey: `send-${Date.now()}`,
    triggerSource,
    requestStartedAt: nowIso(),
    requestStartedPerf: startedPerf,
    requestTurnCount: request.conversationPrefix.length,
    requestCharacterCount: JSON.stringify(request.conversationPrefix).length,
    assistantFirstDetectedAt: null,
    assistantFirstDetectedPerf: null,
    assistantKey: null,
    result: null,
    contextShiftScore: null,
    decisionFinishedPerf: null,
    requestFinishedAt: null,
    assistantFirstDetectedAtDecision: null,
    popupShown: false,
    logCreated: false,
    waitingTimer: null,
    finished: false
  };
  runtime.active = active;
  startWaitingIndicator(active);

  const jevPromise = chrome.runtime.sendMessage({ type: "JEV_EVALUATE", payload: request.payload });
  await appendLog({
    schema: "zeta-jev-feasibility-event/6",
    event_id: active.eventId,
    status: "waiting_for_jev",
    room_url: `${location.origin}${location.pathname}`,
    triggered_at: active.requestStartedAt,
    trigger_source: active.triggerSource,
    request_turn_count: active.requestTurnCount,
    request_character_count: active.requestCharacterCount,
    jev_returned_at: null,
    jev_response: null,
    jev_error: null,
    jev_context_shift_score: null,
    jev_latency_ms: null,
    zeta_response_detected_at: null,
    jev_returned_before_zeta_response: null,
    extra_wait_after_zeta_response_ms: null,
    threshold: INTERVENTION_THRESHOLD,
    decision: "waiting_for_jev",
    assistant_message_key: null,
    released_at: null,
    release_reason: null,
    total_zeta_hidden_ms: 0,
    popup_hold_after_jev_ms: 0
  });
  active.logCreated = true;
  if (active.assistantFirstDetectedAt) {
    await updateLog(active.eventId, {
      zeta_response_detected_at: active.assistantFirstDetectedAt,
      assistant_message_key: active.assistantKey
    });
  }

  let result;
  try {
    result = await jevPromise;
  } catch (error) {
    result = { ok: false, error: error.message };
  }
  if (runtime.active !== active || active.finished) return;
  stopWaitingIndicator(active);
  const decisionFinishedPerf = performance.now();
  const contextShiftScore = Number(result?.response?.answers?.context_shift?.noul);
  const shouldIntervene = Boolean(
    result?.ok
    && Number.isFinite(contextShiftScore)
    && contextShiftScore >= INTERVENTION_THRESHOLD
  );
  active.result = result;
  active.contextShiftScore = contextShiftScore;
  active.decisionFinishedPerf = decisionFinishedPerf;
  active.requestFinishedAt = nowIso();
  active.assistantFirstDetectedAtDecision = active.assistantFirstDetectedAt;
  const jevReturnedBeforeZeta = active.assistantFirstDetectedAtDecision === null;
  const extraWaitAfterZetaMs = jevReturnedBeforeZeta
    ? 0
    : Math.max(0, decisionFinishedPerf - active.assistantFirstDetectedPerf);
  const decision = !result?.ok || !Number.isFinite(contextShiftScore)
    ? "fail_open"
    : (shouldIntervene ? "hold_and_popup" : "show_zeta_response");

  // One readable record per human send. Save it as soon as JEV returns; later
  // Zeta arrival/release events update the same event_id.
  await updateLog(active.eventId, {
    status: shouldIntervene ? "holding_for_user" : "decision_complete",
    jev_returned_at: active.requestFinishedAt,
    jev_response: result?.response || null,
    jev_error: result?.ok ? null : (result?.error || "Unknown error"),
    jev_context_shift_score: Number.isFinite(contextShiftScore) ? contextShiftScore : null,
    jev_latency_ms: result?.latencyMs ?? null,
    zeta_response_detected_at: active.assistantFirstDetectedAt,
    jev_returned_before_zeta_response: jevReturnedBeforeZeta,
    extra_wait_after_zeta_response_ms: extraWaitAfterZetaMs,
    threshold: INTERVENTION_THRESHOLD,
    decision,
    assistant_message_key: active.assistantKey,
    released_at: null,
    release_reason: null,
    total_zeta_hidden_ms: extraWaitAfterZetaMs,
    popup_hold_after_jev_ms: 0
  });
  if (jevReturnedBeforeZeta && active.assistantFirstDetectedAt) {
    await updateLog(active.eventId, {
      zeta_response_detected_at: active.assistantFirstDetectedAt,
      assistant_message_key: active.assistantKey
    });
  }

  if (!shouldIntervene) {
    const releaseReason = !result?.ok
      ? "jev_request_failed"
      : (Number.isFinite(contextShiftScore) ? "score_below_threshold" : "invalid_jev_score");
    await finishActive(active, releaseReason);
    return;
  }

  active.popupShown = true;
  showContextShiftPopup(contextShiftScore, () => finishActive(active, "user_revealed_response"));
  publishStatus(
    "intervention_holding",
    `score ${contextShiftScore.toFixed(3)} ≥ ${INTERVENTION_THRESHOLD} · 응답 공개 대기 중`
  );
  // JEV may finish before Zeta creates its reply. Keep active so that a later reply is hidden.
  scan();
}

async function appendLog(event) {
  const { feasibilityLogs = [] } = await chrome.storage.local.get("feasibilityLogs");
  feasibilityLogs.push(event);
  await chrome.storage.local.set({ feasibilityLogs: feasibilityLogs.slice(-500) });
}

async function updateLog(eventId, patch) {
  const { feasibilityLogs = [] } = await chrome.storage.local.get("feasibilityLogs");
  const index = feasibilityLogs.findIndex((event) => event.event_id === eventId);
  if (index < 0) {
    feasibilityLogs.push({ event_id: eventId, ...patch });
  } else {
    feasibilityLogs[index] = { ...feasibilityLogs[index], ...patch };
  }
  await chrome.storage.local.set({ feasibilityLogs: feasibilityLogs.slice(-500) });
}

function scan() {
  if (!runtime.settings?.enabled) return;
  const turns = visibleTurns();
  if (runtime.active) {
    for (const turn of turns) {
      const isActiveReply = turn.key === runtime.active.assistantKey;
      if (turn.speaker === "assistant" && (isActiveReply || !runtime.baselineKeys.has(turn.key))) {
        hideAssistant(turn);
      }
    }
  }
  const newTurns = turns.filter((turn) => !runtime.baselineKeys.has(turn.key) && turn.text);
  for (const turn of turns) {
    runtime.baselineKeys.add(turn.key);
  }
  if (runtime.active || !newTurns.length) return;

  // DOM fallback: only evaluate when the actual latest visible chat is a new human turn.
  const latest = turns.filter((turn) => turn.text).at(-1);
  if (latest?.speaker !== "human" || !newTurns.some((turn) => turn.key === latest.key)) return;
  triggerFromText(latest.text, "dom_human_turn");
}

function editorText(editor) {
  if (!editor) return "";
  return ("value" in editor ? editor.value : editor.innerText || editor.textContent || "").trim();
}

function findComposer(from = null) {
  const selector = 'textarea, [contenteditable="true"], [role="textbox"]';
  const nearby = from?.closest("form")?.querySelector(selector);
  if (nearby) return nearby;
  if (from?.matches?.(selector)) return from;
  const focused = document.activeElement?.matches?.(selector) ? document.activeElement : null;
  return focused || [...document.querySelectorAll(selector)].find((editor) => editorText(editor));
}

function triggerFromText(text, source) {
  if (!runtime.settings?.enabled || runtime.active) return;
  if (!text) return;
  const duplicate = text === runtime.lastSendText
    && performance.now() - runtime.lastSendDetectedPerf < 1_500;
  if (duplicate) return;
  runtime.lastSendText = text;
  runtime.lastSendDetectedPerf = performance.now();
  publishStatus("trigger_detected", `메시지 전송 감지 · ${source}`);
  evaluateFor(text, source);
}

function triggerFromComposer(editor, source) {
  triggerFromText(editorText(editor), source);
}

function onComposerKeydown(event) {
  if (event.key !== "Enter" || event.shiftKey || event.isComposing || event.keyCode === 229) return;
  const editor = findComposer(event.target);
  if (!editor) return;
  triggerFromComposer(editor, "enter");
}

function onComposerClick(event) {
  const button = event.target.closest?.("button");
  if (!button || button.disabled) return;
  const isKnownSendButton = button.matches('[data-testid="chat-send-button"]');
  const label = [button.getAttribute("aria-label"), button.getAttribute("title"), button.textContent]
    .filter(Boolean).join(" ").trim().toLowerCase();
  if (!isKnownSendButton && !/(^|\s)(send|전송)(\s|$)/i.test(label)) return;
  triggerFromComposer(findComposer(button), "send_button");
}

function onComposerSubmit(event) {
  triggerFromComposer(findComposer(event.target), "form_submit");
}

function attachToConversationLog() {
  const log = findConversationLog();
  if (!log) {
    if (runtime.log && !runtime.log.isConnected) {
      runtime.observer?.disconnect();
      runtime.observer = null;
      runtime.log = null;
    }
    publishStatus("waiting_for_conversation", "Zeta 대화 목록을 기다리는 중입니다.");
    return false;
  }
  if (runtime.log === log && runtime.observer) return true;

  runtime.observer?.disconnect();
  runtime.log = log;
  runtime.baselineKeys = new Set(visibleTurns().map((turn) => turn.key));
  runtime.observer = new MutationObserver(scan);
  runtime.observer.observe(log, { childList: true, subtree: true, characterData: true });
  publishStatus("ready", `대화 감지 준비됨 · 현재 ${runtime.baselineKeys.size} turns`);
  return true;
}

async function start() {
  runtime.settings = await chrome.storage.local.get({
    enabled: false,
    model: "jev-latest",
    timeoutMs: 15000,
    maxTurns: 30
  });
  await publishStatus("initializing", "Zeta 대화 목록을 찾는 중입니다.");
  attachToConversationLog();
  runtime.pageObserver = new MutationObserver(attachToConversationLog);
  runtime.pageObserver.observe(document.documentElement, { childList: true, subtree: true });
  document.addEventListener("keydown", onComposerKeydown, true);
  document.addEventListener("click", onComposerClick, true);
  document.addEventListener("submit", onComposerSubmit, true);
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== "local" || !runtime.settings) return;
  for (const [key, change] of Object.entries(changes)) runtime.settings[key] = change.newValue;
  if (!runtime.settings.enabled) {
    if (runtime.active) finishActive(runtime.active, "extension_disabled");
    else revealAll();
  } else {
    attachToConversationLog();
    visibleTurns().forEach((turn) => runtime.baselineKeys.add(turn.key));
  }
});

start();
