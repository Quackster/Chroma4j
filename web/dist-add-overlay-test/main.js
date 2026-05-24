import { loadChroma4j } from "../dist/chroma4j.js?v=wasm-glow-mode-20260524";

const controls = {
  url: document.getElementById("swf-url"),
  state: document.getElementById("state"),
  direction: document.getElementById("direction"),
  format: document.getElementById("format"),
  addMode: document.getElementById("add-mode"),
  backgroundUrl: document.getElementById("background-url"),
  render: document.getElementById("render"),
  status: document.getElementById("status")
};

const stages = [...document.querySelectorAll(".stage")];

controls.render.addEventListener("click", render);
controls.backgroundUrl.addEventListener("input", applyCustomBackground);

render();

async function render() {
  controls.render.disabled = true;
  controls.status.textContent = "Rendering furniture...";
  clearStages();
  try {
    const chroma = await loadChroma4j({ basePath: "../dist" });
    const result = await chroma.renderFromUrl(controls.url.value, {
      basePath: "../dist",
      state: controls.state.value,
      direction: controls.direction.value,
      format: controls.format.value,
      addMode: controls.addMode.value,
      canvas: "transparent",
      bg: false,
      crop: true,
      loop: true
    });

    const baseUrl = await result.baseDataUrl();
    const addUrl = await result.addDataUrl();
    for (const stage of stages) {
      stage.append(layeredImage(baseUrl, addUrl));
    }
    applyCustomBackground();
    controls.status.textContent = `${result.width} x ${result.height} ${result.format.toUpperCase()} rendered. Glow mode: ${result.addMode === "overlay" ? "overlay" : "baked"}.`;
  } catch (error) {
    controls.status.textContent = error.message;
  } finally {
    controls.render.disabled = false;
  }
}

function layeredImage(baseUrl, addUrl) {
  const stack = document.createElement("div");
  stack.className = "stack";

  const base = document.createElement("img");
  base.alt = "";
  base.src = baseUrl;
  stack.append(base);

  if (addUrl) {
    const add = document.createElement("img");
    add.alt = "";
    add.className = "add";
    add.src = addUrl;
    stack.append(add);
  }

  return stack;
}

function clearStages() {
  for (const stage of stages) {
    stage.replaceChildren();
  }
}

function applyCustomBackground() {
  const custom = document.querySelector("[data-stage='custom']");
  const url = controls.backgroundUrl.value.trim();
  custom.style.backgroundImage = url ? `url("${url}")` : "";
}
