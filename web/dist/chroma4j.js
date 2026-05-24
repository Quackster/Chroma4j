let teavmInstancePromise;
const BUILD_VERSION = "wasm-gif-20260524";

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
  const dataBase64 = rendered.dataBase64 || rendered.pngBase64;
  const outputBytes = base64ToBytes(dataBase64);
  const canvas = target || document.createElement("canvas");
  await paintImage(canvas, outputBytes, mime, rendered.width, rendered.height);
  return {
    canvas,
    width: rendered.width,
    height: rendered.height,
    mime,
    isAnimated: Boolean(rendered.isAnimated),
    blob: () => Promise.resolve(new Blob([outputBytes], { type: mime })),
    dataUrl: () => Promise.resolve(`data:${mime};base64,${dataBase64}`)
  };
}

async function paintImage(canvas, bytes, mime, width, height) {
  canvas.width = width;
  canvas.height = height;
  if (mime === "image/gif") {
    const image = new Image();
    image.src = `data:${mime};base64,${bytesToBase64(bytes)}`;
    await image.decode();
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, width, height);
    ctx.drawImage(image, 0, 0);
    return;
  }
  const bitmap = await createImageBitmap(new Blob([bytes], { type: mime }));
  const ctx = canvas.getContext("2d");
  ctx.imageSmoothingEnabled = false;
  ctx.clearRect(0, 0, width, height);
  ctx.drawImage(bitmap, 0, 0);
  bitmap.close();
}

async function normalizeOptions(options) {
  let direction = numeric(options.direction, 0);
  if (options.rotation !== undefined && options.rotation !== null) {
    direction = numeric(options.rotation, direction);
  }
  const state = numeric(options.state, 0);
  const normalized = {
    sprite: options.sprite || "",
    small: optionBoolean(options.small) || optionBoolean(options.s),
    state: state >= 101 ? 0 : state,
    direction,
    color: normalizeColor(options),
    canvas: options.canvas === undefined ? "transparent" : options.canvas,
    crop: options.crop === undefined ? true : optionBoolean(options.crop),
    shadow: optionBoolean(options.shadow),
    icon: optionBoolean(options.icon),
    background: backgroundBoolean(options.bg) || optionBoolean(options.background),
    gif: optionBoolean(options.gif)
  };
  if (normalized.background) {
    Object.assign(normalized, await loadBackground(options.basePath || "."));
  }
  return normalized;
}

async function loadBackground(basePath) {
  const image = new Image();
  image.crossOrigin = "anonymous";
  image.src = `${basePath}/bg.png`;
  await image.decode();
  const canvas = document.createElement("canvas");
  canvas.width = image.naturalWidth || image.width;
  canvas.height = image.naturalHeight || image.height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(image, 0, 0);
  const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
  return {
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
