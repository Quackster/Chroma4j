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
    args[7],
    bool.Parse(args[8]),
    bool.Parse(args[9]));

furni.Run();
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
import java.nio.file.Files;
import java.nio.file.Paths;

public class ChromaParityJavaRender {
    public static void main(String[] args) throws Exception {
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
            args[7],
            Boolean.parseBoolean(args[8]),
            Boolean.parseBoolean(args[9]));

        furni.run();
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
            $_.Key -like "DefineBitsLossless36:*" -and $_.Key -ne "DefineBitsLossless36:Format5"
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
  const browser = await chromium.launch({ headless: true, executablePath: chromePath });
  try {
    const page = await browser.newPage();
    await page.goto(`http://127.0.0.1:${port}/web/dist/index.html`);
    for (const item of cases) {
      let dataUrl;
      try {
        dataUrl = await page.evaluate(async ({ item, port }) => {
          const { loadChroma4j } = await import("./chroma4j.js");
          const chroma = await loadChroma4j({ basePath: "." });
          const result = await chroma.renderFromUrl(`http://127.0.0.1:${port}/${item.swfRelativePath}`, {
            state: item.wasmState ?? item.state,
            direction: item.wasmDirection ?? item.direction,
            rotation: item.wasmRotation,
            color: item.wasmColor ?? item.color,
            colour: item.wasmColour,
            shadow: item.shadow,
            bg: item.wasmBg,
            background: item.wasmBackground ?? item.background,
            canvas: item.canvas,
            crop: item.crop,
            small: item.small,
            icon: item.icon
          });
          return result.dataUrl();
        }, { item, port });
      } catch (error) {
        throw new Error(`${item.name}: ${error.message}`);
      }
      fs.writeFileSync(item.wasmOutput, Buffer.from(dataUrl.split(",")[1], "base64"));
    }
  } finally {
    await browser.close();
    server.close();
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
    [pscustomobject]@{ Name = "rare_dragonlamp_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_small_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $true; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_icon"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 0; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true; Icon = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_shadow"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $true; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_canvas_nocrop"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "336699"; Crop = $false },
    [pscustomobject]@{ Name = "rare_dragonlamp_background"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $true; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_background_nocrop"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $true; Canvas = "transparent"; Crop = $false },
    [pscustomobject]@{ Name = "rare_dragonlamp_state101"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; WasmState = 101; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_dragonlamp_bg_false_case"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_dragonlamp.swf"; Swf = "rare_dragonlamp.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $true; WasmBackground = $false; WasmBg = "False"; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_parasol_alpha"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_parasol.swf"; Swf = "rare_parasol.swf"; Small = $false; State = 1; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_parasol_shadow"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_parasol.swf"; Swf = "rare_parasol.swf"; Small = $false; State = 1; Direction = 4; Color = 0; Shadow = $true; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "throne_small_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/throne.swf"; Swf = "throne.swf"; Small = $true; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_color1"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 1; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_color20"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 0; WasmColor = 20; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_colour_override"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 1; WasmColor = 2; WasmColour = 1; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "club_sofa_shadow"; Url = "https://images.classichabbo.com/dcr/hof_furni/club_sofa.swf"; Swf = "club_sofa.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $true; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_hammock_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_hammock.swf"; Swf = "rare_hammock.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_hammock_d4"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_hammock.swf"; Swf = "rare_hammock.swf"; Small = $false; State = 0; Direction = 4; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
    [pscustomobject]@{ Name = "rare_icecream_d2"; Url = "https://images.classichabbo.com/dcr/hof_furni/rare_icecream.swf"; Swf = "rare_icecream.swf"; Small = $false; State = 0; Direction = 2; Color = 0; Shadow = $false; Background = $false; Canvas = "transparent"; Crop = $true },
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
        canvas = $case.Canvas
        crop = $case.Crop
        small = $case.Small
        icon = $icon
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
        Write-Warning "Skipping WASM parity: Chrome or Edge executable was not found."
    } elseif (!(Test-Path (Join-Path $workspace "node_modules\playwright-core"))) {
        Write-Warning "Skipping WASM parity: node_modules\playwright-core was not found. Run npm install --no-save playwright-core to enable this check."
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
