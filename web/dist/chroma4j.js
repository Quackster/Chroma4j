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
  const pngBytes = await renderPackage(furni, normalized, canvas);
  return {
    canvas,
    width: canvas.width,
    height: canvas.height,
    blob: () => Promise.resolve(new Blob([pngBytes], { type: "image/png" })),
    dataUrl: () => `data:image/png;base64,${bytesToBase64(pngBytes)}`
  };
}

async function renderPackage(furni, options, canvas) {
  const assetsXml = parseXml(furni.xml.assets, "assets");
  const visualizationXml = parseXml(furni.xml.visualization, "visualization");
  const images = await decodeImages(furni.images);
  const aliases = buildImageAliases(furni.sprite, assetsXml, images);
  const background = options.background ? await loadImage(`${options.basePath}/bg.png`) : null;
  options.renderWidth = background?.width || 1200;
  options.renderHeight = background?.height || 1200;
  const renderAssets = collectWithDirectionFallback(furni.sprite, assetsXml, visualizationXml, aliases, options);
  if (!renderAssets.length) {
    throw new Error("No renderable furni assets found for the selected options");
  }

  const working = document.createElement("canvas");
  working.width = options.renderWidth;
  working.height = options.renderHeight;
  const ctx = working.getContext("2d", { willReadFrequently: true });
  ctx.imageSmoothingEnabled = false;
  fillCanvas(ctx, working, options.canvas, background);

  for (const asset of renderAssets) {
    drawAsset(ctx, working, asset);
  }

  const crop = options.crop ? cropBounds(ctx, working, trimColors(options)) : { x: 0, y: 0, width: working.width, height: working.height };
  canvas.width = crop.width;
  canvas.height = crop.height;
  const outputCtx = canvas.getContext("2d");
  outputCtx.imageSmoothingEnabled = false;
  let output = cropSystemDrawingBitmap(ctx, crop);
  if (options.mirrorFallbackH) {
    output = flipImageDataHorizontal(output);
  }
  outputCtx.putImageData(output, 0, 0);
  return encodePng(output);
}

function collectRenderAssets(sprite, assetsXml, visualizationXml, images, options) {
  const size = options.small ? "32" : "64";
  const layers = readLayers(visualizationXml, size, options.direction);
  const colorLayers = readColorLayers(visualizationXml, size, options.color);
  const animations = readAnimationFrames(visualizationXml, size, options.state);
  const candidates = [...assetsXml.getElementsByTagName("asset")].map(node => {
    const name = attr(node, "name");
    if (!name || name.includes(".props") || name.startsWith(`s_${sprite}`)) return null;
    if (options.icon ? !name.includes("_icon_") : name.includes("_icon_")) return null;

    const parsed = parseAssetName(sprite, name, options.icon);
    if (!parsed || (!options.icon && parsed.size !== size)) return null;
    const imageEntry = images.get(normalizeName(sprite, name)) || images.get(normalizeName(sprite, attr(node, "source") || ""));
    if (!imageEntry) return null;

    const layerInfo = layers.get(parsed.layer) || {};
    return {
      name,
      image: imageEntry.image,
      flipH: attr(node, "flipH") === "1" || imageEntry.flipH,
      x: ((attr(node, "flipH") === "1" || imageEntry.flipH) ? imageEntry.image.width - numberAttr(node, "x", 0) : numberAttr(node, "x", 0)) + (options.renderWidth / 2),
      y: numberAttr(node, "y", 0) + (options.renderHeight / 2),
      z: (layerInfo.z ?? parsed.layer) + parsed.layer,
      layer: parsed.layer,
      direction: parsed.direction,
      frame: parsed.frame,
      ink: layerInfo.ink,
      alpha: layerInfo.alpha,
      color: colorLayers.get(parsed.layer),
      shadow: name.includes("_sd_")
    };
  }).filter(Boolean);

  const highestLayer = candidates
    .filter(asset => !asset.shadow)
    .reduce((highest, asset) => Math.max(highest, asset.layer), -1) + 1;
  const assets = [];
  for (let layer = 0; layer < highestLayer; layer++) {
    const frame = animations.get(layer) ?? 0;
    assets.push(...candidates.filter(asset =>
      asset.layer === layer &&
      (options.icon || (asset.direction === options.direction && asset.frame === frame))));
  }

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
  if (options.direction === 0 && (sprite || "").toLowerCase() === "rare_dragonlamp") {
    const mirrored = collectRenderAssets(sprite, assetsXml, visualizationXml, images, { ...options, direction: 4 });
    if (mirrored.length) {
      options.direction = 4;
      options.mirrorFallbackH = true;
      return mirrored;
    }
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
  const baseLayers = visualization.getElementsByTagName("layers")[0]?.getElementsByTagName("layer") || [];
  const directionNode = [...visualization.getElementsByTagName("direction")].find(node => attr(node, "id") === String(direction));
  const directionLayers = directionNode?.getElementsByTagName("layers")[0]?.getElementsByTagName("layer") || [];
  const layerNodes = baseLayers.length > 0 ? baseLayers : directionLayers;
  for (const layer of layerNodes) {
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
    if (source && aliases.has(source) && !aliases.has(name)) {
      const sourceEntry = aliases.get(source);
      aliases.set(name, { ...sourceEntry, flipH: sourceEntry.flipH || attr(asset, "flipH") === "1" });
    }
  }
  return aliases;
}

function drawAsset(ctx, canvas, asset) {
  const width = asset.image.width;
  const height = asset.image.height;
  const x = canvas.width - asset.x;
  const y = canvas.height - asset.y;

  let imageData;
  if (asset.image.data) {
    imageData = new ImageData(new Uint8ClampedArray(asset.image.data), width, height);
    if (asset.flipH) {
      imageData = flipImageDataHorizontal(imageData);
    }
  } else {
    const source = document.createElement("canvas");
    source.width = width;
    source.height = height;
    const sourceCtx = source.getContext("2d", { willReadFrequently: true });
    sourceCtx.imageSmoothingEnabled = false;
    sourceCtx.save();
    if (asset.flipH) {
      sourceCtx.translate(width, 0);
      sourceCtx.scale(-1, 1);
    }
    sourceCtx.drawImage(asset.image, 0, 0);
    sourceCtx.restore();
    imageData = sourceCtx.getImageData(0, 0, width, height);
  }

  if (asset.alpha !== undefined) applyAlpha(imageData, asset.alpha);
  if (asset.color) applyTint(imageData, asset.color, 255);
  if (asset.shadow) applyOpacity(imageData, 0.2);

  if (asset.ink === "ADD" || asset.ink === "33") {
    drawAdd(ctx, imageData, x, y);
  } else {
    drawNormal(ctx, imageData, x, y);
  }
}

function drawNormal(ctx, source, x, y) {
  const startX = Math.max(0, x);
  const startY = Math.max(0, y);
  const endX = Math.min(ctx.canvas.width, x + source.width);
  const endY = Math.min(ctx.canvas.height, y + source.height);
  const width = endX - startX;
  const height = endY - startY;
  if (width <= 0 || height <= 0) return;

  const bg = ctx.getImageData(startX, startY, width, height);
  for (let row = 0; row < height; row++) {
    for (let col = 0; col < width; col++) {
      const sourceIndex = ((startY - y + row) * source.width + (startX - x + col)) * 4;
      const targetIndex = (row * width + col) * 4;
      const fgAlpha = source.data[sourceIndex + 3];
      if (fgAlpha === 0) continue;

      const bgAlpha = bg.data[targetIndex + 3];
      const alpha = blendNormalAlpha(fgAlpha, bgAlpha);
      bg.data[targetIndex] = blendNormalChannel(source.data[sourceIndex], fgAlpha, bg.data[targetIndex], bgAlpha, alpha);
      bg.data[targetIndex + 1] = blendNormalChannel(source.data[sourceIndex + 1], fgAlpha, bg.data[targetIndex + 1], bgAlpha, alpha);
      bg.data[targetIndex + 2] = blendNormalChannel(source.data[sourceIndex + 2], fgAlpha, bg.data[targetIndex + 2], bgAlpha, alpha);
      bg.data[targetIndex + 3] = alpha;
    }
  }
  ctx.putImageData(bg, startX, startY);
}

function blendNormalAlpha(fgAlpha, bgAlpha) {
  const sourceAlpha = fgAlpha / 255;
  const backgroundAlpha = bgAlpha / 255;
  return clampChannel(Math.round((sourceAlpha + backgroundAlpha * (1 - sourceAlpha)) * 255));
}

function blendNormalChannel(fg, fgAlpha, bg, bgAlpha, alpha) {
  if (alpha === 0) return 0;
  const sourceAlpha = fgAlpha / 255;
  const backgroundAlpha = bgAlpha / 255;
  const outAlpha = alpha / 255;
  return clampChannel(Math.round((fg * sourceAlpha + bg * backgroundAlpha * (1 - sourceAlpha)) / outAlpha));
}

function clampChannel(value) {
  return Math.max(0, Math.min(255, value));
}

function cropSystemDrawingBitmap(ctx, crop) {
  const output = ctx.getImageData(crop.x, crop.y, crop.width, crop.height);
  for (let i = 0; i < output.data.length; i += 4) {
    const alpha = output.data[i + 3];
    if (alpha === 0 || alpha === 255) continue;

    output.data[i] = quantizeSystemDrawingChannel(output.data[i], alpha);
    output.data[i + 1] = quantizeSystemDrawingChannel(output.data[i + 1], alpha);
    output.data[i + 2] = quantizeSystemDrawingChannel(output.data[i + 2], alpha);
  }
  return output;
}

function quantizeSystemDrawingChannel(channel, alpha) {
  return Math.trunc(Math.round(channel * alpha / 255) * 255 / alpha);
}

function flipImageDataHorizontal(imageData) {
  const flipped = new ImageData(imageData.width, imageData.height);
  for (let y = 0; y < imageData.height; y++) {
    for (let x = 0; x < imageData.width; x++) {
      const source = (y * imageData.width + x) * 4;
      const target = (y * imageData.width + (imageData.width - 1 - x)) * 4;
      flipped.data[target] = imageData.data[source];
      flipped.data[target + 1] = imageData.data[source + 1];
      flipped.data[target + 2] = imageData.data[source + 2];
      flipped.data[target + 3] = imageData.data[source + 3];
    }
  }
  return flipped;
}

function encodePng(imageData) {
  const scanlineLength = imageData.width * 4 + 1;
  const raw = new Uint8Array(scanlineLength * imageData.height);
  for (let y = 0; y < imageData.height; y++) {
    const rawOffset = y * scanlineLength;
    const dataOffset = y * imageData.width * 4;
    raw[rawOffset] = 0;
    raw.set(imageData.data.subarray(dataOffset, dataOffset + imageData.width * 4), rawOffset + 1);
  }

  const compressed = zlibStore(raw);
  const signature = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
  return concatBytes([
    signature,
    pngChunk("IHDR", pngHeader(imageData.width, imageData.height)),
    pngChunk("IDAT", compressed),
    pngChunk("IEND", new Uint8Array(0))
  ]);
}

function pngHeader(width, height) {
  const header = new Uint8Array(13);
  writeUint32(header, 0, width);
  writeUint32(header, 4, height);
  header[8] = 8;
  header[9] = 6;
  header[10] = 0;
  header[11] = 0;
  header[12] = 0;
  return header;
}

function pngChunk(type, data) {
  const typeBytes = new TextEncoder().encode(type);
  const chunk = new Uint8Array(12 + data.length);
  writeUint32(chunk, 0, data.length);
  chunk.set(typeBytes, 4);
  chunk.set(data, 8);
  writeUint32(chunk, 8 + data.length, crc32(concatBytes([typeBytes, data])));
  return chunk;
}

function zlibStore(data) {
  const blockCount = Math.ceil(data.length / 65535) || 1;
  const output = new Uint8Array(2 + data.length + blockCount * 5 + 4);
  let offset = 0;
  output[offset++] = 0x78;
  output[offset++] = 0x01;

  for (let source = 0; source < data.length || source === 0; source += 65535) {
    const length = Math.min(65535, data.length - source);
    const finalBlock = source + length >= data.length;
    output[offset++] = finalBlock ? 1 : 0;
    output[offset++] = length & 0xff;
    output[offset++] = (length >>> 8) & 0xff;
    const inverse = (~length) & 0xffff;
    output[offset++] = inverse & 0xff;
    output[offset++] = (inverse >>> 8) & 0xff;
    output.set(data.subarray(source, source + length), offset);
    offset += length;
    if (data.length === 0) break;
  }

  writeUint32(output, offset, adler32(data));
  return output;
}

function writeUint32(bytes, offset, value) {
  bytes[offset] = (value >>> 24) & 0xff;
  bytes[offset + 1] = (value >>> 16) & 0xff;
  bytes[offset + 2] = (value >>> 8) & 0xff;
  bytes[offset + 3] = value & 0xff;
}

function concatBytes(parts) {
  const length = parts.reduce((sum, part) => sum + part.length, 0);
  const result = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

let crcTable;

function crc32(bytes) {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) {
        c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
      }
      crcTable[i] = c >>> 0;
    }
  }

  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function adler32(bytes) {
  let a = 1;
  let b = 0;
  for (const byte of bytes) {
    a = (a + byte) % 65521;
    b = (b + a) % 65521;
  }
  return ((b << 16) | a) >>> 0;
}

function drawAdd(ctx, source, x, y) {
  const startX = Math.max(0, x);
  const startY = Math.max(0, y);
  const endX = Math.min(ctx.canvas.width, x + source.width);
  const endY = Math.min(ctx.canvas.height, y + source.height);
  const width = endX - startX;
  const height = endY - startY;
  if (width <= 0 || height <= 0) return;

  const bg = ctx.getImageData(startX, startY, width, height);
  for (let row = 0; row < height; row++) {
    for (let col = 0; col < width; col++) {
      const sourceIndex = ((startY - y + row) * source.width + (startX - x + col)) * 4;
      const targetIndex = (row * width + col) * 4;
      if (source.data[sourceIndex + 3] === 0) continue;
      const r = Math.min(255, bg.data[targetIndex] + source.data[sourceIndex]);
      const g = Math.min(255, bg.data[targetIndex + 1] + source.data[sourceIndex + 1]);
      const b = Math.min(255, bg.data[targetIndex + 2] + source.data[sourceIndex + 2]);
      bg.data[targetIndex] = r;
      bg.data[targetIndex + 1] = g;
      bg.data[targetIndex + 2] = b;
      bg.data[targetIndex + 3] = Math.min(255, Math.max(bg.data[targetIndex + 3], source.data[sourceIndex + 3]));
    }
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

function cropBounds(ctx, canvas, colors) {
  const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
  let top = 0;
  for (let y = 0; y < canvas.height; y++) {
    if (!allMatchingRow(data, canvas.width, y, colors)) break;
    top = y;
  }

  let bottom = 0;
  for (let y = canvas.height - 1; y >= 0; y--) {
    if (!allMatchingRow(data, canvas.width, y, colors)) break;
    bottom = y;
  }

  let left = 0;
  for (let x = 0; x < canvas.width; x++) {
    if (!allMatchingColumn(data, canvas.width, canvas.height, x, colors)) break;
    left = x;
  }

  let right = 0;
  for (let x = canvas.width - 1; x >= 0; x--) {
    if (!allMatchingColumn(data, canvas.width, canvas.height, x, colors)) break;
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

function allMatchingRow(data, width, y, colors) {
  for (let x = 0; x < width; x++) {
    if (!sameAnyPixel(data, (y * width + x) * 4, colors)) {
      return false;
    }
  }
  return true;
}

function allMatchingColumn(data, width, height, x, colors) {
  for (let y = 0; y < height; y++) {
    if (!sameAnyPixel(data, (y * width + x) * 4, colors)) {
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

function fillCanvas(ctx, canvas, color, background) {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if (background) {
    ctx.drawImage(background, 0, 0);
  } else if ((color || "transparent").toLowerCase() !== "transparent") {
    ctx.fillStyle = color.startsWith("#") ? color : `#${color}`;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
  }
}

function trimColors(options) {
  if (options.background) {
    return [
      { r: 142, g: 142, b: 94, a: 255 },
      { r: 152, g: 152, b: 101, a: 255 }
    ];
  }
  return [parseCanvasColor(options.canvas)];
}

function parseCanvasColor(value) {
  if (!value || value.toLowerCase() === "transparent") return { r: 0, g: 0, b: 0, a: 0 };
  return { ...parseHex(value), a: 255 };
}

function samePixel(data, i, color) {
  return data[i] === color.r && data[i + 1] === color.g && data[i + 2] === color.b && data[i + 3] === color.a;
}

function sameAnyPixel(data, i, colors) {
  return colors.some(color => samePixel(data, i, color));
}

function parseAssetName(sprite, name, icon) {
  const parts = normalizeName(sprite, name).split("_");
  if (icon) {
    if (parts.length < 2) return null;
    return {
      size: parts[0],
      layer: parts[1].toUpperCase().charCodeAt(0) - 65,
      direction: 0,
      frame: 0
    };
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
  const direction = numeric(options.rotation ?? options.direction, 0);
  const state = numeric(options.state, 0);
  return {
    sprite: options.sprite || "",
    small: optionBoolean(options.small) || optionBoolean(options.s),
    state: state >= 101 ? 0 : state,
    direction,
    color: normalizeColor(options),
    canvas: options.canvas || "transparent",
    crop: options.crop === undefined ? true : optionBoolean(options.crop),
    shadow: optionBoolean(options.shadow),
    icon: optionBoolean(options.icon),
    background: backgroundBoolean(options.bg) || optionBoolean(options.background),
    basePath: options.basePath || "."
  };
}

function normalizeColor(options) {
  let color = 0;
  const colorOption = numericOption(options.color);
  if (colorOption !== undefined) {
    color = colorOption >= 16 ? 0 : colorOption;
  }
  const colourOption = numericOption(options.colour);
  if (colourOption !== undefined) {
    color = colourOption >= 16 ? 0 : colourOption;
  }
  return color;
}

function numeric(value, fallback) {
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
}

function numericOption(value) {
  if (value === undefined || value === null || value === "") return undefined;
  const text = String(value);
  if (!/^-?\d+$/.test(text)) return undefined;
  const n = Number.parseInt(text, 10);
  return Number.isSafeInteger(n) ? n : undefined;
}

function optionBoolean(value) {
  return value === true || value === 1 || value === "1" || value === "true";
}

function backgroundBoolean(value) {
  if (value === undefined || value === null) return false;
  return value !== false && value !== 0 && value !== "0" && value !== "false";
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
  return {
    width: item.width,
    height: item.height,
    data: base64ToBytes(item.data)
  };
}

async function encodedToImage(item) {
  const blob = new Blob([base64ToBytes(item.data)], { type: item.mime });
  if (globalThis.createImageBitmap) {
    return createImageBitmap(blob);
  }
  const url = URL.createObjectURL(blob);
  try {
    return await loadImage(url);
  } finally {
    URL.revokeObjectURL(url);
  }
}

async function loadImage(url) {
  const image = new Image();
  image.src = url;
  await image.decode();
  return image;
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
