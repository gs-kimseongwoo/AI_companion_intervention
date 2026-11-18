const roomTitleEl = document.querySelector("#room-title");
const cachedCountEl = document.querySelector("#cached-count");
const statusEl = document.querySelector("#status");
const scanButton = document.querySelector("#scan");
const resultPanel = document.querySelector("#result-panel");
const countEl = document.querySelector("#count");
const confirmPanel = document.querySelector("#confirm-panel");
const confirmCountEl = document.querySelector("#confirm-count");
const skipConfirmEl = document.querySelector("#skip-confirm");
const allButtons = [...document.querySelectorAll("button")];

let activeTabId = null;
let chatFrameId = null;
let roomInfo = null;
let scanResult = null;

function setBusy(busy) {
  allButtons.forEach((button) => { button.disabled = busy; });
}

function setStatus(text, className = "") {
  statusEl.textContent = text;
  statusEl.className = `status ${className}`.trim();
}

function countText(result) {
  return `총 ${result.turn_count}턴`;
}

async function getSetting(key, fallback) {
  const stored = await chrome.storage.local.get(key);
  return stored[key] ?? fallback;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function findChatFrame(tabId) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const frames = await chrome.webNavigation.getAllFrames({ tabId });
    const candidates = frames.filter((frame) => frame.url.startsWith("https://frame.pingpong.ai/"));
    for (const frame of candidates) {
      try {
        const ready = await chrome.tabs.sendMessage(
          tabId,
          { type: "CHAT_FRAME_READY" },
          { frameId: frame.frameId }
        );
        if (ready?.ok) return frame.frameId;
      } catch (_error) {
        // The iframe can appear before its content script finishes loading.
      }
    }
    await sleep(250);
  }
  throw new Error("Zeta 채팅 화면이 아직 준비되지 않았습니다. 잠시 후 다시 열어 주세요.");
}

function sendToChat(message) {
  return chrome.tabs.sendMessage(activeTabId, message, { frameId: chatFrameId });
}

async function initialize() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.id || !/^https:\/\/zeta-ai\.io\/ko\/rooms\//.test(tab.url || "")) {
      throw new Error("내보낼 Zeta 채팅방에서 익스텐션을 열어 주세요.");
    }
    activeTabId = tab.id;
    skipConfirmEl.checked = await getSetting("skipExportConfirm", false);
    const match = new URL(tab.url).pathname.match(/\/rooms\/([^/?#]+)/);
    roomInfo = {
      room_id: match?.[1] || "unknown-room",
      room_url: tab.url.split(/[?#]/)[0],
      title: (tab.title || "").replace(/\s*-\s*제타\s*$/, "").trim() || "Zeta conversation"
    };
    chatFrameId = await findChatFrame(activeTabId);
    roomTitleEl.textContent = roomInfo.title;

    const cache = await getSetting(`roomTurnCount:${roomInfo.room_id}`, null);
    if (cache) {
      cachedCountEl.hidden = false;
      cachedCountEl.textContent = `마지막 확인: ${countText(cache)} · ${new Date(cache.scanned_at).toLocaleString()}`;
    }
  } catch (error) {
    roomTitleEl.textContent = "채팅방을 찾지 못했습니다.";
    setStatus(error.message, "error");
    scanButton.disabled = true;
  }
}

chrome.runtime.onMessage.addListener((message) => {
  if (message.type !== "EXPORT_PROGRESS") return;
  setStatus(message.phase === "ordering"
    ? `시간순으로 정렬 중… ${message.count}턴 확인`
    : `과거 대화를 불러오는 중… ${message.count}턴 확인`);
});

scanButton.addEventListener("click", async () => {
  setBusy(true);
  resultPanel.hidden = true;
  confirmPanel.hidden = true;
  setStatus("전체 대화 확인을 시작합니다…");
  try {
    const response = await sendToChat({ type: "SCAN_ROOM", room: roomInfo });
    if (!response?.ok) throw new Error(response?.error || "전체 대화를 읽지 못했습니다.");
    scanResult = response;
    countEl.textContent = countText(response);
    resultPanel.hidden = false;
    setStatus("전체 대화를 확인했습니다.", "success");
    await chrome.storage.local.set({ [`roomTurnCount:${roomInfo.room_id}`]: response });
    cachedCountEl.hidden = true;
    if (skipConfirmEl.checked) await downloadBoth();
    else {
      confirmCountEl.textContent = countText(response);
      confirmPanel.hidden = false;
    }
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    setBusy(false);
  }
});

async function downloadBoth() {
  setBusy(true);
  try {
    const responses = await Promise.all([
      sendToChat({ type: "GET_EXPORT", format: "json" }),
      sendToChat({ type: "GET_EXPORT", format: "markdown" })
    ]);
    for (const response of responses) {
      if (!response?.ok) throw new Error(response?.error || "내보낼 데이터를 만들지 못했습니다.");
    }
    for (const response of responses) {
      const blob = new Blob([response.content], { type: response.mime });
      const url = URL.createObjectURL(blob);
      await chrome.downloads.download({ url, filename: response.filename, saveAs: false });
      setTimeout(() => URL.revokeObjectURL(url), 30_000);
    }
    confirmPanel.hidden = true;
    setStatus("JSON과 Markdown 파일을 저장했습니다.", "success");
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    setBusy(false);
  }
}

skipConfirmEl.addEventListener("change", () => {
  chrome.storage.local.set({ skipExportConfirm: skipConfirmEl.checked });
});
document.querySelector("#cancel").addEventListener("click", () => {
  confirmPanel.hidden = true;
});
document.querySelector("#confirm").addEventListener("click", async () => {
  if (!scanResult) return;
  await downloadBoth();
});

initialize();
