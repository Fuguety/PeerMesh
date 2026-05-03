param
(
    [Parameter(Mandatory = $true)]
    [string]$Username,

    [Parameter(Mandatory = $true)]
    [int]$Port,

    [switch]$NoDebug,

    [string]$CacheFile = ""
)

$buildDir = "build"
$sourceDir = "src"

Write-Host "Creating build folder..."
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

Write-Host "Compiling Java files..."
$javaFiles = Get-ChildItem -Path $sourceDir -Filter "*.java" | ForEach-Object { $_.FullName }
$javacHelp = javac --help 2>&1

if ($javacHelp -match "--release")
{
    javac --release 8 -Xlint:-options -d $buildDir $javaFiles
}
else
{
    javac -source 8 -target 8 -Xlint:-options -d $buildDir $javaFiles
}

if ($LASTEXITCODE -ne 0)
{
    Write-Host "Compilation failed."
    exit 1
}

Write-Host ""
Write-Host "Starting P2P chat node..."
Write-Host "Username: $Username"
Write-Host "Port: $Port"
Write-Host "Debug DNS: $(-not $NoDebug)"
Write-Host ""

$javaArgs = @($Username, $Port)

if ($NoDebug)
{
    $javaArgs += "--no-debug"
}
else
{
    $javaArgs += "--debug"
}

if ($CacheFile -eq "")
{
    java -cp $buildDir Main $javaArgs
}
else
{
    $javaArgs += $CacheFile
    java -cp $buildDir Main $javaArgs
}

Write-Host ""
Write-Host "Node stopped."
