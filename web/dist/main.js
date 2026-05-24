import { loadChroma4j } from "./chroma4j.js?v=wasm-canvas-url-20260524";

const controls = {
  url: document.getElementById("swf-url"),
  state: document.getElementById("state"),
  direction: document.getElementById("direction"),
  color: document.getElementById("color"),
  canvas: document.getElementById("canvas-color"),
  format: document.getElementById("format"),
  small: document.getElementById("small"),
  shadow: document.getElementById("shadow"),
  crop: document.getElementById("crop"),
  icon: document.getElementById("icon"),
  bg: document.getElementById("bg"),
  loop: document.getElementById("loop"),
  render: document.getElementById("render"),
  download: document.getElementById("download"),
  status: document.getElementById("status"),
  preview: document.getElementById("preview"),
  previewGif: document.getElementById("preview-gif")
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
      format: controls.format.value,
      small: controls.small.checked,
      shadow: controls.shadow.checked,
      crop: controls.crop.checked,
      icon: controls.icon.checked,
      bg: controls.bg.checked,
      loop: controls.loop.checked
    }, controls.preview);
    await updatePreview(lastResult);
    controls.download.disabled = false;
    controls.status.textContent = `${lastResult.width} x ${lastResult.height} ${lastResult.format.toUpperCase()} rendered entirely in the browser.`;
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
  controls.preview.hidden = false;
  controls.previewGif.hidden = true;
  controls.previewGif.removeAttribute("src");
  clearGifBackground();
}

async function updatePreview(result) {
  if (result.format !== "gif" && result.format !== "apng") {
    controls.preview.hidden = false;
    controls.previewGif.hidden = true;
    controls.previewGif.removeAttribute("src");
    clearGifBackground();
    return;
  }

  controls.preview.hidden = true;
  controls.previewGif.hidden = false;
  applyGifBackground(result);
  controls.previewGif.src = await result.dataUrl();
}

function applyGifBackground(result) {
  clearGifBackground();
  if (!result.backgroundDeferred || !result.backgroundUrl) {
    return;
  }
  controls.previewGif.classList.add("with-background");
  controls.previewGif.style.backgroundImage = `url("${result.backgroundUrl}")`;
  controls.previewGif.style.backgroundPosition = `-${result.cropX}px -${result.cropY}px`;
  controls.previewGif.style.backgroundSize = `${result.backgroundWidth}px ${result.backgroundHeight}px`;
}

function clearGifBackground() {
  controls.previewGif.classList.remove("with-background");
  controls.previewGif.style.backgroundImage = "";
  controls.previewGif.style.backgroundPosition = "";
  controls.previewGif.style.backgroundSize = "";
}

controls.download.addEventListener("click", async () => {
  if (!lastResult) return;
  const blob = await lastResult.blob();
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  const extension = lastResult.format === "gif" ? "gif" : (lastResult.format === "apng" ? "apng" : "png");
  link.download = `furni.${extension}`;
  link.click();
  URL.revokeObjectURL(link.href);
});
