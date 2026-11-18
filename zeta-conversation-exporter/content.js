const PENDING_KEY_PREFIX = "pending-";
const SCROLL_PAUSE_MS = 500;
const OLDEST_SETTLE_ATTEMPTS = 8;

let latestExport = null;
let latestRoom = null;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

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

function bubbleText(bubble) {
  const paragraphs = bubble.querySelectorAll(":scope > p");
  if (paragraphs.length) {
    return [...paragraphs].map((p) => nodeText(p).trim()).filter(Boolean).join("\n");
  }
  return nodeText(bubble).trim();
}

function rowPosition(row) {
  const transform = row.style.transform || "";
  const translate3d = transform.match(/translate3d\([^,]+,\s*(-?[\d.]+)px/i);
  if (translate3d) return Number(translate3d[1]);
  const translateY = transform.match(/translateY\(\s*(-?[\d.]+)px/i);
  if (translateY) return Number(translateY[1]);
  return Number(row.dataset.index);
}

function visibleTurns(log) {
  const output = [];
  const legacySelector = [
    '[data-sentry-component="LeftTextContent"]',
    '[data-sentry-component="RightTextContent"]',
    '[data-sentry-component="NarratorBubble"]'
  ].join(", ");

  for (const row of log.querySelectorAll("[data-index][data-key]")) {
    const key = row.dataset.key || "";
    if (!key || key.startsWith(PENDING_KEY_PREFIX)) continue;

    const scope = row.querySelector(".swiper-slide-active") || row;
    const legacySides = [...scope.querySelectorAll(legacySelector)];
    const parts = [];
    if (legacySides.length) {
      legacySides.forEach((side) => {
        const component = side.dataset.sentryComponent;
        const narrator = component === "NarratorBubble";
        const bubble = side.querySelector(".chat");
        const name = narrator ? null : side.querySelector("span.caption1");
        parts.push({
          role: component === "RightTextContent" ? "user" : "character",
          speaker: narrator ? "나레이션" : (name?.textContent.trim() || "화자 미상"),
          text: bubble ? bubbleText(bubble) : side.innerText.trim()
        });
      });
    } else {
      // Current Zeta DOM (frame.pingpong.ai) no longer exposes the sentry
      // component attributes. A row is one user message or one complete AI
      // response, and can contain narration plus several character bubbles.
      const contentRoot = scope.querySelector(`:scope > #${CSS.escape(key)}`) || scope.firstElementChild || scope;
      [...contentRoot.querySelectorAll(".chat")].forEach((bubble) => {
        let segment = bubble;
        while (segment.parentElement && segment.parentElement !== contentRoot) {
          segment = segment.parentElement;
        }
        const user = segment.classList.contains("ml-auto") || Boolean(segment.querySelector(":scope > .ml-auto"));
        const name = segment.querySelector("span.caption1");
        parts.push({
          role: user ? "user" : "character",
          speaker: user ? (name?.textContent.trim() || "사용자") : (name?.textContent.trim() || "나레이션"),
          text: bubbleText(bubble)
        });
      });
    }

    const validParts = parts.filter((part) => part.text);
    if (!validParts.length) continue;
    const speakers = [...new Set(validParts.map((part) => part.speaker).filter(Boolean))];
    const showSpeakerLabels = speakers.length > 1;
    output.push({
      key,
      position: rowPosition(row),
      role: validParts[0].role,
      speaker: speakers.join("/") || null,
      text: validParts.map((part) => (
        showSpeakerLabels ? `[${part.speaker}] ${part.text}` : part.text
      )).join("\n")
    });
  }
  return output;
}

function sendProgress(count, scrollTop, phase) {
  chrome.runtime.sendMessage({ type: "EXPORT_PROGRESS", count, scrollTop, phase }).catch(() => {});
}

function findConversationLog() {
  const explicit = document.querySelector('[role="log"]');
  if (explicit) return explicit;

  const firstRow = document.querySelector("[data-index][data-key]");
  let candidate = firstRow?.parentElement || null;
  while (candidate && candidate !== document.body) {
    const rowCount = candidate.querySelectorAll("[data-index][data-key]").length;
    const style = getComputedStyle(candidate);
    const scrollable = candidate.scrollHeight > candidate.clientHeight
      || ["auto", "scroll"].includes(style.overflowY);
    if (rowCount > 1 && scrollable) return candidate;
    candidate = candidate.parentElement;
  }
  return null;
}

async function readAllTurns() {
  const log = findConversationLog();
  if (!log) throw new Error("제타 대화 목록을 찾지 못했습니다. 대화방을 새로고침해 보세요.");

  const originalScrollTop = log.scrollTop;
  const loadingKeys = new Set();

  log.scrollTop = 0;
  await sleep(SCROLL_PAUSE_MS);

  try {
    // First pass: force Zeta to load every older page.
    let stuck = 0;
    while (stuck < OLDEST_SETTLE_ATTEMPTS) {
      for (const turn of visibleTurns(log)) loadingKeys.add(turn.key);
      sendProgress(loadingKeys.size, log.scrollTop, "loading");

      const before = log.scrollTop;
      log.scrollTop = before - log.clientHeight * 0.65;
      stuck = log.scrollTop === before ? stuck + 1 : 0;
      await sleep(SCROLL_PAUSE_MS);
    }

    // Second pass: after all older pages are loaded, collect each row's visual
    // Y position. Zeta's data-index is grouped by virtualized loading batches,
    // while the translateY position is the actual chronological screen order.
    const seen = new Map();
    stuck = 0;
    while (stuck < 3) {
      for (const turn of visibleTurns(log)) seen.set(turn.key, turn);
      sendProgress(seen.size, log.scrollTop, "ordering");

      const before = log.scrollTop;
      log.scrollTop = Math.min(0, before + log.clientHeight * 0.65);
      stuck = log.scrollTop === before ? stuck + 1 : 0;
      await sleep(SCROLL_PAUSE_MS);
    }

    return [...seen.values()]
      .sort((a, b) => a.position - b.position)
      .map(({ position: _position, ...item }, turnIndex) => ({ turn: turnIndex + 1, ...item }));
  } finally {
    log.scrollTop = originalScrollTop;
  }
}

function buildExport(turns, room) {
  const exportedAt = new Date().toISOString();
  return {
    title: room.title,
    exported_at: exportedAt,
    turn_count: turns.length,
    turns: turns.map(({ key: _key, ...turn }) => turn)
  };
}

function renderMarkdown(data) {
  const lines = [
    `# ${data.title}`,
    "",
    `- Exported: ${data.exported_at}`,
    `- Turns: ${data.turn_count}`,
    ""
  ];

  for (const turn of data.turns) {
    const side = turn.role === "user" ? "사용자" : "AI";
    lines.push(
      `## Turn ${turn.turn} · ${side}`,
      "",
      turn.speaker ? `**화자:** ${turn.speaker}` : "",
      turn.speaker ? "" : null,
      turn.text,
      ""
    );
  }
  return lines.filter((line) => line !== null).join("\n");
}

function filename(extension) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  return `zeta-${latestRoom.room_id}-${timestamp}.${extension}`;
}

chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request.type === "CHAT_FRAME_READY") {
    sendResponse({ ok: Boolean(findConversationLog()) });
    return false;
  }

  if (request.type === "SCAN_ROOM") {
    (async () => {
      try {
        latestRoom = request.room;
        if (!latestRoom?.room_id || !latestRoom?.room_url) {
          throw new Error("현재 Zeta 채팅방 정보를 받지 못했습니다.");
        }
        latestExport = buildExport(await readAllTurns(), latestRoom);
        sendResponse({
          ok: true,
          room_id: latestRoom.room_id,
          title: latestRoom.title,
          turn_count: latestExport.turn_count,
          scanned_at: latestExport.exported_at
        });
      } catch (error) {
        sendResponse({ ok: false, error: error.message });
      }
    })();
    return true;
  }

  if (request.type === "GET_EXPORT") {
    if (!latestExport) {
      sendResponse({ ok: false, error: "먼저 이 채팅방의 전체 대화를 확인해 주세요." });
      return false;
    }
    const isJson = request.format === "json";
    sendResponse({
      ok: true,
      filename: filename(isJson ? "json" : "md"),
      mime: isJson ? "application/json;charset=utf-8" : "text/markdown;charset=utf-8",
      content: isJson ? JSON.stringify(latestExport, null, 2) : renderMarkdown(latestExport)
    });
    return false;
  }

  return false;
});
