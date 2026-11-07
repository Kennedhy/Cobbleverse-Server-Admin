$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$versionLine = Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') | Where-Object { $_ -match '^\s*mod_version\s*=' } | Select-Object -First 1
if ($versionLine -notmatch '^\s*mod_version\s*=\s*([0-9A-Za-z.-]+)\s*$') { throw 'Não foi possível ler mod_version de gradle.properties.' }
$version = $Matches[1]
$outputName = "cobbleverse-generation-control-$version-source.zip"
$outputDirectory = Join-Path $projectRoot 'build/distributions'
$outputPath = Join-Path $outputDirectory $outputName
$excludedDirectories = @('.gradle', 'build', 'run', 'logs', 'node_modules', 'dist', '.git')

[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$fileStream = [System.IO.File]::Open($outputPath, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
$fileStream.SetLength(0)
$archive = [System.IO.Compression.ZipArchive]::new($fileStream, [System.IO.Compression.ZipArchiveMode]::Create)
$entryCount = 0

try {
    foreach ($file in Get-ChildItem -LiteralPath $projectRoot -Recurse -File) {
        $relativePath = [System.IO.Path]::GetRelativePath($projectRoot, $file.FullName).Replace('\', '/')
        $segments = $relativePath.Split('/')
        if (@($segments | Where-Object { $excludedDirectories -contains $_ }).Count -gt 0) { continue }

        $entry = $archive.CreateEntry($relativePath, [System.IO.Compression.CompressionLevel]::Optimal)
        $inputStream = [System.IO.File]::OpenRead($file.FullName)
        $entryStream = $entry.Open()
        try { $inputStream.CopyTo($entryStream) }
        finally { $entryStream.Dispose(); $inputStream.Dispose() }
        $entryCount++
    }
}
finally {
    $archive.Dispose()
    $fileStream.Dispose()
}

Get-Item -LiteralPath $outputPath | Select-Object FullName, Length
Write-Output "Entries=$entryCount"
