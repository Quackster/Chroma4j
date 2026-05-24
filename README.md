# Chroma4j

Chroma4j renders Habbo furni SWFs. The repository contains the original JVM renderer and a TeaVM/WebAssembly browser deployment that renders from SWF bytes entirely client-side.

## Requirements

- JDK 17 or newer.
- Gradle 8.7 or newer, or use the checked-in Gradle wrapper.
- Python 3 for serving `web/dist` locally.
- Node.js for browser JavaScript syntax checks run by the Gradle verification task.
- A modern browser with WebAssembly GC support.

The browser build fetches SWFs directly from the browser, so HTTP SWF URLs must allow CORS. There is no server-side fetch or rendering fallback in the WASM release.

## Preview

![](https://i.imgur.com/w0nzIvr.gif)

## Build The WASM Release

From the repository root:

```powershell
.\gradlew.bat :chroma-wasm:clean :chroma-wasm:buildWasmGC
```

This compiles the TeaVM module and writes:

- `web/dist/wasm-gc/chroma-wasm.wasm`
- `web/dist/wasm-gc/chroma-wasm.wasm-runtime.js`

The static deployment directory contains:

- `web/dist/index.html`
- `web/dist/main.js`
- `web/dist/chroma4j.js`
- `web/dist/styles.css`
- `web/dist/bg.png`

`web/dist` is generated/served output and is ignored by Git.

## Run The Static Demo

Serve `web/dist` with any static HTTP server:

```powershell
python -m http.server 5177 --directory web/dist
```

Open:

```text
http://localhost:5177
```

Paste a CORS-enabled furni SWF URL, choose render options, and click `Render`. The page fetches SWF bytes in the browser, passes them to the TeaVM parser, and exports PNG by default, animated GIF when `gif: true` is selected, or animated PNG when `apng: true` is selected.

## JavaScript API

```js
import { loadChroma4j } from "./chroma4j.js";

const chroma = await loadChroma4j();
const result = await chroma.renderFromUrl("https://example.com/hof_furni/chair.swf", {
  state: 0,
  direction: 2,
  color: 0,
  crop: true,
  canvas: "transparent"
});

document.body.append(result.canvas);
const png = await result.blob();
```

PNG is the default output. `result.format` is `png`, `result.mime` is `image/png`, `result.canvas` contains the rendered pixels, and `result.blob()` returns a browser `Blob` containing the PNG bytes.

To paint PNG output into an existing canvas:

```js
const canvas = document.querySelector("#preview");
const result = await chroma.renderFromUrl("https://example.com/hof_furni/chair.swf", {
  state: 0,
  direction: 2
}, canvas);

console.log(result.canvas === canvas); // true
```

To show the PNG in a normal `<img>`:

```js
const img = document.createElement("img");
img.src = await result.dataUrl();
document.body.append(img);
```

To render an animated GIF, pass `gif: true`:

```js
const result = await chroma.renderFromUrl("https://example.com/hof_furni/rare_dragonlamp.swf", {
  state: 1,
  direction: 4,
  color: 0,
  crop: true,
  canvas: "transparent",
  gif: true,
  loop: true
});

console.log(result.format);    // "gif"
console.log(result.mime);      // "image/gif"
console.log(result.isAnimated); // true when the selected state has multiple frames
```

To render animated PNG instead, pass `apng: true` or `format: "apng"`:

```js
const result = await chroma.renderFromUrl("https://example.com/hof_furni/rare_dragonlamp.swf", {
  state: 1,
  direction: 4,
  apng: true,
  loop: true
});

console.log(result.format); // "apng"
console.log(result.mime);   // "image/png"
```

Use an `<img>` for animated GIF or APNG playback:

```js
const img = document.createElement("img");
img.src = await result.dataUrl();
document.body.append(img);
```

Or use an object URL:

```js
const blob = await result.blob();
const img = document.createElement("img");
img.src = URL.createObjectURL(blob);
document.body.append(img);
```

`result.canvas` is still populated for animated results, but a canvas cannot play an animated GIF or APNG by itself. It is useful only as a first-frame/static preview. Use `result.blob()` or `result.dataUrl()` with an `<img>` when you want the animation.

You can also provide SWF bytes directly:

```js
const bytes = await file.arrayBuffer();
const result = await chroma.renderFromBytes(bytes, { sprite: "chair" });
```

Supported first-release options mirror the server endpoint where applicable:

- `small` / `s`
- `state`
- `direction` / `rotation`
- `color` / `colour`
- `crop`
- `bg` / `background`
- `shadow`
- `canvas`: a hex colour, `transparent`, or an HTTP/HTTPS image URL in the browser build. URL backgrounds must allow CORS pixel reads.
- `icon`
- `gif`: `false` by default for PNG output; `true` returns GIF bytes when the selected state has animation frames.
- `apng`: `false` by default for PNG output; `true` returns APNG bytes. APNG is served as PNG-compatible `image/png`.
- `format`: optional `"png"`, `"gif"`, or `"apng"` selector. `apng` wins if both animated formats are requested.
- `loop`: `true` by default for animated output; set `false` to emit a non-looping GIF or APNG.

## Build The Spring Webapp

The existing server-side renderer can still be built with:

```powershell
.\gradlew.bat :chroma-webapp:clean :chroma-webapp:bootJar
```

Run it with:

```powershell
java -jar chroma-webapp\build\libs\chroma-webapp-1.0.0.jar
```

The server listens on port `5000` and expects SWFs under `swfs/hof_furni`. Use `gif=true` for GIF output, `apng=true` or `format=apng` for APNG output, and `loop=false` for a non-looping animation.
