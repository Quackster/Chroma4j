let teavmInstancePromise;

export async function loadChroma4j(options = {}) {
  await ensureTeaVm(options.basePath || ".");
  return {
    renderFromUrl,
    renderFromBytes
  };
}

async function ensureTeaVm(basePath) {
  if (globalThis.__chroma4jTeaVmParseSwf) {
    return;
  }
  if (!teavmInstancePromise) {
    teavmInstancePromise = (async () => {
      await loadScript(`${basePath}/wasm-gc/chroma4j.wasm-runtime.js`);
      const teavm = await globalThis.TeaVM.wasmGC.load(`${basePath}/wasm-gc/chroma4j.wasm`);
      if (teavm.exports.main) {
        teavm.exports.main([]);
      }
      globalThis.__chroma4jTeaVmParseSwf = teavm.exports.parseSwfBase64;
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
  if (options.gif) {
    throw new Error("GIF export is not supported by the first TeaVM client release");
  }
  await ensureTeaVm(options.basePath || ".");
  const normalized = normalizeOptions(options);
  const packageJson = globalThis.__chroma4jTeaVmParseSwf(bytesToBase64(bytes), normalized.sprite || "");
  const furni = JSON.parse(packageJson);
  if (!furni.ok) {
    throw new Error(furni.error || "SWF parsing failed");
  }
  const canvas = target || document.createElement("canvas");
  await renderPackage(furni, normalized, canvas);
  return {
    canvas,
    width: canvas.width,
    height: canvas.height,
    blob: () => new Promise(resolve => canvas.toBlob(resolve, "image/png")),
    dataUrl: () => canvas.toDataURL("image/png")
  };
}

async function renderPackage(furni, options, canvas) {
  const assetsXml = parseXml(furni.xml.assets, "assets");
  const visualizationXml = parseXml(furni.xml.visualization, "visualization");
  const images = await decodeImages(furni.images);
  const aliases = buildImageAliases(furni.sprite, assetsXml, images);
  const renderAssets = collectWithDirectionFallback(furni.sprite, assetsXml, visualizationXml, aliases, options);
  if (!renderAssets.length) {
    throw new Error("No renderable furni assets found for the selected options");
  }

  const working = document.createElement("canvas");
  working.width = 1200;
  working.height = 1200;
  const ctx = working.getContext("2d", { willReadFrequently: true });
  fillCanvas(ctx, working, options.canvas);

  for (const asset of renderAssets) {
    drawAsset(ctx, working, asset);
  }

  const crop = options.crop ? cropBounds(ctx, working, options.canvas) : { x: 0, y: 0, width: working.width, height: working.height };
  canvas.width = crop.width;
  canvas.height = crop.height;
  canvas.getContext("2d").drawImage(working, crop.x, crop.y, crop.width, crop.height, 0, 0, crop.width, crop.height);
}

function collectRenderAssets(sprite, assetsXml, visualizationXml, images, options) {
  const size = options.small ? "32" : "64";
  const layers = readLayers(visualizationXml, size, options.direction);
  const colorLayers = readColorLayers(visualizationXml, size, options.color);
  const animations = readAnimationFrames(visualizationXml, size, options.state);
  const assets = [...assetsXml.getElementsByTagName("asset")].map(node => {
    const name = attr(node, "name");
    if (!name || name.includes(".props") || name.startsWith(`s_${sprite}`)) return null;
    if (options.icon ? !name.includes("_icon_") : name.includes("_icon_")) return null;

    const parsed = parseAssetName(sprite, name, options.icon);
    if (!parsed || parsed.size !== size) return null;
    const imageEntry = images.get(normalizeName(sprite, name)) || images.get(normalizeName(sprite, attr(node, "source") || ""));
    if (!imageEntry) return null;

    const layerInfo = layers.get(parsed.layer) || {};
    const frame = animations.get(parsed.layer) ?? 0;
    if (!options.icon && (parsed.direction !== options.direction || parsed.frame !== frame)) return null;

    return {
      name,
      image: imageEntry.image,
      flipH: attr(node, "flipH") === "1" || imageEntry.flipH,
      x: numberAttr(node, "x", 0) + 600,
      y: numberAttr(node, "y", 0) + 600,
      z: (layerInfo.z ?? parsed.layer) + parsed.layer,
      layer: parsed.layer,
      ink: layerInfo.ink,
      alpha: layerInfo.alpha,
      color: colorLayers.get(parsed.layer),
      shadow: name.includes("_sd_")
    };
  }).filter(Boolean);

  if (!options.shadow) {
    return assets.filter(asset => !asset.shadow).sort((a, b) => a.z - b.z);
  }
  return assets.map(asset => asset.shadow ? { ...asset, z: Number.MIN_SAFE_INTEGER } : asset).sort((a, b) => a.z - b.z);
}

function collectWithDirectionFallback(sprite, assetsXml, visualizationXml, images, options) {
  const preferred = collectRenderAssets(sprite, assetsXml, visualizationXml, images, options);
  if (preferred.length || options.icon) {
    return preferred;
  }
  for (const direction of [0, 2, 4, 6]) {
    if (direction === options.direction) continue;
    const fallback = collectRenderAssets(sprite, assetsXml, visualizationXml, images, { ...options, direction });
    if (fallback.length) {
      options.direction = direction;
      return fallback;
    }
  }
  return preferred;
}

function readLayers(doc, size, direction) {
  const result = new Map();
  const visualization = [...doc.getElementsByTagName("visualization")].find(node => attr(node, "size") === size);
  if (!visualization) return result;
  const directionNode = [...visualization.getElementsByTagName("direction")].find(node => attr(node, "id") === String(direction));
  const root = directionNode && directionNode.getElementsByTagName("layer").length > 0 ? directionNode : visualization;
  for (const layer of root.getElementsByTagName("layer")) {
    const id = numberAttr(layer, "id", -1);
    if (id >= 0) {
      result.set(id, {
        ink: attr(layer, "ink"),
        z: layer.hasAttribute("z") ? numberAttr(layer, "z", id) : undefined,
        alpha: layer.hasAttribute("alpha") ? numberAttr(layer, "alpha", 255) : undefined
      });
    }
  }
  return result;
}

function readColorLayers(doc, size, colorId) {
  const result = new Map();
  const visualization = [...doc.getElementsByTagName("visualization")].find(node => attr(node, "size") === size);
  if (!visualization) return result;
  const color = [...visualization.getElementsByTagName("color")].find(node => attr(node, "id") === String(colorId));
  if (!color) return result;
  for (const layer of color.getElementsByTagName("colorLayer")) {
    result.set(numberAttr(layer, "id", -1), attr(layer, "color"));
  }
  return result;
}

function readAnimationFrames(doc, size, state) {
  const result = new Map();
  const visualization = [...doc.getElementsByTagName("visualization")].find(node => attr(node, "size") === size);
  if (!visualization) return result;
  const animation = [...visualization.getElementsByTagName("animation")].find(node => attr(node, "id") === String(state));
  if (!animation) return result;
  for (const layer of animation.getElementsByTagName("animationLayer")) {
    const id = numberAttr(layer, "id", -1);
    const frame = layer.getElementsByTagName("frame")[0];
    if (id >= 0 && frame) {
      result.set(id, numberAttr(frame, "id", 0));
    }
  }
  return result;
}

async function decodeImages(items) {
  const result = new Map();
  for (const item of items) {
    let image;
    if (item.kind === "rgba") {
      image = rgbaToCanvas(item);
    } else {
      image = await encodedToImage(item);
    }
    result.set(item.name, { image, flipH: false });
  }
  return result;
}

function buildImageAliases(sprite, assetsXml, images) {
  const aliases = new Map(images);
  for (const [name, image] of images.entries()) {
    aliases.set(normalizeName(sprite, name), image);
  }
  for (const asset of assetsXml.getElementsByTagName("asset")) {
    const name = normalizeName(sprite, attr(asset, "name"));
    const source = normalizeName(sprite, attr(asset, "source"));
    if (source && images.has(source) && !aliases.has(name)) {
      aliases.set(name, { ...images.get(source), flipH: attr(asset, "flipH") === "1" });
    }
  }
  return aliases;
}

function drawAsset(ctx, canvas, asset) {
  const width = asset.image.width;
  const height = asset.image.height;
  const x = canvas.width - asset.x;
  const y = canvas.height - asset.y;

  const source = document.createElement("canvas");
  source.width = width;
  source.height = height;
  const sourceCtx = source.getContext("2d", { willReadFrequently: true });
  sourceCtx.save();
  if (asset.flipH) {
    sourceCtx.translate(width, 0);
    sourceCtx.scale(-1, 1);
  }
  sourceCtx.drawImage(asset.image, 0, 0);
  sourceCtx.restore();

  let imageData = sourceCtx.getImageData(0, 0, width, height);
  if (asset.alpha !== undefined) applyAlpha(imageData, asset.alpha);
  if (asset.color) applyTint(imageData, asset.color, 255);
  if (asset.shadow) applyOpacity(imageData, 0.2);
  sourceCtx.putImageData(imageData, 0, 0);

  if (asset.ink === "ADD" || asset.ink === "33") {
    drawAdd(ctx, source, x, y);
  } else {
    ctx.drawImage(source, x, y);
  }
}

function drawAdd(ctx, source, x, y) {
  const startX = Math.max(0, x);
  const startY = Math.max(0, y);
  const endX = Math.min(ctx.canvas.width, x + source.width);
  const endY = Math.min(ctx.canvas.height, y + source.height);
  const width = endX - startX;
  const height = endY - startY;
  if (width <= 0 || height <= 0) return;

  const fg = source.getContext("2d").getImageData(startX - x, startY - y, width, height);
  const bg = ctx.getImageData(startX, startY, width, height);
  for (let i = 0; i < fg.data.length; i += 4) {
    if (fg.data[i + 3] === 0) continue;
    bg.data[i] = Math.min(255, bg.data[i] + fg.data[i]);
    bg.data[i + 1] = Math.min(255, bg.data[i + 1] + fg.data[i + 1]);
    bg.data[i + 2] = Math.min(255, bg.data[i + 2] + fg.data[i + 2]);
    bg.data[i + 3] = Math.max(bg.data[i + 3], fg.data[i + 3]);
  }
  ctx.putImageData(bg, startX, startY);
}

function applyTint(imageData, color, alpha) {
  const rgb = parseHex(color);
  for (let i = 0; i < imageData.data.length; i += 4) {
    if (imageData.data[i + 3] === 0) continue;
    imageData.data[i] = Math.floor((rgb.r * imageData.data[i]) / 255);
    imageData.data[i + 1] = Math.floor((rgb.g * imageData.data[i + 1]) / 255);
    imageData.data[i + 2] = Math.floor((rgb.b * imageData.data[i + 2]) / 255);
    imageData.data[i + 3] = alpha;
  }
}

function applyAlpha(imageData, alpha) {
  for (let i = 3; i < imageData.data.length; i += 4) {
    if (imageData.data[i] > 0) imageData.data[i] = alpha;
  }
}

function applyOpacity(imageData, opacity) {
  for (let i = 3; i < imageData.data.length; i += 4) {
    imageData.data[i] = Math.round(imageData.data[i] * opacity);
  }
}

function cropBounds(ctx, canvas, canvasColor) {
  const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
  const trim = parseCanvasColor(canvasColor);
  let top = 0;
  for (let y = 0; y < canvas.height; y++) {
    if (!allMatchingRow(data, canvas.width, y, trim)) break;
    top = y;
  }

  let bottom = 0;
  for (let y = canvas.height - 1; y >= 0; y--) {
    if (!allMatchingRow(data, canvas.width, y, trim)) break;
    bottom = y;
  }

  let left = 0;
  for (let x = 0; x < canvas.width; x++) {
    if (!allMatchingColumn(data, canvas.width, canvas.height, x, trim)) break;
    left = x;
  }

  let right = 0;
  for (let x = canvas.width - 1; x >= 0; x--) {
    if (!allMatchingColumn(data, canvas.width, canvas.height, x, trim)) break;
    right = x;
  }

  if (right === 0) right = canvas.width;
  if (bottom === 0) bottom = canvas.height;

  let width = right - left;
  let height = bottom - top;
  if (width === 0) {
    left = 0;
    width = canvas.width;
  }
  if (height === 0) {
    top = 0;
    height = canvas.height;
  }
  return { x: left, y: top, width, height };
}

function allMatchingRow(data, width, y, color) {
  for (let x = 0; x < width; x++) {
    if (!samePixel(data, (y * width + x) * 4, color)) {
      return false;
    }
  }
  return true;
}

function allMatchingColumn(data, width, height, x, color) {
  for (let y = 0; y < height; y++) {
    if (!samePixel(data, (y * width + x) * 4, color)) {
      return false;
    }
  }
  return true;
}

function tightCropBounds(ctx, canvas, canvasColor) {
  const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
  const trim = parseCanvasColor(canvasColor);
  let left = canvas.width;
  let top = canvas.height;
  let right = 0;
  let bottom = 0;
  for (let y = 0; y < canvas.height; y++) {
    for (let x = 0; x < canvas.width; x++) {
      const i = (y * canvas.width + x) * 4;
      if (!samePixel(data, i, trim)) {
        left = Math.min(left, x);
        top = Math.min(top, y);
        right = Math.max(right, x + 1);
        bottom = Math.max(bottom, y + 1);
      }
    }
  }
  if (right <= left || bottom <= top) {
    return { x: 0, y: 0, width: canvas.width, height: canvas.height };
  }
  return { x: left, y: top, width: right - left, height: bottom - top };
}

function fillCanvas(ctx, canvas, color) {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if ((color || "transparent").toLowerCase() !== "transparent") {
    ctx.fillStyle = color.startsWith("#") ? color : `#${color}`;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
  }
}

function parseCanvasColor(value) {
  if (!value || value.toLowerCase() === "transparent") return { r: 0, g: 0, b: 0, a: 0 };
  return { ...parseHex(value), a: 255 };
}

function samePixel(data, i, color) {
  return data[i] === color.r && data[i + 1] === color.g && data[i + 2] === color.b && data[i + 3] === color.a;
}

function parseAssetName(sprite, name, icon) {
  const parts = normalizeName(sprite, name).split("_");
  if (icon) {
    return { size: parts[0], layer: 0, direction: 0, frame: 0 };
  }
  if (parts.length < 4) return null;
  return {
    size: parts[0],
    layer: parts[1].toUpperCase().charCodeAt(0) - 65,
    direction: Number.parseInt(parts[2], 10),
    frame: Number.parseInt(parts[3], 10)
  };
}

function normalizeOptions(options) {
  const direction = numeric(options.direction ?? options.rotation, 0);
  return {
    sprite: options.sprite || "",
    small: Boolean(options.small || options.s),
    state: Math.min(numeric(options.state, 0), 100),
    direction,
    color: Math.min(Math.max(numeric(options.color ?? options.colour, 0), numeric(options.colour ?? options.color, 0)), 15),
    canvas: options.canvas || "transparent",
    crop: options.crop !== false,
    shadow: Boolean(options.shadow),
    icon: Boolean(options.icon),
    basePath: options.basePath || "."
  };
}

function numeric(value, fallback) {
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
}

function attr(node, name) {
  return node?.getAttribute(name) || "";
}

function numberAttr(node, name, fallback) {
  return numeric(attr(node, name), fallback);
}

function normalizeName(sprite, value) {
  if (!value) return "";
  const prefix = `${sprite}_`;
  return value.startsWith(prefix) ? value.slice(prefix.length) : value;
}

function parseXml(text, name) {
  if (!text) throw new Error(`Missing ${name} XML in SWF`);
  const doc = new DOMParser().parseFromString(text, "application/xml");
  const error = doc.getElementsByTagName("parsererror")[0];
  if (error) throw new Error(`Invalid ${name} XML`);
  return doc;
}

function parseHex(value) {
  const hex = value.replace("#", "");
  return {
    r: Number.parseInt(hex.slice(0, 2), 16) || 0,
    g: Number.parseInt(hex.slice(2, 4), 16) || 0,
    b: Number.parseInt(hex.slice(4, 6), 16) || 0
  };
}

function rgbaToCanvas(item) {
  const canvas = document.createElement("canvas");
  canvas.width = item.width;
  canvas.height = item.height;
  const bytes = base64ToBytes(item.data);
  canvas.getContext("2d").putImageData(new ImageData(new Uint8ClampedArray(bytes), item.width, item.height), 0, 0);
  return canvas;
}

async function encodedToImage(item) {
  const blob = new Blob([base64ToBytes(item.data)], { type: item.mime });
  if (globalThis.createImageBitmap) {
    return createImageBitmap(blob);
  }
  const url = URL.createObjectURL(blob);
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    return image;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function bytesToBase64(bytes) {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = "";
  for (let i = 0; i < view.length; i += 0x8000) {
    binary += String.fromCharCode(...view.subarray(i, i + 0x8000));
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

function spriteFromUrl(url) {
  const clean = new URL(url, location.href).pathname.split("/").pop() || "";
  return clean.replace(/\.swf$/i, "");
}

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const existing = document.querySelector(`script[src="${src}"]`);
    if (existing) {
      existing.addEventListener("load", resolve, { once: true });
      resolve();
      return;
    }
    const script = document.createElement("script");
    script.src = src;
    script.onload = resolve;
    script.onerror = () => reject(new Error(`Failed to load ${src}`));
    document.head.appendChild(script);
  });
}
