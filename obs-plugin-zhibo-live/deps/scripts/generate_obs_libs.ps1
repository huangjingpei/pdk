$dumpbin = "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Tools\MSVC\14.41.34120\bin\Hostx64\x64\dumpbin.exe"
$libExe = "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Tools\MSVC\14.41.34120\bin\Hostx64\x64\lib.exe"

$outDir = "E:\pdk\obs-plugin-zhibo-live\deps\lib"
if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir }

function Generate-Lib($dllPath, $libName) {
    Write-Host "Processing $dllPath -> $libName.lib"
    $exports = & $dumpbin /exports $dllPath
    $defPath = Join-Path $outDir "$libName.def"
    $libPath = Join-Path $outDir "$libName.lib"
    
    $defContent = @("LIBRARY $libName", "EXPORTS")
    foreach ($line in $exports) {
        if ($line -match '^\s+\d+\s+[0-9A-Fa-f]+\s+[0-9A-Fa-f]+\s+([a-zA-Z0-9_@?]+)') {
            $defContent += "    " + $matches[1]
        }
    }
    
    $defContent | Set-Content -Encoding ASCII $defPath
    Write-Host "Found $($defContent.Count - 2) exports. Generating $libName.lib..."
    & $libExe /def:$defPath /out:$libPath /machine:x64
    if (Test-Path $libPath) {
        Write-Host "Successfully generated $libPath"
    } else {
        Write-Error "Failed to generate $libPath"
    }
}

Generate-Lib "C:\Program Files\obs-studio\bin\64bit\obs.dll" "obs"
Generate-Lib "C:\Program Files\obs-studio\bin\64bit\obs-frontend-api.dll" "obs-frontend-api"
