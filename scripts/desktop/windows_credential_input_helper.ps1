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
    $buffer = New-Object byte[] 4096
    while (($read = $stdin.Read($buffer, 0, $buffer.Length)) -gt 0) {
        if (($memory.Length + $read) -gt 8192) {
            Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'credential input helper request exceeds 8 KiB'
        }
        $memory.Write($buffer, 0, $read)
    }
    $raw = $utf8.GetString($memory.ToArray())
    $request = $raw | ConvertFrom-Json -ErrorAction Stop
    if ($null -eq $request) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'credential input helper request is empty'
    }
    $names = @($request.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    if ($names.Count -ne 5 -or $names[0] -ne 'credential_target' -or $names[1] -ne 'expected_process_id' -or $names[2] -ne 'focus_unique_password' -or $names[3] -ne 'hwnd' -or $names[4] -ne 'max_utf16_code_units') {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'credential input helper request shape is invalid'
    }
    if ($request.hwnd -is [bool]) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'hwnd must be a positive integer'
    }
    $hwnd = [long]$request.hwnd
    if ($hwnd -le 0) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'hwnd must be a positive integer'
    }
    if ($request.expected_process_id -is [bool]) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'expected_process_id must be a positive integer'
    }
    $expectedProcessId = [int]$request.expected_process_id
    if ($expectedProcessId -le 0) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'expected_process_id must be a positive integer'
    }
    if ($request.focus_unique_password -isnot [bool]) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'focus_unique_password must be boolean'
    }
    $focusUniquePassword = [bool]$request.focus_unique_password
    if ($request.credential_target -isnot [string]) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'credential target must be a string'
    }
    $credentialTarget = [string]$request.credential_target
    if ([string]::IsNullOrWhiteSpace($credentialTarget) -or $credentialTarget.Length -gt 256 -or $credentialTarget -match '[\x00-\x1f\x7f]') {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'credential target is invalid'
    }
    if ($request.max_utf16_code_units -is [bool]) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'credential input bound is invalid'
    }
    $maxUnits = [int]$request.max_utf16_code_units
    if ($maxUnits -lt 1 -or $maxUnits -gt 256) {
        Fail-Helper -Code 'INVALID_ARGUMENT' -Message 'credential input bound is invalid'
    }

    $stage = 'validated'
    $runtimeDirectory = [System.Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()
    $uiaClientAssembly = Join-Path $runtimeDirectory 'WPF\UIAutomationClient.dll'
    $uiaTypesAssembly = Join-Path $runtimeDirectory 'WPF\UIAutomationTypes.dll'
    if (-not (Test-Path -LiteralPath $uiaClientAssembly -PathType Leaf) -or -not (Test-Path -LiteralPath $uiaTypesAssembly -PathType Leaf)) {
        Fail-Helper -Code 'CREDENTIAL_INPUT_UNAVAILABLE' -Message 'Windows UI Automation runtime is unavailable'
    }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Automation;

public static class HooshiXDesktopCredentialInput
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CREDENTIAL
    {
        public UInt32 Flags;
        public UInt32 Type;
        public IntPtr TargetName;
        public IntPtr Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public UInt32 CredentialBlobSize;
        public IntPtr CredentialBlob;
        public UInt32 Persist;
        public UInt32 AttributeCount;
        public IntPtr Attributes;
        public IntPtr TargetAlias;
        public IntPtr UserName;
    }

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

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredReadW(string target, uint type, uint flags, out IntPtr credentialPtr);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    private const uint CRED_TYPE_GENERIC = 1;
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

    private static void RequireWindowProcess(long hwnd, int expectedProcessId)
    {
        uint actualProcessId;
        if (GetWindowThreadProcessId(new IntPtr(hwnd), out actualProcessId) == 0 || actualProcessId != (uint)expectedProcessId)
        {
            throw new InvalidOperationException("CREDENTIAL_PROCESS_CHANGED:0");
        }
    }

    private static AutomationElement RequireFocusedPassword(long hwnd, int expectedProcessId)
    {
        RequireWindowProcess(hwnd, expectedProcessId);
        if (GetForegroundWindow().ToInt64() != hwnd)
        {
            throw new InvalidOperationException("FOREGROUND_CHANGED:0");
        }
        AutomationElement focused = AutomationElement.FocusedElement;
        if (focused == null)
        {
            throw new InvalidOperationException("CREDENTIAL_TARGET_NOT_PASSWORD:0");
        }
        bool isPassword;
        try
        {
            isPassword = focused.Current.IsPassword;
        }
        catch
        {
            throw new InvalidOperationException("CREDENTIAL_TARGET_NOT_PASSWORD:0");
        }
        if (!isPassword)
        {
            throw new InvalidOperationException("CREDENTIAL_TARGET_NOT_PASSWORD:0");
        }

        AutomationElement cursor = focused;
        while (cursor != null)
        {
            try
            {
                int native = cursor.Current.NativeWindowHandle;
                if (native != 0 && native == hwnd)
                {
                    return focused;
                }
            }
            catch { }
            try
            {
                cursor = TreeWalker.RawViewWalker.GetParent(cursor);
            }
            catch
            {
                cursor = null;
            }
        }
        throw new InvalidOperationException("CREDENTIAL_TARGET_NOT_PASSWORD:0");
    }

    private static AutomationElement FocusUniquePassword(long hwnd, int expectedProcessId)
    {
        RequireWindowProcess(hwnd, expectedProcessId);
        if (GetForegroundWindow().ToInt64() != hwnd)
        {
            throw new InvalidOperationException("FOREGROUND_CHANGED:0");
        }
        AutomationElement root = AutomationElement.FromHandle(new IntPtr(hwnd));
        if (root == null)
        {
            throw new InvalidOperationException("CREDENTIAL_PASSWORD_TARGET_AMBIGUOUS:0");
        }
        Condition condition = new AndCondition(
            new PropertyCondition(AutomationElement.IsPasswordProperty, true),
            new PropertyCondition(AutomationElement.IsEnabledProperty, true)
        );
        AutomationElementCollection matches = root.FindAll(TreeScope.Descendants, condition);
        AutomationElement selected = null;
        int visibleMatches = 0;
        for (int index = 0; index < matches.Count; index++)
        {
            AutomationElement candidate = matches[index];
            bool offscreen;
            try { offscreen = candidate.Current.IsOffscreen; } catch { continue; }
            if (offscreen) { continue; }
            selected = candidate;
            visibleMatches++;
            if (visibleMatches > 1) { break; }
        }
        if (visibleMatches != 1 || selected == null)
        {
            throw new InvalidOperationException("CREDENTIAL_PASSWORD_TARGET_AMBIGUOUS:0");
        }
        selected.SetFocus();
        Thread.Sleep(50);
        RequireWindowProcess(hwnd, expectedProcessId);
        if (GetForegroundWindow().ToInt64() != hwnd)
        {
            throw new InvalidOperationException("FOREGROUND_CHANGED:0");
        }
        AutomationElement current = AutomationElement.FocusedElement;
        if (current == null || !Automation.Compare(selected, current))
        {
            throw new InvalidOperationException("CREDENTIAL_FOCUS_CHANGED:0");
        }
        return selected;
    }

    private static void RequireSameFocus(long hwnd, int expectedProcessId, AutomationElement expected, int delivered)
    {
        RequireWindowProcess(hwnd, expectedProcessId);
        if (GetForegroundWindow().ToInt64() != hwnd)
        {
            throw new InvalidOperationException("FOREGROUND_CHANGED:" + delivered);
        }
        AutomationElement current = AutomationElement.FocusedElement;
        if (current == null || !Automation.Compare(expected, current))
        {
            throw new InvalidOperationException("CREDENTIAL_FOCUS_CHANGED:" + delivered);
        }
    }

    public static void Send(long hwnd, int expectedProcessId, string credentialTarget, int maxUnits, bool focusUniquePassword)
    {
        AutomationElement expectedFocus = focusUniquePassword
            ? FocusUniquePassword(hwnd, expectedProcessId)
            : RequireFocusedPassword(hwnd, expectedProcessId);
        IntPtr credentialPtr = IntPtr.Zero;
        try
        {
            if (!CredReadW(credentialTarget, CRED_TYPE_GENERIC, 0, out credentialPtr) || credentialPtr == IntPtr.Zero)
            {
                throw new InvalidOperationException("CREDENTIAL_UNAVAILABLE:0");
            }
            CREDENTIAL credential = Marshal.PtrToStructure<CREDENTIAL>(credentialPtr);
            if (credential.CredentialBlob == IntPtr.Zero || credential.CredentialBlobSize == 0 ||
                (credential.CredentialBlobSize % 2) != 0 || credential.CredentialBlobSize > (uint)(maxUnits * 2))
            {
                throw new InvalidOperationException("CREDENTIAL_FORMAT_UNSUPPORTED:0");
            }
            int units = checked((int)credential.CredentialBlobSize / 2);
            for (int index = 0; index < units; index++)
            {
                if ((char)Marshal.ReadInt16(credential.CredentialBlob, index * 2) == '\0')
                {
                    throw new InvalidOperationException("CREDENTIAL_FORMAT_UNSUPPORTED:0");
                }
            }

            int delivered = 0;
            for (int index = 0; index < units; index++)
            {
                RequireSameFocus(hwnd, expectedProcessId, expectedFocus, delivered);
                char value = (char)Marshal.ReadInt16(credential.CredentialBlob, index * 2);
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
                    string code = sent == 0 ? "CREDENTIAL_INPUT_DENIED" : "CREDENTIAL_INPUT_PARTIAL";
                    throw new InvalidOperationException(code + ":" + delivered);
                }
                delivered++;
                Thread.Sleep(5);
            }
            Thread.Sleep(500);
        }
        finally
        {
            if (credentialPtr != IntPtr.Zero)
            {
                CredFree(credentialPtr);
            }
        }
    }
}
'@ -ReferencedAssemblies @($uiaClientAssembly, $uiaTypesAssembly)
    $stage = 'compiled'

    try {
        [HooshiXDesktopCredentialInput]::Send($hwnd, $expectedProcessId, $credentialTarget, $maxUnits, $focusUniquePassword)
        $stage = 'sent'
    }
    catch {
        $exception = $_.Exception
        while ($null -ne $exception.InnerException) { $exception = $exception.InnerException }
        $safe = [string]$exception.Message
        if ($safe -match '^(FOREGROUND_CHANGED|CREDENTIAL_FOCUS_CHANGED|CREDENTIAL_INPUT_DENIED|CREDENTIAL_INPUT_PARTIAL|CREDENTIAL_TARGET_NOT_PASSWORD|CREDENTIAL_PASSWORD_TARGET_AMBIGUOUS|CREDENTIAL_PROCESS_CHANGED|CREDENTIAL_UNAVAILABLE|CREDENTIAL_FORMAT_UNSUPPORTED):(\d+)$') {
            $code = $Matches[1]
            $deliveredUnits = [int]$Matches[2]
            Fail-Helper -Code $code -Message 'Windows credential input did not complete safely' -Data @{
                partial_input_possible = ($deliveredUnits -gt 0)
            }
        }
        Fail-Helper -Code 'CREDENTIAL_INPUT_HELPER_FAILED' -Message 'Windows credential input helper failed'
    }

    $stage = 'report'
    Write-JsonBytes -Value ([ordered]@{
        credential_applied = $true
        settle_ms = 500
    }) -Stream ([Console]::OpenStandardOutput())
    exit 0
}
catch {
    Fail-Helper -Code 'CREDENTIAL_INPUT_HELPER_FAILED' -Message 'Windows credential input helper failed' -Data @{ stage = $stage; exception_type = $_.Exception.GetType().FullName }
}
