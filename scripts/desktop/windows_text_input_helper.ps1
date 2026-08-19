$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$utf8 = [System.Text.UTF8Encoding]::new($false)

function Write-JsonBytes {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][System.IO.Stream]$Stream
    )
    $json = ($Value | ConvertTo-Json -Compress -Depth 6) + "`n"
    $bytes = $utf8.GetBytes($json)
    $Stream.Write($bytes, 0, $bytes.Length)
    $Stream.Flush()
}

function Fail-Helper {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Message,
        [hashtable]$Data = @{}
    )
    Write-JsonBytes -Value ([ordered]@{ code = $Code; message = $Message; data = $Data }) -Stream ([Console]::OpenStandardError())
    exit 2
}

$stage = 'bootstrap'
try {
    $stdin = [Console]::OpenStandardInput()
    $memory = [System.IO.MemoryStream]::new()
    $buffer = New-Object byte[] 8192
    while (($read = $stdin.Read($buffer, 0, $buffer.Length)) -gt 0) {
        if (($memory.Length + $read) -gt 65536) {
            Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'text input helper request exceeds 64 KiB'
        }
        $memory.Write($buffer, 0, $read)
    }
    $raw = $utf8.GetString($memory.ToArray())
    $request = $raw | ConvertFrom-Json -ErrorAction Stop
    if ($null -eq $request) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'text input helper request is empty'
    }
    $names = @($request.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    if ($names.Count -ne 2 -or $names[0] -ne 'hwnd' -or $names[1] -ne 'text') {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'text input helper request shape is invalid'
    }
    if ($request.hwnd -is [bool]) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'hwnd must be a positive integer'
    }
    $hwnd = [long]$request.hwnd
    if ($hwnd -le 0) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'hwnd must be a positive integer'
    }
    if ($request.text -isnot [string]) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'text must be a string'
    }
    $text = [string]$request.text
    if ([string]::IsNullOrEmpty($text) -or $text.Length -gt 16384) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'text length is invalid'
    }

    $stage = 'validated'
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Threading;

public static class HooshiXDesktopUnicodeInput
{
    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public UIntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit, Size = 32)]
    public struct INPUTUNION
    {
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT
    {
        public uint type;
        public INPUTUNION U;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    private static INPUT Event(char value, bool keyUp)
    {
        var input = new INPUT();
        input.type = INPUT_KEYBOARD;
        input.U.ki.wVk = 0;
        input.U.ki.wScan = value;
        input.U.ki.dwFlags = KEYEVENTF_UNICODE | (keyUp ? KEYEVENTF_KEYUP : 0);
        return input;
    }

    public static int Send(long hwnd, string inputText)
    {
        string text = inputText.Replace("\r\n", "\r").Replace("\n", "\r");
        int delivered = 0;
        foreach (char value in text)
        {
            if (GetForegroundWindow().ToInt64() != hwnd)
            {
                throw new InvalidOperationException("FOREGROUND_CHANGED:" + delivered);
            }

            var pair = new INPUT[] { Event(value, false), Event(value, true) };
            uint sent = SendInput((uint)pair.Length, pair, Marshal.SizeOf<INPUT>());
            if (sent != pair.Length)
            {
                if (sent == 1)
                {
                    try
                    {
                        var release = new INPUT[] { Event(value, true) };
                        SendInput(1, release, Marshal.SizeOf<INPUT>());
                    }
                    catch { }
                }
                string code = sent == 0 ? "TEXT_INPUT_DENIED" : "TEXT_INPUT_PARTIAL";
                throw new InvalidOperationException(code + ":" + delivered);
            }

            delivered++;
            Thread.Sleep(5);
        }

        Thread.Sleep(500);
        return delivered;
    }
}
'@
    $stage = 'compiled'

    try {
        $delivered = [HooshiXDesktopUnicodeInput]::Send($hwnd, $text)
        $stage = 'sent'
    }
    catch {
        $exception = $_.Exception
        while ($null -ne $exception.InnerException) { $exception = $exception.InnerException }
        $safe = [string]$exception.Message
        if ($safe -match '^(FOREGROUND_CHANGED|TEXT_INPUT_DENIED|TEXT_INPUT_PARTIAL):(\d+)$') {
            $code = $Matches[1]
            $deliveredUnits = [int]$Matches[2]
            Fail-Helper -Code $code -Message 'Windows text input did not complete safely' -Data @{
                partial_input_possible = ($deliveredUnits -gt 0)
                delivered_code_units = $deliveredUnits
            }
        }
        Fail-Helper -Code 'TEXT_INPUT_HELPER_FAILED' -Message 'Windows text input helper failed'
    }

    $stage = 'report'
    Write-JsonBytes -Value ([ordered]@{
        utf16_code_units = $delivered
        chunks = $delivered
        settle_ms = 500
    }) -Stream ([Console]::OpenStandardOutput())
    exit 0
}
catch {
    Fail-Helper -Code 'TEXT_INPUT_HELPER_FAILED' -Message 'Windows text input helper failed' -Data @{ stage = $stage; exception_type = $_.Exception.GetType().FullName }
}
