param(
    [string]$MySqlHome = 'C:\Program Files\MySQL\MySQL Server 8.0',
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1024, 65535)][int]$Port = 13306
)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$mysqlServer = Join-Path $MySqlHome 'bin\mysqld.exe'
$mysqlClient = Join-Path $MySqlHome 'bin\mysql.exe'
$mysqlAdmin = Join-Path $MySqlHome 'bin\mysqladmin.exe'
foreach ($binary in @($mysqlServer, $mysqlClient, $mysqlAdmin)) {
    if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw "Missing MySQL binary: $binary" }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome with your JDK 17 directory.'
}
$driveName = [System.IO.Path]::GetPathRoot($repoRoot).Substring(0, 1)
if ((Get-PSDrive -Name $driveName).Free -lt 1GB) {
    throw 'At least 1 GB of free space is required before starting the isolated MySQL test instance.'
}
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
try { $listener.Start() } finally { $listener.Stop() }

$runId = [guid]::NewGuid().ToString('N')
$testRoot = Join-Path $repoRoot "target\mysql-pd-$runId"
$dataDirectory = Join-Path $testRoot 'data'
$serverLog = Join-Path $testRoot 'server.log'
$databaseName = "emsafe_pd_test_$runId"
$testPassword = [guid]::NewGuid().ToString('N')
$environmentNames = @('JAVA_HOME', 'MYSQL_PWD', 'DOSIMETRY_MYSQL_URL', 'DOSIMETRY_MYSQL_USERNAME', 'DOSIMETRY_MYSQL_PASSWORD')
$originalEnvironment = @{}
foreach ($name in $environmentNames) { $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$server = $null
$testExitCode = 1

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    & $mysqlServer --no-defaults "--basedir=$MySqlHome" "--datadir=$dataDirectory" --innodb-redo-log-capacity=16777216 --initialize-insecure --console
    if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed.' }
    $serverArguments = @('--no-defaults', "`"--basedir=$MySqlHome`"", "`"--datadir=$dataDirectory`"",
        "--port=$Port", '--bind-address=127.0.0.1', '--mysqlx=0', '--innodb-redo-log-capacity=16777216',
        '--innodb-buffer-pool-size=67108864', "`"--log-error=$serverLog`"")
    $server = Start-Process -FilePath $mysqlServer -ArgumentList $serverArguments -WindowStyle Hidden -PassThru
    $env:MYSQL_PWD = ''
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($server.HasExited) { throw 'The isolated MySQL instance exited during startup. Inspect target/mysql-test-server.log.' }
        & $mysqlAdmin --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root --connect-timeout=1 ping --silent 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'The isolated MySQL instance did not become ready in time.' }
    "ALTER USER 'root'@'localhost' IDENTIFIED BY '$testPassword'; CREATE DATABASE $databaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" |
        & $mysqlClient --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root --connect-timeout=2
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the dedicated test database.' }
    $env:MYSQL_PWD = $testPassword
    $env:JAVA_HOME = $JavaHome
    $env:DOSIMETRY_MYSQL_URL = "jdbc:mysql://127.0.0.1:$Port/${databaseName}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:DOSIMETRY_MYSQL_USERNAME = 'root'
    $env:DOSIMETRY_MYSQL_PASSWORD = $testPassword
    & .\mvnw.cmd -Pmysql-it -B -ntp -l target/mysql-verification.log test
    $testExitCode = $LASTEXITCODE
} finally {
    try {
        if ($null -ne $server -and -not $server.HasExited) {
            try {
                & $mysqlAdmin --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root --connect-timeout=2 shutdown 2>$null
            } finally {
                if (-not $server.WaitForExit(10000)) {
                    Stop-Process -Id $server.Id -Force
                    if (-not $server.WaitForExit(10000)) { throw 'Could not stop the isolated server; its data will be preserved.' }
                }
            }
        }
    } finally {
        foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process') }
        Pop-Location
        if (Test-Path -LiteralPath $serverLog) {
            Copy-Item -LiteralPath $serverLog -Destination (Join-Path $repoRoot 'target\mysql-test-server.log')
        }
        if ((Test-Path -LiteralPath $testRoot) -and ($null -eq $server -or $server.HasExited)) {
            $resolvedTarget = (Resolve-Path -LiteralPath $testRoot).Path
            $expectedTarget = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target\mysql-pd-$runId"))
            if ($resolvedTarget -ne $expectedTarget -or -not $resolvedTarget.StartsWith($repoRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Unexpected MySQL cleanup path; refusing deletion.'
            }
            Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
        }
    }
}
if ($testExitCode -ne 0) { throw "MySQL tests failed (exit $testExitCode). See target/mysql-verification.log." }
Write-Output 'MySQL tests passed. Results: target/mysql-verification.log and target/surefire-reports.'
