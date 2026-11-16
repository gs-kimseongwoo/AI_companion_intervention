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
    intervention_holding: "개입 표시 중 · 응답 가림 유지",
    last_request_succeeded: "최근 JEV 요청 성공",
    last_request_failed: "최근 JEV 요청 실패"
  };
  const readyStates = new Set(["ready", "trigger_detected", "requesting", "intervention_holding", "last_request_succeeded"]);
  return {
    className: status.state === "last_request_failed" ? "error" : (readyStates.has(status.state) ? "ready" : ""),
    text: `${labels[status.state] || status.state}${status.detail ? ` · ${status.detail}` : ""}`
  };
}

function stats(logs) {
  if (!logs.length) return "아직 측정 기록이 없습니다.";
  const last = logs.at(-1);
  if (last.decision === "waiting_for_jev") {
    const started = Date.parse(last.triggered_at);
    const elapsed = Number.isFinite(started) ? Math.max(0, Date.now() - started) : null;
    return [
      `저장된 요청 ${logs.length}개`,
      "최근 요청: JEV 응답 기다리는 중",
      `현재 경과: ${elapsed === null ? "계산 불가" : `${elapsed}ms`}`,
      `Zeta AI 응답: ${last.zeta_response_detected_at ? "이미 도착해서 가리는 중" : "아직 도착하지 않음"}`
    ].join("<br>");
  }
  const latency = last.jev_latency_ms ?? last.api_latency_ms;
  const score = last.jev_context_shift_score ?? last.context_shift_score;
  const before = last.jev_returned_before_zeta_response ?? last.jev_finished_before_zeta_reply;
  const extraWait = last.extra_wait_after_zeta_response_ms
    ?? (before === false ? last.hidden_until_decision_ms : 0);
  const decisionLabels = {
    hold_and_popup: "응답 계속 가림 + 팝업",
    show_zeta_response: "Zeta 응답 공개",
    fail_open: "JEV 오류로 Zeta 응답 공개"
  };
  const timing = before === true
    ? "JEV가 Zeta 응답보다 먼저 도착 · 추가 대기 0ms"
    : (before === false
      ? `Zeta 응답 후 JEV가 ${Math.round(extraWait || 0)}ms 뒤 도착`
      : "Zeta/JEV 도착 순서 기록 없음");
  return [
    `저장된 요청 ${logs.length}개`,
    `최근 JEV score: ${Number.isFinite(score) ? Number(score).toFixed(3) : "없음"}`,
    `JEV 응답 시간: ${Number.isFinite(latency) ? `${Math.round(latency)}ms` : "기록 없음"}`,
    timing,
    `결정: ${decisionLabels[last.decision] || last.decision || "이전 로그 형식"}`,
    last.jev_error ? `오류: ${last.jev_error}` : ""
  ].filter(Boolean).join("<br>");
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
    schema: "zeta-jev-feasibility-log/2",
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
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && (changes.feasibilityLogs || changes.feasibilityStatus)) refresh();
});
setInterval(refresh, 500);
refresh();
