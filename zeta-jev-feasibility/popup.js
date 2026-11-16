const enabled = document.querySelector("#enabled");
const summary = document.querySelector("#summary");
const connection = document.querySelector("#connection");

function connectionText(status) {
  if (!status) return { className: "error", text: "Zeta 탭과 아직 연결되지 않았습니다. 확장을 다시 로드한 뒤 대화방을 새로고침하세요." };
  const labels = {
    initializing: "초기화 중",
    waiting_for_conversation: "대화 화면 대기 중",
    ready: "대화 감지 준비됨",
    trigger_detected: "새 사용자 발화 감지됨",
    requesting: "JEV 요청 중",
    last_request_succeeded: "최근 JEV 요청 성공",
    last_request_failed: "최근 JEV 요청 실패"
  };
  const readyStates = new Set(["ready", "trigger_detected", "requesting", "last_request_succeeded"]);
  return {
    className: status.state === "last_request_failed" ? "error" : (readyStates.has(status.state) ? "ready" : ""),
    text: `${labels[status.state] || status.state}${status.detail ? ` · ${status.detail}` : ""}`
  };
}

function stats(logs) {
  if (!logs.length) return "아직 측정 기록이 없습니다.";
  const success = logs.filter((log) => log.success);
  const percentile = (values, p) => {
    if (!values.length) return null;
    const sorted = [...values].sort((a, b) => a - b);
    return sorted[Math.ceil((sorted.length - 1) * p)];
  };
  const hidden = success.map((log) => log.assistant_hidden_ms).filter(Number.isFinite);
  const api = success.map((log) => log.api_latency_ms).filter(Number.isFinite);
  const hiddenP50 = percentile(hidden, .5);
  const hiddenP90 = percentile(hidden, .9);
  const apiP50 = percentile(api, .5);
  const last = logs.at(-1);
  return [
    `전체 ${logs.length}회 · 성공 ${success.length}회 · 실패 ${logs.length - success.length}회`,
    hiddenP50 === null ? "성공 요청 없음" : `전체 가림 p50 ${Math.round(hiddenP50)}ms · p90 ${Math.round(hiddenP90)}ms`,
    apiP50 === null ? "API 지연 기록 없음" : `JEV API p50 ${Math.round(apiP50)}ms`,
    `최근: ${last.success ? `실제 가림 ${Math.round(last.assistant_hidden_ms)}ms` : last.error}`
  ].join("<br>");
}

async function refresh() {
  const data = await chrome.storage.local.get({ enabled: false, feasibilityLogs: [], feasibilityStatus: null });
  enabled.checked = data.enabled;
  summary.innerHTML = stats(data.feasibilityLogs);
  const status = connectionText(data.feasibilityStatus);
  connection.className = status.className;
  connection.textContent = status.text;
}

enabled.addEventListener("change", async () => {
  await chrome.storage.local.set({ enabled: enabled.checked });
  refresh();
});

document.querySelector("#download").addEventListener("click", async () => {
  const { feasibilityLogs = [] } = await chrome.storage.local.get("feasibilityLogs");
  const blob = new Blob([JSON.stringify({
    schema: "zeta-jev-feasibility-log/1",
    exported_at: new Date().toISOString(),
    events: feasibilityLogs
  }, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  await chrome.downloads.download({
    url,
    filename: `zeta-jev-feasibility-${new Date().toISOString().replace(/[:.]/g, "-")}.json`,
    saveAs: true
  });
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
});

document.querySelector("#clear").addEventListener("click", async () => {
  if (!confirm("저장된 feasibility 로그를 모두 지울까요?")) return;
  await chrome.storage.local.set({ feasibilityLogs: [] });
  refresh();
});

document.querySelector("#options").addEventListener("click", () => chrome.runtime.openOptionsPage());
refresh();
