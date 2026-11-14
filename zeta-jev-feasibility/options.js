const DEFAULTS = {
  apiKey: "",
  endpoint: "https://api.typesafe.ai/v1/systemone",
  model: "jev-latest",
  timeoutMs: 15000,
  maxTurns: 30
};

async function load() {
  const settings = await chrome.storage.local.get(DEFAULTS);
  for (const key of Object.keys(DEFAULTS)) document.querySelector(`#${key}`).value = settings[key];
}

document.querySelector("#form").addEventListener("submit", async (event) => {
  event.preventDefault();
  await chrome.storage.local.set({
    apiKey: document.querySelector("#apiKey").value.trim(),
    endpoint: document.querySelector("#endpoint").value.trim(),
    model: document.querySelector("#model").value.trim(),
    timeoutMs: Number(document.querySelector("#timeoutMs").value),
    maxTurns: Number(document.querySelector("#maxTurns").value)
  });
  const saved = document.querySelector("#saved");
  saved.textContent = "저장했습니다.";
  setTimeout(() => { saved.textContent = ""; }, 1800);
});

load();
