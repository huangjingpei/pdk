param(
    [string]$BackendBaseUrl = "http://127.0.0.1:8080",
    [Parameter(Mandatory = $true)][string]$Phone,
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$DeviceId = "ZHIBO-LIVE-E2E-DEVICE",
    [Parameter(Mandatory = $true)][string]$MediaMtxServiceToken
)

$ErrorActionPreference = "Stop"

function Assert-Equal([object]$Actual, [object]$Expected, [string]$Name) {
    if ($Actual -ne $Expected) {
        throw "$Name failed: expected=$Expected actual=$Actual"
    }
    Write-Host "[PASS] $Name -> $Actual"
}

function Invoke-RawJson([string]$Uri, [hashtable]$Headers, [object]$Body) {
    $params = @{
        Uri = $Uri
        Method = "POST"
        ContentType = "application/json; charset=utf-8"
        Body = ($Body | ConvertTo-Json -Compress)
        SkipHttpErrorCheck = $true
    }
    if ($Headers) { $params.Headers = $Headers }
    Invoke-WebRequest @params
}

$login = Invoke-RestMethod -Uri "$BackendBaseUrl/api/v1/client/auth/login" -Method Post `
    -ContentType "application/json; charset=utf-8" `
    -Body (@{ appId = 3; phone = $Phone; password = $Password; deviceId = $DeviceId } | ConvertTo-Json -Compress)
Assert-Equal $login.code 200 "appId=3 client login"
Assert-Equal $login.data.bizCode "ZHIBO_LIVE" "login business isolation"

$clientHeaders = @{
    "X-PDK-App-ID" = "3"
    "X-PDK-Phone" = $Phone
    "X-PDK-Device-ID" = $DeviceId
}
$clientHeaders[$login.data.tokenName] = $login.data.tokenValue

$requestId = "e2e-" + [Guid]::NewGuid().ToString("N")
$ticketResponse = Invoke-RestMethod -Uri "$BackendBaseUrl/api/v1/client/zhibo-live/publish-tickets" `
    -Method Post -Headers $clientHeaders -ContentType "application/json; charset=utf-8" `
    -Body (@{ clientRequestId = $requestId; title = "MediaMTX auth E2E"; requestedProtocol = "RTMP" } | ConvertTo-Json -Compress)
Assert-Equal $ticketResponse.code 200 "authenticated ticket issuance"

$publishUri = [Uri]$ticketResponse.data.publishUrl
$publishTicket = [System.Web.HttpUtility]::ParseQueryString($publishUri.Query).Get("token")
$streamPath = $publishUri.AbsolutePath.TrimStart("/")
if ([string]::IsNullOrWhiteSpace($publishTicket)) { throw "ticket issuance returned no token" }

$authEndpoint = "$BackendBaseUrl/api/v1/internal/mediamtx/auth?serviceToken=$([Uri]::EscapeDataString($MediaMtxServiceToken))"
$baseAuth = @{ user = ""; password = ""; ip = "127.0.0.1"; action = "publish"; path = $streamPath; protocol = "rtmp"; query = ""; userAgent = "ffmpeg-e2e" }

$withoutTicket = $baseAuth.Clone()
$withoutTicket.id = "direct-ffmpeg-" + [Guid]::NewGuid().ToString("N")
$withoutTicket.token = ""
$response = Invoke-RawJson $authEndpoint @{} $withoutTicket
Assert-Equal $response.StatusCode 401 "direct ffmpeg without login/ticket is denied"

$connectionId = "allowed-" + [Guid]::NewGuid().ToString("N")
$valid = $baseAuth.Clone()
$valid.id = $connectionId
$valid.token = $publishTicket
$response = Invoke-RawJson $authEndpoint @{} $valid
Assert-Equal $response.StatusCode 204 "valid one-time publish ticket is allowed"

$replay = $valid.Clone()
$replay.id = "replay-" + [Guid]::NewGuid().ToString("N")
$response = Invoke-RawJson $authEndpoint @{} $replay
Assert-Equal $response.StatusCode 409 "ticket replay from another connection is denied"

$eventBase = "$BackendBaseUrl/api/v1/internal/mediamtx/events"
$eventBody = @{ serviceToken = $MediaMtxServiceToken; path = $streamPath; sourceId = $connectionId }
$response = Invoke-WebRequest -Uri "$eventBase/available" -Method Post -Body $eventBody -SkipHttpErrorCheck
Assert-Equal $response.StatusCode 204 "runOnAvailable event"

$streams = Invoke-RestMethod -Uri "$BackendBaseUrl/api/v1/client/zhibo-live/streams/current" -Method Get -Headers $clientHeaders
$current = $streams.data | Where-Object { $_.streamSessionNo -eq $ticketResponse.data.streamSessionNo }
Assert-Equal $current.status "LIVE" "stream session enters LIVE"
Assert-Equal $current.billedUnits 1 "LIVE event bills exactly one unit"

$response = Invoke-WebRequest -Uri "$eventBase/unavailable" -Method Post -Body $eventBody -SkipHttpErrorCheck
Assert-Equal $response.StatusCode 204 "runOnUnavailable event"
$streams = Invoke-RestMethod -Uri "$BackendBaseUrl/api/v1/client/zhibo-live/streams/current" -Method Get -Headers $clientHeaders
$current = $streams.data | Where-Object { $_.streamSessionNo -eq $ticketResponse.data.streamSessionNo }
Assert-Equal $current.status "ENDED" "stream session enters ENDED"

Write-Host "ZHIBO_LIVE MediaMTX HTTP authentication E2E passed. Sensitive publish URL was not printed."
