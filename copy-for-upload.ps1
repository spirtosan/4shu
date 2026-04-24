# copy-for-upload.ps1
# Run from C:\Users\spirt\files\
# Copies all relevant source files to C:\Users\spirt\files\ for upload to Claude

$src = "C:\Users\spirt\fshu"
$dst = "C:\Users\spirt\files"

$files = @(
    # Core
    "app\src\main\java\com\fshu\MainActivity.kt",
    "app\src\main\java\com\fshu\FshuApplication.kt",
    "app\src\main\java\com\fshu\UserAdapter.kt",
    # Data
    "app\src\main\java\com\fshu\data\local\AppDatabase.kt",
    "app\src\main\java\com\fshu\data\local\MessageDao.kt",
    "app\src\main\java\com\fshu\data\model\Message.kt",
    "app\src\main\java\com\fshu\data\model\User.kt",
    "app\src\main\java\com\fshu\data\model\ListItem.kt",
    "app\src\main\java\com\fshu\data\remote\WebSocketClient.kt",
    # Service
    "app\src\main\java\com\fshu\service\FshuService.kt",
    "app\src\main\java\com\fshu\service\FshuFirebaseService.kt",
    "app\src\main\java\com\fshu\service\WebRTCManager.kt",
    "app\src\main\java\com\fshu\service\ServiceRestartReceiver.kt",
    "app\src\main\java\com\fshu\service\ServiceWatchdogWorker.kt",
    # UI
    "app\src\main\java\com\fshu\ui\login\LoginActivity.kt",
    "app\src\main\java\com\fshu\ui\passphrase\PassphraseActivity.kt",
    "app\src\main\java\com\fshu\ui\passphrase\PassphraseSetupActivity.kt",
    "app\src\main\java\com\fshu\ui\permission\PermissionSetupActivity.kt",
    "app\src\main\java\com\fshu\ui\chat\ChatActivity.kt",
    "app\src\main\java\com\fshu\ui\chat\ChatViewModel.kt",
    "app\src\main\java\com\fshu\ui\chat\ChatAdapter.kt",
    "app\src\main\java\com\fshu\ui\call\CallActivity.kt",
    "app\src\main\java\com\fshu\ui\call\CallViewModel.kt",
    "app\src\main\java\com\fshu\ui\admin\AdminPanelActivity.kt",
    "app\src\main\java\com\fshu\ui\admin\ChangePasswordDialog.kt",
    "app\src\main\java\com\fshu\ui\settings\SettingsActivity.kt",
    "app\src\main\java\com\fshu\ui\BackgroundBottomSheet.kt",
    "app\src\main\java\com\fshu\ui\BackgroundHelper.kt",
    "app\src\main\java\com\fshu\ui\ConnectionTestSheet.kt",
    "app\src\main\java\com\fshu\ui\AppLockManager.kt",
    # Util
    "app\src\main\java\com\fshu\util\CryptoHelper.kt",
    "app\src\main\java\com\fshu\util\LocationHelper.kt",
    "app\src\main\java\com\fshu\util\MessageBus.kt",
    "app\src\main\java\com\fshu\util\Prefs.kt",
    # Server
    "server.js",
    "server_remote.js",
    "admin.js",
    "package.json",
    # Android config
    "app\src\main\AndroidManifest.xml",
    "app\build.gradle",
    "build.gradle",
    "settings.gradle",
    "gradle.properties",
    # Menus
    "app\src\main\res\menu\menu_main.xml",
    "app\src\main\res\menu\menu_chat.xml",
    "app\src\main\res\menu\menu_user.xml",
    "app\src\main\res\menu\menu_admin_panel.xml",
    # Resources
    "app\src\main\res\values\strings.xml",
    "app\src\main\res\values\colors.xml",
    "app\src\main\res\values\themes.xml",
    "app\src\main\res\xml\file_paths.xml",
    # Layouts
    "app\src\main\res\layout\activity_settings.xml",
    "app\src\main\res\layout\activity_passphrase.xml",
    "app\src\main\res\layout\activity_passphrase_setup.xml",
    "app\src\main\res\layout\fragment_connection_test.xml",
    "app\src\main\res\layout\activity_chat.xml",
    "app\src\main\res\layout\item_message_sent.xml",
    "app\src\main\res\layout\item_message_received.xml",
    "app\src\main\res\layout\item_list_sent.xml",
    "app\src\main\res\layout\item_list_received.xml",
    "app\src\main\res\layout\item_location_sent.xml",
    "app\src\main\res\layout\item_location_received.xml",
    "app\src\main\res\layout\item_location_request_sent.xml",
    "app\src\main\res\layout\item_location_request_received.xml",
    "app\src\main\res\layout\item_user.xml"
)

# Keep the script itself safe — copy it to the fshu project root
$scriptSrc = "$dst\copy-for-upload.ps1"
$scriptDst = "$src\copy-for-upload.ps1"
if (Test-Path $scriptSrc) {
    Copy-Item $scriptSrc $scriptDst -Force
}

# Clean and recreate destination
if (Test-Path $dst) {
    Remove-Item "$dst\*" -Recurse -Force
} else {
    New-Item -ItemType Directory -Path $dst | Out-Null
}

# Restore the script after cleaning
if (Test-Path $scriptDst) {
    Copy-Item $scriptDst $scriptSrc -Force
}

$ok = 0
$missing = @()

foreach ($rel in $files) {
    $full = Join-Path $src $rel
    if (Test-Path $full) {
        $name = Split-Path $rel -Leaf
        $dest = Join-Path $dst $name
        if (Test-Path $dest) {
            $parentPath = Split-Path $rel -Parent
            $parent = if ($parentPath) { Split-Path $parentPath -Leaf } else { "root" }
            $name = "${parent}_${name}"
            $dest = Join-Path $dst $name
        }
        Copy-Item $full $dest
        $ok++
    } else {
        $missing += $rel
    }
}

Write-Host ""
Write-Host "Copied $ok files to $dst" -ForegroundColor Green
if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "Missing ($($missing.Count)):" -ForegroundColor Yellow
    $missing | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
}
Write-Host ""
Write-Host "Done. Open folder? (y/n)" -NoNewline
$key = Read-Host
if ($key -eq "y") { explorer $dst }