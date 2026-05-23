# Chroma4j

Chroma4j renders Habbo furni SWFs. The repository contains the original JVM renderer and a TeaVM/WebAssembly browser deployment that renders from SWF bytes entirely client-side.

## Modules

- `chroma-lib`: core JVM renderer used by the Spring webapp. It uses JPEXS, AWT/ImageIO, and filesystem extraction.
- `chroma-webapp`: Spring Boot server endpoint that renders local `swfs/hof_furni/*.swf` files.
- `chroma-wasm`: TeaVM WebAssembly GC build for browser-only SWF parsing.
- `web/dist`: static browser deployment and demo page.

## Requirements

- JDK 17 or newer.
- Gradle 8.7 or newer, or use the checked-in Gradle wrapper.
- Python 3 for serving `web/dist` locally.
- A modern browser with WebAssembly GC support.

The browser build fetches SWFs directly from the browser, so HTTP SWF URLs must allow CORS. There is no server-side fetch or rendering fallback in the WASM release.

## Build The WASM Release

From the repository root:

```powershell
.\gradlew.bat :chroma-wasm:clean :chroma-wasm:buildWasmGC
```

This compiles the TeaVM module and writes:

- `web/dist/wasm-gc/chroma4j.wasm`
- `web/dist/wasm-gc/chroma4j.wasm-runtime.js`

The checked-in static deployment files are:

- `web/dist/index.html`
- `web/dist/main.js`
- `web/dist/chroma4j.js`
- `web/dist/styles.css`
- `web/dist/bg.png`

## Run The Static Demo

Serve `web/dist` with any static HTTP server:

```powershell
python -m http.server 5177 --directory web/dist
```

Open:

```text
http://localhost:5177
```

Paste a CORS-enabled furni SWF URL, choose render options, and click `Render`. The page fetches SWF bytes in the browser, passes them to the TeaVM parser, renders to Canvas, and exports PNG.

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

`result.blob()` returns a browser `Blob` containing the rendered PNG bytes. Use it when you want to upload the PNG, download it, or create an object URL.

To show the render in an `<img>` using a base64 data URL:

```js
const img = document.createElement("img");
img.src = result.dataUrl();
document.body.append(img);
```

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
- `canvas`
- `icon`
- `gif` is accepted for endpoint compatibility and ignored; the browser release renders PNG output.

## Smoke Tests

Check the browser JavaScript syntax:

```powershell
node --check web\dist\chroma4j.js
node --check web\dist\main.js
```

Check that the TeaVM runtime can load the generated WASM and expose the parser:

```powershell
node -e "const fs=require('fs'); const runtime=fs.readFileSync('web/dist/wasm-gc/chroma4j.wasm-runtime.js','utf8'); (0,eval)(runtime); (async()=>{ const teavm=await globalThis.TeaVM.wasmGC.load('web/dist/wasm-gc/chroma4j.wasm'); const result=JSON.parse(teavm.exports.parseSwfBase64(Buffer.from('not-a-swf').toString('base64'), 'bad')); console.log(result.ok === false); })().catch(e=>{ console.error(e); process.exit(1); });"
```

Expected output:

```text
true
```

Check static serving:

```powershell
python -m http.server 5177 --directory web/dist
Invoke-WebRequest -Uri http://localhost:5177 -UseBasicParsing | Select-Object -ExpandProperty StatusCode
Invoke-WebRequest -Uri http://localhost:5177/wasm-gc/chroma4j.wasm -UseBasicParsing | Select-Object -ExpandProperty StatusCode
```

Expected output for each request:

```text
200
```

For visual validation, use a known CORS-enabled furni SWF URL in the demo and confirm the preview canvas renders non-empty pixels before downloading PNG.

## C# Parity Checks

The C# project is the rendering source of truth. Use the parity runner to render selected SWFs through C# and Java, then compare PNG pixels:

```powershell
.\tools\parity\Compare-ChromaParity.ps1 -CSharpRoot C:\SourceControl\Chroma
```

To include the browser/TeaVM path, install the local automation dependency and pass `-IncludeWasm`:

```powershell
npm install --no-save playwright-core
.\tools\parity\Compare-ChromaParity.ps1 -CSharpRoot C:\SourceControl\Chroma -IncludeWasm
```

The runner downloads its SWF fixtures into `build/parity`, builds a temporary C# harness against `C:\SourceControl\Chroma\Chroma\Chroma.csproj`, renders the same options with `chroma-lib`, and reports pixel deltas. A passing row has `DifferingPixels = 0`.

Current parity-sensitive fixtures include:

- `rare_dragonlamp`, directions `0`, `2`, and `4`, which must be exact.
- `rare_dragonlamp`, small render, icon render, ignored `gif` option, shadow render, background render, no-crop colored canvas render including explicit empty canvas, short HTML hex, invalid leading-hash fallback, and endpoint-style `state` / `bg` normalization.
- `rare_parasol`, state `1`, direction `4`, which covers translucent alpha blending and final crop/export behavior.
- `rare_parasol`, state `1`, direction `4`, with shadows enabled.
- `throne`, `club_sofa`, `rare_hammock`, `rare_icecream`, `rare_icecream_campaign`, `doorB`, and `rare_fountain` to broaden direction, small, color, shadow, endpoint-style color/rotation normalization, stateful furni, and simple static furni coverage.

## Build The Spring Webapp

The existing server-side renderer can still be built with:

```powershell
.\gradlew.bat :chroma-webapp:clean :chroma-webapp:bootJar
```

Run it with:

```powershell
java -jar chroma-webapp\build\libs\chroma-webapp-1.0.0.jar
```

The server listens on port `5000` and expects SWFs under `swfs/hof_furni`.
