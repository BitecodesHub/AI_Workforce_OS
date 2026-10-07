# Writes infra\launcher\.env, the launcher's private settings, for "Start AI Workforce OS.bat".
# prepare-env.sh does the same for the macOS .command; keep the two in step.
#
# The secrets are generated once, on the first start, and never regenerated: every credential an
# administrator stores in the app is encrypted under AIWOS_ENCRYPTION_MASTER_KEY, and decryption
# accepts only the key id it was written with, so a new key would make every stored key
# unreadable. Only the demo switch is ever rewritten, and only when it is asked to change.
#
#   prepare-env.ps1 [--demo | --no-demo] [--new-keys]
#
# Reads AIWOS_DEMO_ENABLED (true/false) and AIWOS_EXPOSE_LAN (1 to share on the network) from the
# environment. Run from the repository root with Windows PowerShell 5.1 or later. Exit code: 0
# ready, 1 failed, 3 refused to share the app on the network.

$ErrorActionPreference = 'Stop'

$EnvFile = Join-Path (Get-Location) 'infra\launcher\.env'
$Volume = 'aiwos-app_postgres-data'

# The values every launcher install used before this file existed. An install created then
# encrypted its stored credentials under a key derived from this secret, so it must keep it.
$LegacyKeyId = 'local-dev'
$LegacyDevelopmentSecret = 'insecure-local-development-secret-change-me'
$LegacyDbPassword = 'aiwos'

$demoChoice = $null
$newKeys = $false
foreach ($arg in $args) {
    switch ($arg) {
        '--demo' { $demoChoice = 'true' }
        '--no-demo' { $demoChoice = 'false' }
        '--new-keys' { $newKeys = $true }
        default {
            Write-Host "  Unknown option: $arg (expected --demo, --no-demo or --new-keys)"
            exit 1
        }
    }
}

function ConvertTo-Bool([string]$value) {
    switch -Regex (("$value").Trim().ToLowerInvariant()) {
        '^(1|true|yes|y|on)$' { return 'true' }
        '^(0|false|no|n|off)$' { return 'false' }
        default { return $null }
    }
}

if ($null -eq $demoChoice -and $env:AIWOS_DEMO_ENABLED) {
    $demoChoice = ConvertTo-Bool $env:AIWOS_DEMO_ENABLED
}

function New-RandomBytes([int]$count) {
    $bytes = New-Object byte[] $count
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return , $bytes
}

function New-RandomBase64 { [Convert]::ToBase64String((New-RandomBytes 32)) }

function New-RandomHex { -join ((New-RandomBytes 32) | ForEach-Object { $_.ToString('x2') }) }

# UTF-8 without a byte-order mark and with LF line ends, which is what docker compose reads.
# Written in place, so a file already restricted to this account keeps its permissions.
function Write-EnvLines([string[]]$lines) {
    $text = ($lines -join "`n") + "`n"
    [System.IO.File]::WriteAllText($EnvFile, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Read-EnvLines {
    if (-not (Test-Path -LiteralPath $EnvFile)) { return @() }
    return @([System.IO.File]::ReadAllLines($EnvFile))
}

function Get-EnvValue([string]$key) {
    $value = $null
    foreach ($line in Read-EnvLines) {
        if ($line.StartsWith("$key=")) { $value = $line.Substring($key.Length + 1) }
    }
    return $value
}

function Set-EnvValue([string]$key, [string]$value) {
    $done = $false
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($line in Read-EnvLines) {
        if ($line.StartsWith("$key=")) {
            if (-not $done) { $lines.Add("$key=$value"); $done = $true }
        } else {
            $lines.Add($line)
        }
    }
    if (-not $done) { $lines.Add("$key=$value") }
    Write-EnvLines $lines.ToArray()
}

function Write-NewEnv($keyId, $masterKey, $developmentSecret, $dbPassword, $demo, $loaded) {
    $today = Get-Date -Format 'yyyy-MM-dd'
    Write-EnvLines @(
        "# AI Workforce OS launcher settings, written on $today by prepare-env.ps1.",
        '#',
        '# Private to this computer and never committed. Do not delete it: the keys you store in the app',
        '# are encrypted with AIWOS_ENCRYPTION_MASTER_KEY and cannot be read without it. Keep a copy with',
        '# your backups if the data matters.',
        "AIWOS_ENCRYPTION_KEY_ID=$keyId",
        "AIWOS_ENCRYPTION_MASTER_KEY=$masterKey",
        "AIWOS_SECURITY_DEVELOPMENT_SECRET=$developmentSecret",
        "AIWOS_SECURITY_INTERNAL_SERVICE_SECRET=$(New-RandomHex)",
        '# Read by PostgreSQL only when its data volume is first created.',
        "AIWOS_DB_PASSWORD=$dbPassword",
        '# The sample workspace and demo sign-ins. Change it with --demo or --no-demo.',
        "AIWOS_DEMO_ENABLED=$demo",
        '# Set once demo data has been loaded: the demo sign-ins then exist in this database for good.',
        "AIWOS_DEMO_DATA_LOADED=$loaded"
    )
    # Readable by this account only, like the chmod 600 the macOS launcher applies.
    try {
        $acl = Get-Acl -LiteralPath $EnvFile
        $acl.SetAccessRuleProtection($true, $false)
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')
        $acl.SetAccessRule($rule)
        Set-Acl -LiteralPath $EnvFile -AclObject $acl
    } catch {
        Write-Host "  Note: could not restrict access to $EnvFile. Keep it private."
    }
}

function Test-VolumeExists {
    # Windows PowerShell turns a native command's error output into a terminating error while
    # $ErrorActionPreference is Stop, so the check runs with it relaxed.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & docker volume inspect $Volume *> $null } finally { $ErrorActionPreference = $previous }
    return ($LASTEXITCODE -eq 0)
}

try {
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        if (Test-VolumeExists) {
            # Created by a launcher from before this file existed. Its stored credentials were
            # encrypted under the published development secret, so that secret and its key id are
            # kept, as is the database password the volume was initialised with. Demo data was
            # always on then.
            $demo = if ($null -ne $demoChoice) { $demoChoice } else { 'true' }
            Write-NewEnv $LegacyKeyId '' $LegacyDevelopmentSecret $LegacyDbPassword $demo 'true'
            Write-Host ''
            Write-Host '  WARNING: this installation was created by an earlier version of the launcher.'
            Write-Host '  The AI provider and connector keys stored in it are encrypted with a key that anyone'
            Write-Host '  can work out from the published source code. It is kept so those keys still work.'
            Write-Host '  To switch to a private key, start once with --new-keys, then enter your provider and'
            Write-Host '  connector keys again on the Model routing and Connectors pages.'
            Write-Host '  If you deleted infra\launcher\.env from a newer install, restore it from a backup'
            Write-Host '  instead: the database password and the encryption key in it cannot be recreated.'
        } else {
            # The .bat asks the question on a first start and passes the answer as a flag.
            $demo = if ($null -ne $demoChoice) { $demoChoice } else { 'true' }
            Write-NewEnv 'launcher-1' (New-RandomBase64) (New-RandomHex) (New-RandomHex) $demo $demo
            Write-Host '  Created private settings in infra\launcher\.env. Keep this file: it holds the encryption key.'
        }
    } elseif ($null -ne $demoChoice -and (Get-EnvValue 'AIWOS_DEMO_ENABLED') -ne $demoChoice) {
        Set-EnvValue 'AIWOS_DEMO_ENABLED' $demoChoice
    }

    if ((Get-EnvValue 'AIWOS_DEMO_ENABLED') -eq 'true' -and (Get-EnvValue 'AIWOS_DEMO_DATA_LOADED') -ne 'true') {
        Set-EnvValue 'AIWOS_DEMO_DATA_LOADED' 'true'
    }

    if ($newKeys) {
        if ((Get-EnvValue 'AIWOS_ENCRYPTION_KEY_ID') -eq $LegacyKeyId -or -not (Get-EnvValue 'AIWOS_ENCRYPTION_MASTER_KEY')) {
            Set-EnvValue 'AIWOS_ENCRYPTION_KEY_ID' 'launcher-1'
            Set-EnvValue 'AIWOS_ENCRYPTION_MASTER_KEY' (New-RandomBase64)
            Set-EnvValue 'AIWOS_SECURITY_DEVELOPMENT_SECRET' (New-RandomHex)
            Write-Host ''
            Write-Host '  New private encryption key written. Keys stored before now can no longer be read:'
            Write-Host '  enter your AI provider and connector keys again on the Model routing and Connectors pages.'
        } else {
            Write-Host '  This installation already has a private encryption key; nothing was changed.'
        }
    }
} catch {
    Write-Host "  $($_.Exception.Message)"
    exit 1
}

# Sharing the app on the network is opt-in, and never with demo sign-ins in the database: they
# share a password that is printed on the screen and published in the source.
if ((ConvertTo-Bool $env:AIWOS_EXPOSE_LAN) -eq 'true') {
    if ((Get-EnvValue 'AIWOS_DEMO_ENABLED') -eq 'true') {
        Write-Host ''
        Write-Host '  Not sharing on the network: demo data is on, and its sign-ins use a published password.'
        Write-Host '  Start with --no-demo to share it, or without AIWOS_EXPOSE_LAN to keep it on this computer.'
        exit 3
    }
    if ((Get-EnvValue 'AIWOS_DEMO_DATA_LOADED') -eq 'true') {
        Write-Host ''
        Write-Host '  Not sharing on the network: demo data was loaded into this installation earlier, so its'
        Write-Host '  demo sign-ins and their published password still exist. To share the app, start again'
        Write-Host "  from empty data: run 'docker compose -f infra\launcher\docker-compose.yml down -v'"
        Write-Host "  (this deletes all of the app's data), delete infra\launcher\.env, then start with --no-demo."
        exit 3
    }
}

exit 0
