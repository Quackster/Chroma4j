import { loadChroma4j } from "./chroma4j.js?v=wasm-glow-mode-20260524";

const controls = {
  url: document.getElementById("swf-url"),
  state: document.getElementById("state"),
  direction: document.getElementById("direction"),
  color: document.getElementById("color"),
  canvas: document.getElementById("canvas-color"),
  format: document.getElementById("format"),
  addMode: document.getElementById("add-mode"),
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
  previewStack: document.getElementById("preview-stack"),
  previewBase: document.getElementById("preview-base"),
  previewAdd: document.getElementById("preview-add")
};

let lastResult;
let lastRenderUrl;
let lastRenderOptions;

controls.render.addEventListener("click", async () => {
  resetPreview();
  controls.status.textContent = "Loading TeaVM renderer...";
  controls.render.disabled = true;
  controls.download.disabled = true;
  try {
    const chroma = await loadChroma4j();
    controls.status.textContent = "Fetching SWF and rendering...";
    lastRenderUrl = controls.url.value;
    lastRenderOptions = {
      state: controls.state.value,
      direction: controls.direction.value,
      color: controls.color.value,
      canvas: controls.canvas.value,
      format: controls.format.value,
      addMode: controls.addMode.value,
      small: controls.small.checked,
      shadow: controls.shadow.checked,
      crop: controls.crop.checked,
      icon: controls.icon.checked,
      bg: controls.bg.checked,
      loop: controls.loop.checked
    };
    lastResult = await chroma.renderFromUrl(lastRenderUrl, lastRenderOptions, controls.preview);
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
  controls.previewStack.hidden = true;
  controls.previewBase.removeAttribute("src");
  controls.previewAdd.removeAttribute("src");
  controls.previewAdd.hidden = true;
  clearPreviewBackground();
}

async function updatePreview(result) {
  if (!result.additive && result.format !== "gif" && result.format !== "apng") {
    controls.preview.hidden = false;
    controls.previewStack.hidden = true;
    controls.previewBase.removeAttribute("src");
    controls.previewAdd.removeAttribute("src");
    controls.previewAdd.hidden = true;
    clearPreviewBackground();
    return;
  }

  controls.preview.hidden = true;
  controls.previewStack.hidden = false;
  applyPreviewBackground(result);
  controls.previewBase.src = result.additive ? await result.baseDataUrl() : await result.dataUrl();
  const addUrl = result.additive ? await result.addDataUrl() : "";
  controls.previewAdd.hidden = !addUrl;
  if (addUrl) {
    controls.previewAdd.src = addUrl;
  } else {
    controls.previewAdd.removeAttribute("src");
  }
}

function applyPreviewBackground(result) {
  clearPreviewBackground();
  if (!result.backgroundDeferred || !result.backgroundUrl) {
    return;
  }
  controls.previewStack.classList.add("with-background");
  controls.previewStack.style.backgroundImage = `url("${result.backgroundUrl}")`;
  controls.previewStack.style.backgroundPosition = `-${result.cropX}px -${result.cropY}px`;
  controls.previewStack.style.backgroundSize = `${result.backgroundWidth}px ${result.backgroundHeight}px`;
}

function clearPreviewBackground() {
  controls.previewStack.classList.remove("with-background");
  controls.previewStack.style.backgroundImage = "";
  controls.previewStack.style.backgroundPosition = "";
  controls.previewStack.style.backgroundSize = "";
}

controls.download.addEventListener("click", async () => {
  if (!lastResult) return;
  controls.download.disabled = true;
  let blob;
  try {
    if (lastResult.additive && lastResult.format !== "png") {
      controls.status.textContent = "Rendering baked animation for download...";
      const chroma = await loadChroma4j();
      const baked = await chroma.renderFromUrl(lastRenderUrl, { ...lastRenderOptions, addMode: "baked" });
      blob = await baked.blob();
    } else {
      blob = await lastResult.blob();
    }
  } catch (error) {
    controls.status.textContent = error.message;
    return;
  } finally {
    controls.download.disabled = false;
  }
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  const extension = lastResult.format === "gif" ? "gif" : (lastResult.format === "apng" ? "apng" : "png");
  link.download = `furni.${extension}`;
  link.click();
  URL.revokeObjectURL(link.href);
});
