[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Server,

    [ValidateRange(0, 65535)]
    [int]$LocalPort = 0,

    [ValidateRange(1, 65535)]
    [int]$RemotePort = 8765,
    [switch]$OpenBrowser,

    [switch]$CopyToken,

    [ValidateNotNullOrEmpty()]
    [string]$RemoteTokenPath,

    [ValidateSet('Posix', 'Windows')]
    [string]$RemoteTokenShell = 'Posix',

    [ValidateRange(5, 300)]
    [int]$ConnectTimeoutSeconds = 90
)

$ssh = Get-Command ssh -ErrorAction SilentlyContinue
if ($null -eq $ssh) {
    throw 'OpenSSH não foi encontrado. Instale/ative o cliente OpenSSH do Windows e tente novamente.'
}
if ($Server.StartsWith('-') -or $Server -match '[\r\n]') {
    throw 'Informe um alias SSH ou usuario@servidor, sem opções de linha de comando.'
}

function Select-LocalPort {
    param([int]$RequestedPort)

    if ($RequestedPort -gt 0) {
        $candidates = @($RequestedPort)
    } else {
        $candidates = @(8765) + @(18765..18799)
    }

    foreach ($candidate in $candidates) {
        $listener = $null
        try {
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $candidate)
            $listener.Start()
            return [int]$candidate
        } catch [System.Net.Sockets.SocketException] {
            if ($RequestedPort -gt 0) {
                throw "A porta local $RequestedPort já está ocupada. Feche o processo que a usa ou execute novamente sem -LocalPort para selecionar outra porta automaticamente."
            }
        } finally {
            if ($null -ne $listener) { $listener.Stop() }
        }
    }

    throw 'Não encontrei uma porta local livre entre 8765 e 18799. Feche outro túnel administrativo e tente novamente.'
}

$LocalPort = Select-LocalPort -RequestedPort $LocalPort
if ($CopyToken -and [string]::IsNullOrWhiteSpace($RemoteTokenPath)) {
    throw 'Informe -RemoteTokenPath junto com -CopyToken.'
}
if (-not $CopyToken -and -not [string]::IsNullOrWhiteSpace($RemoteTokenPath)) {
    throw 'Use -CopyToken para buscar o token por SSH.'
}
if ($CopyToken -and $RemoteTokenPath -match '[\r\n"]') {
    throw 'O caminho remoto não pode conter quebras de linha ou aspas duplas.'
}

$forward = '127.0.0.1:' + $LocalPort + ':127.0.0.1:' + $RemotePort
$url = "http://127.0.0.1:${LocalPort}"

function Test-AdminPanel {
    param([string]$Address)

    $request = [System.Net.HttpWebRequest]::Create($Address)
    $request.Method = 'GET'
    $request.Proxy = $null
    $request.Timeout = 1500
    $request.ReadWriteTimeout = 1500
    $response = $null
    $reader = $null

    try {
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200) { return $false }
        $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
        $html = $reader.ReadToEnd()
        return $html.Contains('<title>Cobbleverse Control</title>')
    } catch {
        return $false
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
    }
}

function Copy-RemoteAdminToken {
    param([string]$HostName, [string]$Path, [string]$Shell)

    if ($Shell -eq 'Windows') {
        $safePath = $Path.Replace("'", "''")
        $readCommand = "powershell.exe -NoProfile -NonInteractive -Command `"[Console]::Write((Get-Content -Raw -LiteralPath '$safePath'))`""
    } else {
        $safePath = $Path.Replace("'", "'\''")
        $readCommand = "cat -- '$safePath'"
    }

    $output = & $ssh.Source -T $HostName $readCommand
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Não foi possível ler o token por SSH (código $exitCode). Confira o caminho remoto e a autenticação."
    }

    $token = ($output -join "`n").Trim()
    if ($token -notmatch '\A[0-9a-fA-F]{64}\z') {
        throw 'O arquivo remoto não contém um token válido de 64 caracteres hexadecimais.'
    }

    Set-Clipboard -Value $token
    Write-Host 'Token copiado para a área de transferência. Cole-o no painel e mantenha-o privado.'
}

$arguments = @(
    '-N', '-T',
    '-o', 'ExitOnForwardFailure=yes',
    '-o', 'ServerAliveInterval=30',
    '-o', 'ServerAliveCountMax=3',
    '-L', $forward,
    $Server
)

Write-Host "Conectando via SSH a $Server pela porta local 127.0.0.1:$LocalPort..."
Write-Host 'Aguarde a confirmação de que o painel respondeu pelo túnel.'
$sshProcess = Start-Process -FilePath $ssh.Source -ArgumentList $arguments -NoNewWindow -PassThru

try {
    $deadline = [DateTime]::UtcNow.AddSeconds($ConnectTimeoutSeconds)
    $connected = $false
    while ([DateTime]::UtcNow -lt $deadline -and -not $sshProcess.HasExited) {
        $sshProcess.Refresh()
        if ($sshProcess.HasExited) { break }
        if (Test-AdminPanel -Address $url) {
            $connected = $true
            break
        }
        Start-Sleep -Milliseconds 750
        $sshProcess.Refresh()
    }

    if (-not $connected) {
        $sshProcess.Refresh()
        if ($sshProcess.HasExited) {
            throw "O SSH encerrou antes do painel responder (código $($sshProcess.ExitCode)). Confira host, chave SSH e porta remota $RemotePort."
        }
        throw "O painel não respondeu em $ConnectTimeoutSeconds segundos. Confira se o servidor está ligado e se a porta remota $RemotePort está ativa."
    }

    Write-Host "Túnel conectado. Painel: $url"
    if ($CopyToken) { Copy-RemoteAdminToken -HostName $Server -Path $RemoteTokenPath -Shell $RemoteTokenShell }
    if ($OpenBrowser) { Start-Process $url }
    Write-Host 'Mantenha esta janela aberta enquanto administra o servidor. Ctrl+C encerra o túnel.'

    while (-not $sshProcess.HasExited) {
        Start-Sleep -Seconds 1
        $sshProcess.Refresh()
    }

    if ($sshProcess.ExitCode -ne 0) {
        throw "O túnel SSH encerrou com código $($sshProcess.ExitCode). Confira host e conexão."
    }
} finally {
    if ($null -ne $sshProcess -and -not $sshProcess.HasExited) {
        Stop-Process -Id $sshProcess.Id -Force -ErrorAction SilentlyContinue
    }
}
