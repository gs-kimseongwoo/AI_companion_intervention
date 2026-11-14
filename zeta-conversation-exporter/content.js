const TEMP_KEY_PREFIXES = ["pending-", "local-"];

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

function visibleMessages() {
  const log = document.querySelector('[role="log"]');
  if (!log) throw new Error("제타 대화 목록을 찾지 못했습니다. 페이지를 새로고침해 보세요.");
  const output = [];
  const selector = '[data-sentry-component="LeftTextContent"], [data-sentry-component="RightTextContent"], [data-sentry-component="NarratorBubble"]';
  for (const row of log.querySelectorAll("[data-index][data-key]")) {
    const scope = row.querySelector(".swiper-slide-active") || row;
    const sides = [...scope.querySelectorAll(selector)];
    if (!sides.length) continue;
    sides.forEach((side, offset) => {
      const component = side.dataset.sentryComponent;
      const narrator = component === "NarratorBubble";
      const bubble = side.querySelector(".chat");
      const name = narrator ? null : side.querySelector("span.caption1");
      output.push({
        key: offset === 0 ? row.dataset.key : `${row.dataset.key}#${offset}`,
        index: Number(row.dataset.index) + offset / 100,
        role: component === "RightTextContent" ? "user" : "character",
        speaker: narrator ? "나레이션" : (name?.textContent.trim() || null),
        text: bubble ? bubbleText(bubble) : side.innerText.trim(),
        has_image: Boolean(side.querySelector('[data-sentry-component="WrappedImage"]'))
      });
    });
  }
  return output;
}

async function readAllMessages() {
  const log = document.querySelector('[role="log"]');
  if (!log) throw new Error("제타 대화 목록을 찾지 못했습니다.");
  const originalScrollTop = log.scrollTop;
  const seen = new Map();
  log.scrollTop = 0;
  await sleep(500);
  let stuck = 0;
  try {
    while (stuck < 3) {
      for (const message of visibleMessages()) {
        if (!TEMP_KEY_PREFIXES.some((prefix) => message.key.startsWith(prefix))) {
          seen.set(message.key, message);
        }
      }
      const before = log.scrollTop;
      log.scrollTop = before - log.clientHeight * 0.65;
      stuck = log.scrollTop === before ? stuck + 1 : 0;
      await sleep(500);
    }
  } finally {
    log.scrollTop = originalScrollTop;
  }
  return [...seen.values()].sort((a, b) => a.index - b.index);
}

function safeRoomId() {
  return location.pathname.match(/\/rooms\/([^/?#]+)/)?.[1] || "unknown-room";
}

function asMarkdown(exported) {
  const lines = [
    `# Zeta conversation`,
    "",
    `- Room: ${exported.room_url}`,
    `- Exported: ${exported.exported_at}`,
    `- Messages: ${exported.messages.length}`,
    ""
  ];
  for (const message of exported.messages) {
    const label = message.speaker || (message.role === "user" ? "사용자" : "캐릭터");
    lines.push(`## ${label}`, "", message.text || (message.has_image ? "[이미지]" : ""), "");
  }
  return lines.join("\n");
}

chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request.type !== "EXPORT") return false;
  (async () => {
    try {
      const messages = await readAllMessages();
      const exported = {
        schema: "zeta-conversation-export/1",
        room_url: `${location.origin}${location.pathname}`,
        room_id: safeRoomId(),
        title: document.title,
        exported_at: new Date().toISOString(),
        messages
      };
      const isJson = request.format === "json";
      sendResponse({
        ok: true,
        count: messages.length,
        filename: `zeta-${safeRoomId()}-${new Date().toISOString().replace(/[:.]/g, "-")}.${isJson ? "json" : "md"}`,
        mime: isJson ? "application/json;charset=utf-8" : "text/markdown;charset=utf-8",
        content: isJson ? JSON.stringify(exported, null, 2) : asMarkdown(exported)
      });
    } catch (error) {
      sendResponse({ ok: false, error: error.message });
    }
  })();
  return true;
});
