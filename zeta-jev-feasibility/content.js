const QUESTIONS = {
  context_shift: {
    type: "noul",
    instructions: "Does `current_turn` mark a meaningful transition from the preceding turns in `conversation_prefix` into a new topic, event, scene, or conversational phase? Judge the boundary at the current turn, not whether a shift happened somewhere earlier.",
    criteria: {
      true: "The current turn begins or clearly establishes a meaningfully different topic, event, scene, or phase.",
      false: "The current turn continues, elaborates, or locally responds within the same topic, event, scene, or phase."
    }
  },
  natural_breakpoint: {
    type: "noul",
    instructions: "At the end of `current_turn`, could the human user naturally end this conversation now without interrupting an unresolved exchange, active question, or emotionally or narratively immediate beat? Judge local stopping-point naturalness, not whether the user should stop.",
    criteria: {
      true: "The interaction has a locally complete, settled, or pause-friendly boundary.",
      false: "Ending now would noticeably interrupt an active exchange, question, action, conflict, or immediate emotional or narrative beat."
    }
  },
  engagement_drop: {
    type: "noul",
    instructions: "Compared with the human user's earlier messages in `conversation_prefix`, does `latest_human_turn` show lower conversational engagement? Consider reduced elaboration, initiative, responsiveness, emotional investment, or willingness to continue in context. Do not treat brevity alone as disengagement. If there is not enough earlier human evidence for a comparison, favor false.",
    criteria: {
      true: "The latest human contribution shows a meaningful decrease in engagement relative to that user's earlier behavior in this conversation.",
      false: "Engagement is stable or higher, the difference is not meaningful, or there is insufficient evidence."
    }
  }
};

const runtime = {
  settings: null,
  observer: null,
  baselineKeys: new Set(),
  active: null,
  lastUserText: null,
  lastUserDetectedPerf: 0
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

function visibleTurns() {
  const log = document.querySelector('[role="log"]');
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

function showIndicator() {
  let indicator = document.querySelector("#zjf-waiting-indicator");
  if (!indicator) {
    indicator = document.createElement("div");
    indicator.id = "zjf-waiting-indicator";
    indicator.textContent = "응답 타이밍을 확인하는 중…";
    document.body.appendChild(indicator);
  }
}

function revealAll() {
  document.querySelectorAll(".zjf-hidden-reply").forEach((node) => node.classList.remove("zjf-hidden-reply"));
  document.querySelector("#zjf-waiting-indicator")?.remove();
}

function hideAssistant(turn) {
  if (!runtime.active || runtime.active.finished) return;
  turn.side.classList.add("zjf-hidden-reply");
  showIndicator();
  runtime.active.assistantKey = turn.key;
  if (!runtime.active.assistantFirstDetectedAt) {
    runtime.active.assistantFirstDetectedAt = nowIso();
    runtime.active.assistantFirstDetectedPerf = performance.now();
  }
}

function requestState(userTurn) {
  const allTurns = visibleTurns().filter((turn) => turn.text);
  const targetIndex = allTurns.findIndex((turn) => turn.key === userTurn.key);
  const throughUser = targetIndex >= 0 ? allTurns.slice(0, targetIndex + 1) : allTurns;
  const prefix = throughUser.slice(-Number(runtime.settings.maxTurns));
  const conversationPrefix = prefix.map((turn, index) => ({
    turn_index: String(index + 1),
    speaker: turn.speaker,
    text: turn.text
  }));
  const humanTurns = conversationPrefix.filter((turn) => turn.speaker === "human");
  return {
    conversationPrefix,
    payload: {
      state: {
        conversation_prefix: conversationPrefix,
        current_turn: conversationPrefix.at(-1),
        latest_human_turn: humanTurns.at(-1) || "No human turn yet."
      },
      model: runtime.settings.model,
      questions: QUESTIONS
    }
  };
}

async function evaluateFor(userTurn) {
  if (runtime.active || !runtime.settings.enabled) return;
  const request = requestState(userTurn);
  if (!request.conversationPrefix.length) return;
  const startedPerf = performance.now();
  const active = {
    userKey: userTurn.key,
    requestStartedAt: nowIso(),
    requestStartedPerf: startedPerf,
    requestTurnCount: request.conversationPrefix.length,
    requestCharacterCount: JSON.stringify(request.conversationPrefix).length,
    assistantFirstDetectedAt: null,
    assistantFirstDetectedPerf: null,
    assistantKey: null,
    finished: false
  };
  runtime.active = active;
  let result;
  try {
    result = await chrome.runtime.sendMessage({ type: "JEV_EVALUATE", payload: request.payload });
  } catch (error) {
    result = { ok: false, error: error.message };
  }
  const finishedPerf = performance.now();
  active.finished = true;
  revealAll();
  runtime.active = null;
  await appendLog({
    schema: "zeta-jev-feasibility-event/2",
    room_url: `${location.origin}${location.pathname}`,
    trigger_message_key: active.userKey,
    assistant_message_key: active.assistantKey,
    request_started_at: active.requestStartedAt,
    assistant_first_detected_at: active.assistantFirstDetectedAt,
    request_finished_at: nowIso(),
    api_latency_ms: result?.latencyMs ?? null,
    decision_round_trip_ms: finishedPerf - active.requestStartedPerf,
    assistant_hidden_ms: active.assistantFirstDetectedPerf === null
      ? 0
      : finishedPerf - active.assistantFirstDetectedPerf,
    jev_finished_before_zeta_reply: active.assistantFirstDetectedPerf === null,
    request_turn_count: active.requestTurnCount,
    request_character_count: active.requestCharacterCount,
    success: Boolean(result?.ok),
    error: result?.ok ? null : (result?.error || "Unknown error"),
    jev_response: result?.response || null
  });
  scan();
}

async function appendLog(event) {
  const { feasibilityLogs = [] } = await chrome.storage.local.get("feasibilityLogs");
  feasibilityLogs.push(event);
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
  for (const turn of turns) {
    if (runtime.baselineKeys.has(turn.key) || !turn.text) continue;
    runtime.baselineKeys.add(turn.key);
    if (turn.speaker !== "human" || runtime.active) continue;
    const duplicate = turn.text === runtime.lastUserText
      && performance.now() - runtime.lastUserDetectedPerf < 10_000;
    if (duplicate) continue;
    runtime.lastUserText = turn.text;
    runtime.lastUserDetectedPerf = performance.now();
    evaluateFor(turn);
  }
}

async function start() {
  runtime.settings = await chrome.storage.local.get({
    enabled: false,
    model: "jev-latest",
    timeoutMs: 15000,
    maxTurns: 30
  });
  const log = document.querySelector('[role="log"]');
  if (!log) return;
  visibleTurns().forEach((turn) => runtime.baselineKeys.add(turn.key));
  runtime.observer = new MutationObserver(scan);
  runtime.observer.observe(log, { childList: true, subtree: true, characterData: true });
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== "local" || !runtime.settings) return;
  for (const [key, change] of Object.entries(changes)) runtime.settings[key] = change.newValue;
  if (!runtime.settings.enabled) {
    if (runtime.active) runtime.active.finished = true;
    revealAll();
  } else {
    visibleTurns().forEach((turn) => runtime.baselineKeys.add(turn.key));
  }
});

start();
