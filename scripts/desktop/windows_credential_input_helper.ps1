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

    private delegate bool EnumChildProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct GUITHREADINFO
    {
        public int cbSize;
        public uint flags;
        public IntPtr hwndActive;
        public IntPtr hwndFocus;
        public IntPtr hwndCapture;
        public IntPtr hwndMenuOwner;
        public IntPtr hwndMoveSize;
        public IntPtr hwndCaret;
        public RECT rcCaret;
    }

    private sealed class PasswordTarget
    {
        public AutomationElement AutomationElement { get; private set; }
        public IntPtr NativeHwnd { get; private set; }
        public bool IsNative { get { return NativeHwnd != IntPtr.Zero; } }

        public static PasswordTarget FromAutomation(AutomationElement element)
        {
            return new PasswordTarget { AutomationElement = element, NativeHwnd = IntPtr.Zero };
        }

        public static PasswordTarget FromNative(IntPtr hwnd)
        {
            return new PasswordTarget { AutomationElement = null, NativeHwnd = hwnd };
        }
    }

    [DllImport("user32.dll")]
    private static extern bool EnumChildWindows(IntPtr hWndParent, EnumChildProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool IsChild(IntPtr hWndParent, IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool IsWindowEnabled(IntPtr hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassNameW(IntPtr hWnd, System.Text.StringBuilder lpClassName, int nMaxCount);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW", SetLastError = true)]
    private static extern int GetWindowLong32(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
    private static extern IntPtr GetWindowLongPtr64(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr SendMessageTimeoutW(
        IntPtr hWnd,
        uint Msg,
        IntPtr wParam,
        IntPtr lParam,
        uint fuFlags,
        uint uTimeout,
        out UIntPtr lpdwResult);

    [DllImport("user32.dll")]
    private static extern uint GetCurrentThreadId();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetFocus(IntPtr hWnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetGUIThreadInfo(uint idThread, ref GUITHREADINFO lpgui);

    private const int GWL_STYLE = -16;
    private const long ES_MULTILINE = 0x0004L;
    private const long ES_PASSWORD = 0x0020L;
    private const uint EM_GETPASSWORDCHAR = 0x00D2;
    private const uint SMTO_ABORTIFHUNG = 0x0002;

    private static long GetWindowStyle(IntPtr hwnd)
    {
        return IntPtr.Size == 8 ? GetWindowLongPtr64(hwnd, GWL_STYLE).ToInt64() : GetWindowLong32(hwnd, GWL_STYLE);
    }

    private static bool IsSupportedNativeEditClass(IntPtr hwnd)
    {
        var name = new System.Text.StringBuilder(256);
        if (GetClassNameW(hwnd, name, name.Capacity) <= 0)
        {
            return false;
        }
        string value = name.ToString();
        return string.Equals(value, "Edit", StringComparison.OrdinalIgnoreCase)
            || value.StartsWith("WindowsForms10.EDIT.", StringComparison.OrdinalIgnoreCase);
    }

    private static bool HasNativePasswordMask(IntPtr hwnd)
    {
        UIntPtr result;
        IntPtr call = SendMessageTimeoutW(
            hwnd,
            EM_GETPASSWORDCHAR,
            IntPtr.Zero,
            IntPtr.Zero,
            SMTO_ABORTIFHUNG,
            100,
            out result);
        return call != IntPtr.Zero && result.ToUInt64() != 0;
    }

    private static bool IsNativePasswordControl(long rootHwnd, IntPtr candidate, int expectedProcessId)
    {
        IntPtr root = new IntPtr(rootHwnd);
        if (candidate == IntPtr.Zero || candidate == root || !IsChild(root, candidate))
        {
            return false;
        }
        if (!IsWindowVisible(candidate) || !IsWindowEnabled(candidate) || !IsSupportedNativeEditClass(candidate))
        {
            return false;
        }
        uint actualProcessId;
        if (GetWindowThreadProcessId(candidate, out actualProcessId) == 0 || actualProcessId != (uint)expectedProcessId)
        {
            return false;
        }
        long style = GetWindowStyle(candidate);
        if ((style & ES_PASSWORD) == 0 || (style & ES_MULTILINE) != 0)
        {
            return false;
        }
        return HasNativePasswordMask(candidate);
    }

    private static System.Collections.Generic.List<IntPtr> FindNativePasswordControls(long hwnd, int expectedProcessId)
    {
        var matches = new System.Collections.Generic.List<IntPtr>();
        IntPtr root = new IntPtr(hwnd);
        EnumChildProc callback = delegate(IntPtr candidate, IntPtr ignored)
        {
            if (IsNativePasswordControl(hwnd, candidate, expectedProcessId))
            {
                matches.Add(candidate);
                if (matches.Count > 1)
                {
                    return false;
                }
            }
            return true;
        };
        EnumChildWindows(root, callback, IntPtr.Zero);
        return matches;
    }

    private static uint RequireNativePasswordThread(long hwnd, int expectedProcessId, IntPtr candidate, int delivered)
    {
        RequireWindowProcess(hwnd, expectedProcessId);
        if (GetForegroundWindow().ToInt64() != hwnd || !IsNativePasswordControl(hwnd, candidate, expectedProcessId))
        {
            throw new InvalidOperationException("CREDENTIAL_FOCUS_CHANGED:" + delivered);
        }
        uint actualProcessId;
        uint threadId = GetWindowThreadProcessId(candidate, out actualProcessId);
        if (threadId == 0 || actualProcessId != (uint)expectedProcessId)
        {
            throw new InvalidOperationException("CREDENTIAL_PROCESS_CHANGED:" + delivered);
        }
        return threadId;
    }

    private static void RequireNativePasswordFocus(long hwnd, int expectedProcessId, IntPtr candidate, int delivered)
    {
        uint threadId = RequireNativePasswordThread(hwnd, expectedProcessId, candidate, delivered);
        var info = new GUITHREADINFO();
        info.cbSize = Marshal.SizeOf<GUITHREADINFO>();
        if (!GetGUIThreadInfo(threadId, ref info) || info.hwndFocus != candidate)
        {
            throw new InvalidOperationException("CREDENTIAL_FOCUS_CHANGED:" + delivered);
        }
    }

    private static void FocusNativePassword(long hwnd, int expectedProcessId, IntPtr candidate)
    {
        uint targetThreadId = RequireNativePasswordThread(hwnd, expectedProcessId, candidate, 0);
        uint currentThreadId = GetCurrentThreadId();
        bool attached = false;
        try
        {
            if (currentThreadId != targetThreadId)
            {
                if (!AttachThreadInput(currentThreadId, targetThreadId, true))
                {
                    throw new InvalidOperationException("CREDENTIAL_FOCUS_CHANGED:0");
                }
                attached = true;
            }
            SetFocus(candidate);
        }
        finally
        {
            if (attached && !AttachThreadInput(currentThreadId, targetThreadId, false))
            {
                throw new InvalidOperationException("CREDENTIAL_FOCUS_CHANGED:0");
            }
        }
        Thread.Sleep(50);
        RequireNativePasswordFocus(hwnd, expectedProcessId, candidate, 0);
    }

    private static PasswordTarget RequireFocusedPassword(long hwnd, int expectedProcessId)
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
                    return PasswordTarget.FromAutomation(focused);
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

    private static PasswordTarget FocusUniquePassword(long hwnd, int expectedProcessId)
    {
        RequireWindowProcess(hwnd, expectedProcessId);
        if (GetForegroundWindow().ToInt64() != hwnd)
        {
            throw new InvalidOperationException("FOREGROUND_CHANGED:0");
        }

        AutomationElement root = AutomationElement.FromHandle(new IntPtr(hwnd));
        AutomationElement selected = null;
        int visibleMatches = 0;
        if (root != null)
        {
            Condition condition = new AndCondition(
                new PropertyCondition(AutomationElement.IsPasswordProperty, true),
                new PropertyCondition(AutomationElement.IsEnabledProperty, true)
            );
            AutomationElementCollection matches = root.FindAll(TreeScope.Descendants, condition);
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
        }

        if (visibleMatches > 1)
        {
            throw new InvalidOperationException("CREDENTIAL_PASSWORD_TARGET_AMBIGUOUS:0");
        }
        if (visibleMatches == 1 && selected != null)
        {
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
            return PasswordTarget.FromAutomation(selected);
        }

        System.Collections.Generic.List<IntPtr> nativeMatches = FindNativePasswordControls(hwnd, expectedProcessId);
        if (nativeMatches.Count != 1)
        {
            throw new InvalidOperationException("CREDENTIAL_PASSWORD_TARGET_AMBIGUOUS:0");
        }
        IntPtr native = nativeMatches[0];
        FocusNativePassword(hwnd, expectedProcessId, native);
        return PasswordTarget.FromNative(native);
    }

    private static void RequireSameFocus(long hwnd, int expectedProcessId, PasswordTarget expected, int delivered)
    {
        RequireWindowProcess(hwnd, expectedProcessId);
        if (GetForegroundWindow().ToInt64() != hwnd)
        {
            throw new InvalidOperationException("FOREGROUND_CHANGED:" + delivered);
        }
        if (expected.IsNative)
        {
            RequireNativePasswordFocus(hwnd, expectedProcessId, expected.NativeHwnd, delivered);
            return;
        }
        AutomationElement current = AutomationElement.FocusedElement;
        if (current == null || expected.AutomationElement == null || !Automation.Compare(expected.AutomationElement, current))
        {
            throw new InvalidOperationException("CREDENTIAL_FOCUS_CHANGED:" + delivered);
        }
    }

    public static void Send(long hwnd, int expectedProcessId, string credentialTarget, int maxUnits, bool focusUniquePassword)
    {
        PasswordTarget expectedFocus = focusUniquePassword
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
