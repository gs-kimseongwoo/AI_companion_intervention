const DEFAULTS = {
  enabled: false,
  endpoint: "https://api.typesafe.ai/v1/systemone",
  model: "jev-latest",
  timeoutMs: 15000,
  maxTurns: 30
};

chrome.runtime.onInstalled.addListener(async () => {
  const current = await chrome.storage.local.get(Object.keys(DEFAULTS));
  await chrome.storage.local.set(Object.fromEntries(
    Object.entries(DEFAULTS).filter(([key]) => current[key] === undefined)
  ));
});

chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request.type !== "JEV_EVALUATE") return false;
  evaluate(request.payload).then(sendResponse).catch((error) => {
    sendResponse({ ok: false, error: error.message });
  });
  return true;
});

async function evaluate(payload) {
  const settings = { ...DEFAULTS, ...await chrome.storage.local.get(null) };
  if (!settings.apiKey) return { ok: false, error: "JEV API 키가 설정되지 않았습니다." };
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), Number(settings.timeoutMs));
  const startedAt = performance.now();
  try {
    const response = await fetch(settings.endpoint, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${settings.apiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload),
      signal: controller.signal
    });
    const bodyText = await response.text();
    let body;
    try { body = JSON.parse(bodyText); } catch { body = { raw_text: bodyText.slice(0, 2000) }; }
    const latencyMs = performance.now() - startedAt;
    if (!response.ok) {
      return { ok: false, status: response.status, latencyMs, error: `JEV HTTP ${response.status}`, response: body };
    }
    return { ok: true, status: response.status, latencyMs, response: body };
  } catch (error) {
    return {
      ok: false,
      latencyMs: performance.now() - startedAt,
      error: error.name === "AbortError" ? `JEV 요청이 ${settings.timeoutMs}ms 후 timeout되었습니다.` : error.message
    };
  } finally {
    clearTimeout(timeout);
  }
}
