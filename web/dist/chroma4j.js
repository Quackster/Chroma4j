let teavmInstancePromise;
const BUILD_VERSION = "wasm-glow-mode-20260524";

export async function loadChroma4j(options = {}) {
  await ensureTeaVm(options.basePath || ".");
  return {
    renderFromUrl,
    renderFromBytes
  };
}

async function ensureTeaVm(basePath) {
  if (globalThis.__chroma4jTeaVmRenderSwf) {
    return;
  }
  if (!teavmInstancePromise) {
    teavmInstancePromise = (async () => {
      await loadScript(`${basePath}/wasm-gc/chroma-wasm.wasm-runtime.js?v=${BUILD_VERSION}`);
      const teavm = await globalThis.TeaVM.wasmGC.load(`${basePath}/wasm-gc/chroma-wasm.wasm?v=${BUILD_VERSION}`);
      if (teavm.exports.main) {
        teavm.exports.main([]);
      }
      globalThis.__chroma4jTeaVmParseSwf = teavm.exports.parseSwfBase64;
      globalThis.__chroma4jTeaVmRenderSwf = teavm.exports.renderSwfBase64;
      return teavm;
    })();
  }
  await teavmInstancePromise;
}

async function renderFromUrl(url, options = {}, target) {
  const response = await fetch(url, { mode: "cors" });
  if (!response.ok) {
    throw new Error(`SWF fetch failed: HTTP ${response.status}`);
  }
  const bytes = await response.arrayBuffer();
  const sprite = options.sprite || spriteFromUrl(url);
  return renderFromBytes(bytes, { ...options, sprite }, target);
}

async function renderFromBytes(bytes, options = {}, target) {
  await ensureTeaVm(options.basePath || ".");
  const normalized = await normalizeOptions(options);
  const rendered = JSON.parse(globalThis.__chroma4jTeaVmRenderSwf(bytesToBase64(bytes), JSON.stringify(normalized)));
  if (!rendered.ok) {
    throw new Error(rendered.error || "SWF rendering failed");
  }

  const mime = rendered.mime || "image/png";
  const format = rendered.format || (mime === "image/gif" ? "gif" : "png");
  const dataBase64 = rendered.dataBase64 || rendered.pngBase64;
  const outputBytes = base64ToBytes(dataBase64);
  const addBase64 = rendered.addBase64 || "";
  const addBytes = addBase64 ? base64ToBytes(addBase64) : undefined;
  const canvas = target || document.createElement("canvas");
  await paintImage(canvas, outputBytes, mime, format, rendered.width, rendered.height, addBytes);
  return {
    canvas,
    width: rendered.width,
    height: rendered.height,
    cropX: rendered.cropX || 0,
    cropY: rendered.cropY || 0,
    backgroundWidth: rendered.backgroundWidth || 0,
    backgroundHeight: rendered.backgroundHeight || 0,
    backgroundDeferred: Boolean(rendered.backgroundDeferred),
    backgroundUrl: rendered.backgroundDeferred ? normalized.backgroundUrl : "",
    mime,
    format,
    addMode: normalized.addMode,
    additive: Boolean(addBase64),
    isAnimated: Boolean(rendered.isAnimated),
    blob: () => format === "png" && addBytes
      ? canvasBlob(canvas, mime)
      : Promise.resolve(new Blob([outputBytes], { type: mime })),
    dataUrl: () => Promise.resolve(format === "png" && addBytes ? canvas.toDataURL(mime) : `data:${mime};base64,${dataBase64}`),
    baseDataUrl: () => Promise.resolve(`data:${mime};base64,${dataBase64}`),
    addDataUrl: () => Promise.resolve(addBase64 ? `data:${mime};base64,${addBase64}` : ""),
    addBlob: () => Promise.resolve(addBytes ? new Blob([addBytes], { type: mime }) : undefined)
  };
}

async function paintImage(canvas, bytes, mime, format, width, height, addBytes) {
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, width, height);
  if (mime === "image/gif" || format === "apng") {
    await drawImageBytes(ctx, bytes, mime, width, height);
    if (addBytes) {
      ctx.globalCompositeOperation = "lighter";
      await drawImageBytes(ctx, addBytes, mime, width, height);
      ctx.globalCompositeOperation = "source-over";
    }
    return;
  }
  const bitmap = await createImageBitmap(new Blob([bytes], { type: mime }));
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(bitmap, 0, 0);
  bitmap.close();
  if (addBytes) {
    const addBitmap = await createImageBitmap(new Blob([addBytes], { type: mime }));
    ctx.globalCompositeOperation = "lighter";
    ctx.drawImage(addBitmap, 0, 0);
    ctx.globalCompositeOperation = "source-over";
    addBitmap.close();
  }
}

async function drawImageBytes(ctx, bytes, mime, width, height) {
  const image = new Image();
  image.src = `data:${mime};base64,${bytesToBase64(bytes)}`;
  await image.decode();
  ctx.drawImage(image, 0, 0, width, height);
}

function canvasBlob(canvas, mime) {
  return new Promise(resolve => {
    canvas.toBlob(blob => resolve(blob), mime);
  });
}

async function normalizeOptions(options) {
  let direction = numeric(options.direction, 0);
  if (options.rotation !== undefined && options.rotation !== null) {
    direction = numeric(options.rotation, direction);
  }
  const state = numeric(options.state, 0);
  const requestedFormat = String(options.format || "").toLowerCase();
  const apng = optionBoolean(options.apng) || requestedFormat === "apng";
  const gif = !apng && (optionBoolean(options.gif) || requestedFormat === "gif");
  const rawCanvas = options.canvas === undefined ? "transparent" : String(options.canvas).trim();
  const canvasBackgroundUrl = httpUrl(rawCanvas) ? rawCanvas : "";
  const addMode = normalizeAddMode(options);
  const normalized = {
    sprite: options.sprite || "",
    small: optionBoolean(options.small) || optionBoolean(options.s),
    state: state >= 101 ? 0 : state,
    direction,
    color: normalizeColor(options),
    canvas: canvasBackgroundUrl ? "transparent" : rawCanvas,
    crop: options.crop === undefined ? true : optionBoolean(options.crop),
    shadow: optionBoolean(options.shadow),
    icon: optionBoolean(options.icon),
    background: Boolean(canvasBackgroundUrl) || backgroundBoolean(options.bg) || optionBoolean(options.background),
    gif,
    apng,
    format: apng ? "apng" : (gif ? "gif" : "png"),
    addMode,
    separateAdd: addMode === "overlay",
    loop: options.loop === undefined ? true : optionBoolean(options.loop)
  };
  if (canvasBackgroundUrl || normalized.background) {
    const backgroundUrl = canvasBackgroundUrl || `${options.basePath || "."}/bg.png`;
    Object.assign(normalized, await loadBackground(backgroundUrl));
  }
  return normalized;
}

async function loadBackground(url) {
  const image = new Image();
  image.crossOrigin = "anonymous";
  image.src = url;
  try {
    await image.decode();
  } catch {
    throw new Error(`Background image failed to load: ${url}`);
  }
  const canvas = document.createElement("canvas");
  canvas.width = image.naturalWidth || image.width;
  canvas.height = image.naturalHeight || image.height;
  if (canvas.width <= 0 || canvas.height <= 0) {
    throw new Error(`Background image has no readable size: ${url}`);
  }
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(image, 0, 0);
  let data;
  try {
    data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
  } catch {
    throw new Error(`Background image must allow CORS pixel reads: ${url}`);
  }
  return {
    backgroundUrl: image.src,
    backgroundWidth: canvas.width,
    backgroundHeight: canvas.height,
    backgroundRgbaBase64: bytesToBase64(data)
  };
}

function normalizeColor(options) {
  let color = 0;
  const colorOption = numericOption(options.color);
  if (colorOption !== undefined) {
    color = colorOption;
  }
  const colourOption = numericOption(options.colour);
  if (colourOption !== undefined) {
    color = colourOption;
  }
  return color;
}

function numeric(value, fallback) {
  const parsed = numericOption(value);
  return parsed === undefined ? fallback : parsed;
}

function numericOption(value) {
  if (value === undefined || value === null || value === "") return undefined;
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? undefined : parsed;
}

function optionBoolean(value) {
  if (typeof value === "boolean") return value;
  if (typeof value === "string") return value.toLowerCase() === "true";
  return Boolean(value);
}

function backgroundBoolean(value) {
  if (typeof value === "string" && value.toLowerCase() === "false") return false;
  return optionBoolean(value);
}

function normalizeAddMode(options) {
  const rawMode = options.addMode === undefined ? options.glowMode : options.addMode;
  const mode = String(rawMode || "").toLowerCase();
  if (mode === "baked" || mode === "built-in" || mode === "builtin" || mode === "inbuilt") {
    return "baked";
  }
  if (mode === "overlay" || mode === "layered" || mode === "superimposed") {
    return "overlay";
  }
  if (options.separateAdd !== undefined) {
    return optionBoolean(options.separateAdd) ? "overlay" : "baked";
  }
  return "overlay";
}

function httpUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

function spriteFromUrl(url) {
  try {
    const path = new URL(url, globalThis.location?.href || "http://localhost").pathname;
    const file = path.substring(path.lastIndexOf("/") + 1);
    return file.replace(/\.[^.]+$/, "");
  } catch {
    const file = String(url).split("/").pop().split("?")[0];
    return file.replace(/\.[^.]+$/, "");
  }
}

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const existing = [...document.scripts].find(script => script.src === new URL(src, document.baseURI).href);
    if (existing) {
      resolve();
      return;
    }
    const script = document.createElement("script");
    script.src = src;
    script.async = true;
    script.onload = resolve;
    script.onerror = () => reject(new Error(`Failed to load ${src}`));
    document.head.appendChild(script);
  });
}

function bytesToBase64(bytes) {
  const array = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = "";
  for (let i = 0; i < array.length; i += 0x8000) {
    binary += String.fromCharCode(...array.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

function base64ToBytes(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}
