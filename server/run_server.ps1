# Helper to locate Python, install requirements, and start the FastAPI server
# Usage: PowerShell (run as user):
#   powershell -ExecutionPolicy Bypass -File .\run_server.ps1

Set-StrictMode -Version Latest
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

Write-Host "Looking for Python..."
$python = $null

# Try common launchers
foreach ($cmd in @('py','python','python3')) {
    try {
        $path = (Get-Command $cmd -ErrorAction Stop).Source
        if (Test-Path $path) { $python = $path; break }
    } catch {}
}

# Common installation locations
$candidates = @(
    "$env:LocalAppData\Programs\Python\Python311\python.exe",
    "$env:LocalAppData\Programs\Python\Python310\python.exe",
    "$env:LocalAppData\Programs\Python\Python39\python.exe",
    "$env:ProgramFiles\Python311\python.exe",
    "$env:ProgramFiles\Python\Python311\python.exe",
    "C:\\Python311\\python.exe",
    "C:\\Python39\\python.exe"
)

foreach ($p in $candidates) {
    if (-not $python -and (Test-Path $p)) { $python = $p; break }
}

if (-not $python) {
    Write-Error "Python not found. Install Python and ensure it's on PATH, or run this script with the full path to python.exe.`nSee https://www.python.org/downloads/"
    exit 2
}

Write-Host "Using Python at: $python"

Write-Host "Installing requirements (user) from requirements.txt..."
& $python -m pip install --user -r requirements.txt
if ($LASTEXITCODE -ne 0) {
    Write-Warning "pip install returned exit code $LASTEXITCODE. You may need to run the script as an administrator or install packages manually."
}

Write-Host "Starting uvicorn server on http://localhost:8000 ..."
& $python -m uvicorn main:app --host 0.0.0.0 --port 8000