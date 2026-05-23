param(
    [string] $CSharpRoot = "C:\SourceControl\Chroma",
    [string] $OutputDir = "build\parity",
    [switch] $IncludeWasm
)

$ErrorActionPreference = "Stop"

function Resolve-FullPath([string] $Path) {
    $executionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
}

function Set-Utf8NoBomContent([string] $Path, [string] $Value) {
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText((Resolve-FullPath $Path), $Value, $encoding)
}

function New-CSharpHarness([string] $HarnessDir, [string] $CSharpProject) {
    New-Item -ItemType Directory -Force -Path $HarnessDir | Out-Null

    $projectXml = @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net8.0</TargetFramework>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
  </PropertyGroup>
  <ItemGroup>
    <ProjectReference Include="$CSharpProject" />
  </ItemGroup>
</Project>
"@

    $program = @'
using Chroma;
using System.IO;

if (args.Length == 1 && args[0] == "__PROBE_HEX__")
{
    try
    {
        ChromaFurniture.HexToColor(null!);
        Console.WriteLine("null=NO_THROW");
    }
    catch
    {
        Console.WriteLine("null=THROW");
    }

    var hashFallback = ChromaFurniture.HexToColor("#336699");
    var shortHex = ChromaFurniture.HexToColor("FFF");
    var transparent = ChromaFurniture.HexToColor("transparent");
    Console.WriteLine($"hash={hashFallback.R},{hashFallback.G},{hashFallback.B},{hashFallback.A}");
    Console.WriteLine($"short={shortHex.R},{shortHex.G},{shortHex.B},{shortHex.A}");
    Console.WriteLine($"transparent={transparent.R},{transparent.G},{transparent.B},{transparent.A}");
    return 0;
}

if (args.Length < 11)
{
    Console.Error.WriteLine("usage: <swf> <small> <state> <direction> <color> <shadow> <background> <canvas> <crop> <icon> <out>");
    return 2;
}

var furni = new ChromaFurniture(
    args[0],
    bool.Parse(args[1]),
    int.Parse(args[2]),
    int.Parse(args[3]),
    int.Parse(args[4]),
    bool.Parse(args[5]),
    bool.Parse(args[6]),
    args[7] == "__CHROMA_EMPTY__" ? "" : args[7],
    bool.Parse(args[8]),
    bool.Parse(args[9]));

var outputName = furni.Run();
File.WriteAllText(args[10] + ".name", outputName);
File.WriteAllBytes(args[10], furni.CreateImage());
return 0;
'@

    Set-Utf8NoBomContent (Join-Path $HarnessDir "ChromaParityCS.csproj") $projectXml
    Set-Utf8NoBomContent (Join-Path $HarnessDir "Program.cs") $program
}

function New-JavaHarness([string] $HarnessDir) {
    New-Item -ItemType Directory -Force -Path $HarnessDir | Out-Null

    $source = @'
import com.quackster.chroma.ChromaFurniture;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ChromaParityJavaRender {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "__PROBE_HEX__".equals(args[0])) {
            try {
                ChromaFurniture.hexToColor(null);
                System.out.println("null=NO_THROW");
            } catch (Exception e) {
                System.out.println("null=THROW");
            }

            Color hashFallback = ChromaFurniture.hexToColor("#336699");
            Color shortHex = ChromaFurniture.hexToColor("FFF");
            Color transparent = ChromaFurniture.hexToColor("transparent");
            System.out.println("hash=" + hashFallback.getRed() + "," + hashFallback.getGreen() + "," + hashFallback.getBlue() + "," + hashFallback.getAlpha());
            System.out.println("short=" + shortHex.getRed() + "," + shortHex.getGreen() + "," + shortHex.getBlue() + "," + shortHex.getAlpha());
            System.out.println("transparent=" + transparent.getRed() + "," + transparent.getGreen() + "," + transparent.getBlue() + "," + transparent.getAlpha());
            return;
        }

        if (args.length < 11) {
            System.err.println("usage: <swf> <small> <state> <direction> <color> <shadow> <background> <canvas> <crop> <icon> <out>");
            System.exit(2);
        }

        ChromaFurniture furni = new ChromaFurniture(
            args[0],
            Boolean.parseBoolean(args[1]),
            Integer.parseInt(args[2]),
            Integer.parseInt(args[3]),
            Integer.parseInt(args[4]),
            Boolean.parseBoolean(args[5]),
            Boolean.parseBoolean(args[6]),
            "__CHROMA_EMPTY__".equals(args[7]) ? "" : args[7],
            Boolean.parseBoolean(args[8]),
            Boolean.parseBoolean(args[9]));

        String outputName = furni.run();
        Files.writeString(Paths.get(args[10] + ".name"), outputName);
        byte[] png = furni.createImage();
        if (png == null) {
            System.err.println("renderer returned null");
            System.exit(3);
        }
        Files.write(Paths.get(args[10]), png);
    }
}
'@

    Set-Utf8NoBomContent (Join-Path $HarnessDir "ChromaParityJavaRender.java") $source
}

function Get-JavaRuntimeClasspath([string] $Workspace, [string] $WorkDir) {
    $initScript = Join-Path $WorkDir "classpath.gradle"
    $init = @'
gradle.projectsEvaluated {
    def chromaLib = gradle.rootProject.project(':chroma-lib')
    chromaLib.tasks.register('printRuntimeClasspath') {
        doLast { println chromaLib.sourceSets.main.runtimeClasspath.asPath }
    }
}
'@
    Set-Utf8NoBomContent $initScript $init
    $classpath = (& (Join-Path $Workspace "gradlew.bat") -q -I $initScript :chroma-lib:printRuntimeClasspath)
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed while generating the chroma-lib runtime classpath."
    }
    (($classpath -split ';') | Where-Object { Test-Path $_ }) -join ';'
}

function Invoke-Renderer([string] $Command, [string[]] $Arguments, [string] $WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Command exited with code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-RendererOutput([string] $Command, [string[]] $Arguments, [string] $WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        $output = & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Command exited with code $LASTEXITCODE"
        }
        ($output -join "`n").Trim()
    } finally {
        Pop-Location
    }
}

function Compare-TextFile([string] $ExpectedPath, [string] $ActualPath, [string] $Description) {
    $expected = [System.IO.File]::ReadAllText((Resolve-FullPath $ExpectedPath))
    $actual = [System.IO.File]::ReadAllText((Resolve-FullPath $ActualPath))
    if ($expected -ne $actual) {
        throw "$Description differs. Expected '$expected' but got '$actual'."
    }
}

function Get-CaseProperty([object] $Case, [string] $Name) {
    if ($Case.PSObject.Properties.Name -contains $Name) {
        return $Case.$Name
    }
    return $null
}

function Read-UInt16LE([byte[]] $Bytes, [int] $Offset) {
    [int] $Bytes[$Offset] -bor ([int] $Bytes[$Offset + 1] -shl 8)
}

function Read-Int32LE([byte[]] $Bytes, [int] $Offset) {
    [int] $Bytes[$Offset] -bor ([int] $Bytes[$Offset + 1] -shl 8) -bor ([int] $Bytes[$Offset + 2] -shl 16) -bor ([int] $Bytes[$Offset + 3] -shl 24)
}

function Expand-SwfBody([string] $SwfPath) {
    $bytes = [System.IO.File]::ReadAllBytes((Resolve-FullPath $SwfPath))
    if ($bytes.Length -lt 8) {
        throw "SWF file is too short: $SwfPath"
    }

    $signature = [char] $bytes[0]
    if ($bytes[1] -ne [byte][char]'W' -or $bytes[2] -ne [byte][char]'S') {
        throw "Unsupported SWF signature in $SwfPath"
    }

    if ($signature -eq 'F') {
        $body = New-Object byte[] ($bytes.Length - 8)
        [Array]::Copy($bytes, 8, $body, 0, $body.Length)
        return $body
    }

    if ($signature -ne 'C') {
        throw "Unsupported SWF compression '$signature' in $SwfPath"
    }

    if ($bytes.Length -lt 12) {
        throw "Compressed SWF file is too short: $SwfPath"
    }

    $inputStream = [System.IO.MemoryStream]::new($bytes, 10, $bytes.Length - 14)
    $deflate = [System.IO.Compression.DeflateStream]::new($inputStream, [System.IO.Compression.CompressionMode]::Decompress)
    $outputStream = [System.IO.MemoryStream]::new()
    try {
        $deflate.CopyTo($outputStream)
        return $outputStream.ToArray()
    } finally {
        $deflate.Dispose()
        $inputStream.Dispose()
        $outputStream.Dispose()
    }
}

function Get-SwfBitmapSummary([string] $SwfPath) {
    $body = Expand-SwfBody $SwfPath
    if ($body.Length -lt 5) {
        throw "SWF body is too short: $SwfPath"
    }

    $nbits = $body[0] -shr 3
    $rectBytes = [math]::Ceiling((5 + ($nbits * 4)) / 8)
    $offset = [int] $rectBytes + 4
    $summary = @{}

    while ($offset + 2 -le $body.Length) {
        $header = Read-UInt16LE $body $offset
        $offset += 2
        $code = $header -shr 6
        $length = $header -band 63
        if ($length -eq 63) {
            if ($offset + 4 -gt $body.Length) {
                throw "Malformed long SWF tag length in $SwfPath"
            }
            $length = Read-Int32LE $body $offset
            $offset += 4
        }
        if ($length -lt 0 -or $offset + $length -gt $body.Length) {
            throw "Malformed SWF tag in $SwfPath"
        }
        if ($code -eq 0) {
            break
        }

        if ($code -eq 20 -or $code -eq 36) {
            if ($length -ge 7) {
                $format = $body[$offset + 2]
                $key = "DefineBitsLossless$($code):Format$format"
                if (!$summary.ContainsKey($key)) {
                    $summary[$key] = 0
                }
                $summary[$key] = 1 + [int] $summary[$key]
            }
        } elseif ($code -eq 21 -or $code -eq 35 -or $code -eq 90) {
            $key = "EncodedImage$code"
            if (!$summary.ContainsKey($key)) {
                $summary[$key] = 0
            }
            $summary[$key] = 1 + [int] $summary[$key]
        }

        $offset += $length
    }

    [pscustomobject]@{
        Swf = Split-Path $SwfPath -Leaf
        Summary = (($summary.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join "; ")
        UnsupportedForWasm = (($summary.GetEnumerator() | Where-Object {
            $_.Key -like "DefineBitsLossless*:*" -and $_.Key -notlike "*:Format5"
        } | Sort-Object Name | ForEach-Object { $_.Key }) -join ", ")
    }
}

function Compare-Png([string] $ExpectedPath, [string] $ActualPath) {
    Add-Type -AssemblyName System.Drawing

    $expected = [System.Drawing.Bitmap]::new((Resolve-FullPath $ExpectedPath))
    $actual = [System.Drawing.Bitmap]::new((Resolve-FullPath $ActualPath))
    try {
        if ($expected.Width -ne $actual.Width -or $expected.Height -ne $actual.Height) {
            return [pscustomobject]@{
                Expected = $ExpectedPath
                Actual = $ActualPath
                SizeExpected = "$($expected.Width)x$($expected.Height)"
                SizeActual = "$($actual.Width)x$($actual.Height)"
                DifferingPixels = -1
                MaxChannelDelta = -1
                SumChannelDelta = -1
                FirstDiff = "size mismatch"
            }
        }

        $differingPixels = 0
        $maxChannelDelta = 0
        [long] $sumChannelDelta = 0
        $firstDiff = ""

        for ($y = 0; $y -lt $expected.Height; $y++) {
            for ($x = 0; $x -lt $expected.Width; $x++) {
                $a = $expected.GetPixel($x, $y)
                $b = $actual.GetPixel($x, $y)
                $deltas = @(
                    [math]::Abs($a.R - $b.R),
                    [math]::Abs($a.G - $b.G),
                    [math]::Abs($a.B - $b.B),
                    [math]::Abs($a.A - $b.A)
                )
                $pixelDelta = ($deltas | Measure-Object -Maximum).Maximum
                if ($pixelDelta -gt 0) {
                    $differingPixels++
                    $sumChannelDelta += ($deltas | Measure-Object -Sum).Sum
                    if ($pixelDelta -gt $maxChannelDelta) {
                        $maxChannelDelta = $pixelDelta
                    }
                    if ($firstDiff.Length -eq 0) {
                        $firstDiff = "x=$x y=$y expected=($($a.R),$($a.G),$($a.B),$($a.A)) actual=($($b.R),$($b.G),$($b.B),$($b.A))"
                    }
                }
            }
        }

        [pscustomobject]@{
            Expected = $ExpectedPath
            Actual = $ActualPath
            SizeExpected = "$($expected.Width)x$($expected.Height)"
            SizeActual = "$($actual.Width)x$($actual.Height)"
            DifferingPixels = $differingPixels
            MaxChannelDelta = $maxChannelDelta
            SumChannelDelta = $sumChannelDelta
            FirstDiff = $firstDiff
        }
    } finally {
        $expected.Dispose()
        $actual.Dispose()
    }
}

function Write-WasmHarness([string] $ScriptPath) {
    $script = @'
const fs = require("fs");
const path = require("path");
const http = require("http");
const { chromium } = require("playwright-core");

const workspace = process.argv[2];
const casesPath = process.argv[3];
const chromePath = process.argv[4];
const cases = JSON.parse(fs.readFileSync(casesPath, "utf8"));
const mime = { ".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".swf": "application/x-shockwave-flash" };

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://127.0.0.1");
  let rel = decodeURIComponent(url.pathname.slice(1));
  if (!rel || rel === "index.html") rel = "web/dist/index.html";
  const file = path.normalize(path.join(workspace, rel));
  if (!file.startsWith(path.normalize(workspace)) || !fs.existsSync(file)) {
    res.writeHead(404);
    res.end("not found");
    return;
  }
  res.writeHead(200, { "content-type": mime[path.extname(file)] || "application/octet-stream", "access-control-allow-origin": "*" });
  fs.createReadStream(file).pipe(res);
});

server.listen(0, "127.0.0.1", async () => {
  const port = server.address().port;
  let browser;
  let exitCode = 0;
  try {
    browser = await chromium.launch({ headless: true, executablePath: chromePath });
    const page = await browser.newPage();
    await page.goto(`http://127.0.0.1:${port}/web/dist/index.html`);
    const invalidRejected = await page.evaluate(async () => {
      const { loadChroma4j } = await import("./chroma4j.js");
      const chroma = await loadChroma4j({ basePath: "." });
      try {
        await chroma.renderFromBytes(new Uint8Array([110, 111, 116, 45, 97, 45, 115, 119, 102]).buffer, { sprite: "bad" });
        return false;
      } catch (error) {
        return error instanceof Error && error.message.length > 0;
      }
    });
    if (!invalidRejected) {
      throw new Error("renderFromBytes accepted invalid SWF bytes");
    }
    for (const item of cases) {
      let renderResult;
      try {
        renderResult = await page.evaluate(async ({ item, port }) => {
          const { loadChroma4j } = await import("./chroma4j.js");
          const chroma = await loadChroma4j({ basePath: "." });
          const swfUrl = `http://127.0.0.1:${port}/${item.swfRelativePath}`;
          const renderOptions = {
            state: item.wasmState ?? item.state,
            direction: item.wasmDirection ?? item.direction,
            rotation: item.wasmRotation,
            color: item.wasmColor ?? item.color,
            colour: item.wasmColour,
            shadow: item.shadow,
            bg: item.wasmBg,
            background: item.wasmBackground ?? item.background,
            canvas: item.canvas === "__CHROMA_EMPTY__" ? "" : item.canvas,
            crop: item.wasmCrop ?? item.crop,
            small: item.wasmSmall ?? item.small,
            s: item.wasmS,
            icon: item.icon,
            gif: item.wasmGif
          };
          let result;
          if (item.assertRenderFromBytes) {
            const response = await fetch(swfUrl);
            result = await chroma.renderFromBytes(await response.arrayBuffer(), { ...renderOptions, sprite: item.sprite });
          } else {
            result = await chroma.renderFromUrl(swfUrl, renderOptions);
          }
          const dataUrl = result.dataUrl();
          if (!item.assertResultApi) {
            return { dataUrl };
          }
          const blob = await result.blob();
          return {
            dataUrl,
            blobType: blob.type,
            blobBytes: Array.from(new Uint8Array(await blob.arrayBuffer()))
          };
        }, { item, port });
      } catch (error) {
        throw new Error(`${item.name}: ${error.message}`);
      }
      const pngBytes = Buffer.from(renderResult.dataUrl.split(",")[1], "base64");
      if (item.assertResultApi) {
        const blobBytes = Buffer.from(renderResult.blobBytes);
        if (renderResult.blobType !== "image/png") {
          throw new Error(`${item.name}: result.blob() returned ${renderResult.blobType}`);
        }
        if (!pngBytes.equals(blobBytes)) {
          throw new Error(`${item.name}: result.blob() bytes differ from result.dataUrl() bytes`);
        }
      }
      fs.writeFileSync(item.wasmOutput, pngBytes);
    }
  } catch (error) {
    exitCode = 1;
    console.error(error && error.stack ? error.stack : error);
  } finally {
    if (browser) {
      await browser.close();
    }
    server.close(() => process.exit(exitCode));
  }
});
'@
    Set-Utf8NoBomContent $ScriptPath $script
}

$workspace = Resolve-FullPath "."
$output = Resolve-FullPath $OutputDir
$swfDir = Join-Path $output "swfs\hof_furni"
$csharpProject = Join-Path $CSharpRoot "Chroma\Chroma.csproj"

if (!(Test-Path $csharpProject)) {
    throw "C# source project was not found at $csharpProject"
}

New-Item -ItemType Directory -Force -Path $swfDir | Out-Null

$cases = @(
    [pscustomobject]@{ Name = "rare_dragonlamp_d0"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 0; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_direction_fallback"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 7; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_small_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $true; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_icon"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 0; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true; Icon = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_icon_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true; Icon = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_gif_ignored"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true; WasmGif = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_shadow"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $true; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_canvas_nocrop"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "336699"; Crop = $false },
    [pscustomobject]@{ Name = "rare_dragonlamp_canvas_empty_nocrop"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "__CHROMA_EMPTY__"; Crop = $false },
    [pscustomobject]@{ Name = "rare_dragonlamp_canvas_short_hex"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "FFF"; Crop = $false },
    [pscustomobject]@{ Name = "rare_dragonlamp_canvas_hash_fallback"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "#336699"; Crop = $false },
    [pscustomobject]@{ Name = "rare_dragonlamp_crop_true_case"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $false; WasmCrop = "True" },
    [pscustomobject]@{ Name = "rare_dragonlamp_background"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $true; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_background_nocrop"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $true; Canvas = "transparent"; Crop = $false },
    [pscustomobject]@{ Name = "rare_dragonlamp_state101"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; WasmState = 101; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_bg_false_case"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $true; WasmBackground = $false; WasmBg = "False"; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_parasol_alpha"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_parasol.swf"; Swf = "rare_parasol.swf"; Small = $false; State = 1; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_parasol_shadow"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_parasol.swf"; Swf = "rare_parasol.swf"; Small = $false; State = 1; Direction = 4; Color = 0; Shadow = $true; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_rotation_override"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $false; State = 0; Direction = 4; WasmDirection = 2; WasmRotation = "4"; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_rotation_invalid"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $false; State = 0; Direction = 2; WasmRotation = "2abc"; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_small_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $true; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_small_s_alias"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $true; WasmSmall = $false; WasmS = "true"; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_small_true_case"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $false; WasmSmall = "True"; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_color1"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 1; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_color20"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 0; WasmColor = 20; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_colour_override"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 1; WasmColor = 2; WasmColour = 1; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_shadow"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $true; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_hammock_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_hammock.swf"; Swf = "rare_hammock.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_hammock_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_hammock.swf"; Swf = "rare_hammock.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_icecream_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_icecream.swf"; Swf = "rare_icecream.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_icecream_state2_d0"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_icecream.swf"; Swf = "rare_icecream.swf"; Small = $false; State = 2; Direction = 0; Color = -1; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_icecream_state99_d0"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_icecream.swf"; Swf = "rare_icecream.swf"; Small = $false; State = 99; Direction = 0; Color = -1; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_icecream_campaign_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_icecream_campaign.swf"; Swf = "rare_icecream_campaign.swf"; Small = $false; State = 0; Direction = 2; Color = -1; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "doorB_state2_d0"; Url = "https://images.classichabbo.com/dcr/hof_furni/doorB.swf"; Swf = "doorB.swf"; Small = $false; State = 2; Direction = 0; Color = -1; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_fountain_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_fountain.swf"; Swf = "rare_fountain.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_fountain_shadow"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_fountain.swf"; Swf = "rare_fountain.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $true; Background = $false; Canvas = "transparent"; Crop = $true }
)

foreach ($case in $cases | Group-Object Swf) {
    $target = Join-Path $swfDir $case.Name
    if (!(Test-Path $target)) {
        Invoke-WebRequest -Uri $case.Group[0].Url -OutFile $target -UseBasicParsing
    }
}

$bitmapCoverage = foreach ($swf in Get-ChildItem -Path $swfDir -Filter *.swf) {
    Get-SwfBitmapSummary $swf.FullName
}
$bitmapCoverage | Format-Table Swf, Summary -AutoSize
$unsupportedBitmapFormats = $bitmapCoverage | Where-Object { $_.UnsupportedForWasm }
if ($unsupportedBitmapFormats) {
    $unsupportedBitmapFormats | Format-Table Swf, UnsupportedForWasm -AutoSize
    throw "Fixture set contains SWF bitmap formats not currently supported by the TeaVM extractor."
}

& (Join-Path $workspace "gradlew.bat") :chroma-lib:classes | Out-Host

$harnessRoot = Join-Path $output "harness"
$csharpHarness = Join-Path $harnessRoot "csharp"
$javaHarness = Join-Path $harnessRoot "java"
New-CSharpHarness $csharpHarness $csharpProject
New-JavaHarness $javaHarness

$classpath = Get-JavaRuntimeClasspath $workspace $harnessRoot
$javaClasses = Join-Path $javaHarness "classes"
New-Item -ItemType Directory -Force -Path $javaClasses | Out-Null
Invoke-Renderer "javac" @("-cp", $classpath, "-d", $javaClasses, (Join-Path $javaHarness "ChromaParityJavaRender.java")) $workspace

$csharpHarnessProject = Join-Path $csharpHarness "ChromaParityCS.csproj"
Invoke-Renderer "dotnet" @("build", $csharpHarnessProject, "--nologo", "-v:q") $workspace
$csharpHarnessDll = Join-Path $csharpHarness "bin\Debug\net8.0\ChromaParityCS.dll"
if (!(Test-Path $csharpHarnessDll)) {
    throw "C# parity harness did not produce $csharpHarnessDll"
}

$csharpHexProbe = Invoke-RendererOutput "dotnet" @($csharpHarnessDll, "__PROBE_HEX__") $workspace
$javaHexProbe = Invoke-RendererOutput "java" @("-cp", "$javaClasses;$classpath", "ChromaParityJavaRender", "__PROBE_HEX__") $workspace
if ($csharpHexProbe -ne $javaHexProbe) {
    Write-Host "C# hex probe:"
    Write-Host $csharpHexProbe
    Write-Host "Java hex probe:"
    Write-Host $javaHexProbe
    throw "Hex color behavior differs between C# and Java."
}
Write-Host "Hex color behavior parity:"
Write-Host $javaHexProbe

$results = New-Object System.Collections.Generic.List[object]
$wasmCases = New-Object System.Collections.Generic.List[object]

foreach ($case in $cases) {
    $swfPath = Join-Path $swfDir $case.Swf
    $csOutput = Join-Path $output "$($case.Name)-csharp.png"
    $javaOutput = Join-Path $output "$($case.Name)-java.png"
    $icon = [bool] ($case.PSObject.Properties.Name -contains "Icon" -and $case.Icon)
    $args = @(
        $swfPath,
        $case.Small.ToString().ToLowerInvariant(),
        [string] $case.State,
        [string] $case.Direction,
        [string] $case.Color,
        $case.Shadow.ToString().ToLowerInvariant(),
        $case.Background.ToString().ToLowerInvariant(),
        $case.Canvas,
        $case.Crop.ToString().ToLowerInvariant(),
        $icon.ToString().ToLowerInvariant()
    )

    Invoke-Renderer "dotnet" (@($csharpHarnessDll) + $args + @($csOutput)) $workspace
    Invoke-Renderer "java" (@("-cp", "$javaClasses;$classpath", "ChromaParityJavaRender") + $args + @($javaOutput)) $workspace
    Compare-TextFile "$csOutput.name" "$javaOutput.name" "$($case.Name) output filename"
    $results.Add((Compare-Png $csOutput $javaOutput))

    $wasmCases.Add([pscustomobject]@{
        swfRelativePath = "build/parity/swfs/hof_furni/$($case.Swf)"
        state = $case.State
        direction = $case.Direction
        color = $case.Color
        wasmState = Get-CaseProperty $case "WasmState"
        wasmDirection = Get-CaseProperty $case "WasmDirection"
        wasmRotation = Get-CaseProperty $case "WasmRotation"
        wasmColor = Get-CaseProperty $case "WasmColor"
        wasmColour = Get-CaseProperty $case "WasmColour"
        shadow = $case.Shadow
        background = $case.Background
        wasmBackground = Get-CaseProperty $case "WasmBackground"
        wasmBg = Get-CaseProperty $case "WasmBg"
        wasmGif = Get-CaseProperty $case "WasmGif"
        wasmSmall = Get-CaseProperty $case "WasmSmall"
        wasmS = Get-CaseProperty $case "WasmS"
        wasmCrop = Get-CaseProperty $case "WasmCrop"
        canvas = $case.Canvas
        crop = $case.Crop
        small = $case.Small
        icon = $icon
        sprite = [System.IO.Path]::GetFileNameWithoutExtension($case.Swf)
        assertResultApi = $case.Name -eq "rare_dragonlamp_d0"
        assertRenderFromBytes = $case.Name -eq "rare_dragonlamp_d2"
        name = $case.Name
        wasmOutput = (Join-Path $output "$($case.Name)-wasm.png")
        expectedOutput = $csOutput
    })
}

if ($IncludeWasm) {
    $chromeCandidates = @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "$env:ProgramFiles(x86)\Google\Chrome\Application\chrome.exe",
        "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles(x86)\Microsoft\Edge\Application\msedge.exe"
    )
    $chrome = $chromeCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (!$chrome) {
        throw "WASM parity requested, but Chrome or Edge executable was not found."
    } elseif (!(Test-Path (Join-Path $workspace "node_modules\playwright-core"))) {
        throw "WASM parity requested, but node_modules\playwright-core was not found. Run npm install --no-save playwright-core to enable this check."
    } else {
        $wasmCasesPath = Join-Path $output "wasm-cases.json"
        $wasmScriptPath = Join-Path $output "render-wasm.js"
        Set-Utf8NoBomContent $wasmCasesPath ($wasmCases | ConvertTo-Json -Depth 8)
        Write-WasmHarness $wasmScriptPath
        Invoke-Renderer "node" @($wasmScriptPath, $workspace, $wasmCasesPath, $chrome) $workspace
        foreach ($case in $wasmCases) {
            $results.Add((Compare-Png $case.expectedOutput $case.wasmOutput))
        }
    }
}

$results | Format-Table Expected, Actual, SizeExpected, SizeActual, DifferingPixels, MaxChannelDelta, SumChannelDelta -AutoSize

$failures = $results | Where-Object { $_.DifferingPixels -ne 0 }
if ($failures) {
    $failures | Format-List
    throw "Parity differences were found."
}

Write-Host "Parity artifacts are in $output. Remove with: Remove-Item -Recurse -Force '$output'"
