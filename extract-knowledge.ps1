# extract-knowledge.ps1
# Extracts files needed for Claude Project Knowledge
# Run from: C:\Users\spirt\fshu-next
# Output: C:\Users\spirt\fshu-knowledge\

$sourceDir = "C:\Users\spirt\fshu-next"
$outputDir = "C:\Users\spirt\fshu-knowledge"

# Create output dir
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

Write-Host "Extracting 4shu Project Knowledge files..." -ForegroundColor Cyan

# Files to extract (these belong in Project Knowledge)
$files = @(
    "BRIEFING.md",
    "4shu_master_plan.md"
)

foreach ($file in $files) {
    $src = Join-Path $sourceDir $file
    $dst = Join-Path $outputDir $file
    if (Test-Path $src) {
        Copy-Item $src $dst -Force
        Write-Host "  OK $file" -ForegroundColor Green
    } else {
        Write-Host "  MISSING $file" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "Done. Files ready in: $outputDir" -ForegroundColor Cyan
Write-Host ""
Write-Host "Upload these to Project Knowledge:" -ForegroundColor Yellow
Write-Host "  - BRIEFING.md"
Write-Host "  - 4shu_master_plan.md"
Write-Host "  - 4shu_colors.md       (download from Claude)"
Write-Host "  - 4shu_db_schema.md    (download from Claude)"
Write-Host ""
Write-Host "Remove from Project Knowledge (Claude Code handles these):" -ForegroundColor Red
Write-Host "  - All .kt files"
Write-Host "  - All .xml files"
Write-Host "  - server5_reference.js"
Write-Host "  - admin.js"
Write-Host "  - Any config files"
