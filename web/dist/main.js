import { loadChroma4j } from "./chroma4j.js?v=wasm-render-20260524";

const controls = {
  url: document.getElementById("swf-url"),
  state: document.getElementById("state"),
  direction: document.getElementById("direction"),
  color: document.getElementById("color"),
  canvas: document.getElementById("canvas-color"),
  small: document.getElementById("small"),
  shadow: document.getElementById("shadow"),
  crop: document.getElementById("crop"),
  icon: document.getElementById("icon"),
  render: document.getElementById("render"),
  download: document.getElementById("download"),
  status: document.getElementById("status"),
  preview: document.getElementById("preview")
};

let lastResult;

controls.render.addEventListener("click", async () => {
  resetPreview();
  controls.status.textContent = "Loading TeaVM renderer...";
  controls.render.disabled = true;
  controls.download.disabled = true;
  try {
    const chroma = await loadChroma4j();
    controls.status.textContent = "Fetching SWF and rendering...";
    lastResult = await chroma.renderFromUrl(controls.url.value, {
      state: controls.state.value,
      direction: controls.direction.value,
      color: controls.color.value,
      canvas: controls.canvas.value,
      small: controls.small.checked,
      shadow: controls.shadow.checked,
      crop: controls.crop.checked,
      icon: controls.icon.checked
    }, controls.preview);
    controls.download.disabled = false;
    controls.status.textContent = `${lastResult.width} x ${lastResult.height} PNG rendered entirely in the browser.`;
  } catch (error) {
    controls.status.textContent = error.message;
  } finally {
    controls.render.disabled = false;
  }
});

function resetPreview() {
  lastResult = undefined;
  controls.preview.width = 1;
  controls.preview.height = 1;
  const context = controls.preview.getContext("2d");
  context.clearRect(0, 0, controls.preview.width, controls.preview.height);
}

controls.download.addEventListener("click", async () => {
  if (!lastResult) return;
  const blob = await lastResult.blob();
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = "furni.png";
  link.click();
  URL.revokeObjectURL(link.href);
});
