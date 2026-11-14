const statusEl = document.querySelector("#status");
const buttons = [...document.querySelectorAll("button")];

function setBusy(busy) {
  buttons.forEach((button) => { button.disabled = busy; });
}

async function exportConversation(format) {
  setBusy(true);
  statusEl.className = "";
  statusEl.textContent = "대화를 읽는 중입니다…";
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.id || !/^https:\/\/zeta-ai\.io\/ko\/rooms\//.test(tab.url || "")) {
      throw new Error("Zeta 대화방 탭에서 실행해 주세요.");
    }
    const response = await chrome.tabs.sendMessage(tab.id, { type: "EXPORT", format });
    if (!response?.ok) throw new Error(response?.error || "대화를 읽지 못했습니다.");
    const blob = new Blob([response.content], { type: response.mime });
    const url = URL.createObjectURL(blob);
    await chrome.downloads.download({ url, filename: response.filename, saveAs: true });
    setTimeout(() => URL.revokeObjectURL(url), 30_000);
    statusEl.className = "success";
    statusEl.textContent = `${response.count}개 메시지를 저장했습니다.`;
  } catch (error) {
    statusEl.className = "error";
    statusEl.textContent = error.message;
  } finally {
    setBusy(false);
  }
}

document.querySelector("#json").addEventListener("click", () => exportConversation("json"));
document.querySelector("#markdown").addEventListener("click", () => exportConversation("markdown"));
