<# Read-only WM_NULL responsiveness sampling for windows owned by one test process tree.
   Does not activate, click, resize or close windows. Run as a hidden helper process. #>
param([Parameter(Mandatory)][int]$RootProcessId,[string]$OutputFile='build/reports/window-responsiveness.jsonl',[int]$IntervalMilliseconds=1000)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class RtrWindowProbe {
    public delegate bool Callback(IntPtr window, IntPtr parameter);
    [DllImport("user32.dll")] public static extern bool EnumWindows(Callback callback, IntPtr parameter);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll",SetLastError=true)] public static extern IntPtr SendMessageTimeout(IntPtr window,uint message,UIntPtr wp,IntPtr lp,uint flags,uint timeout,out UIntPtr result);
    public static List<IntPtr> Windows(HashSet<uint> processes) {
        var windows=new List<IntPtr>(); EnumWindows((w,p)=>{uint pid;GetWindowThreadProcessId(w,out pid);if(processes.Contains(pid)&&IsWindowVisible(w))windows.Add(w);return true;},IntPtr.Zero);return windows;
    }
    public static bool Responds(IntPtr window) {UIntPtr result;return SendMessageTimeout(window,0,UIntPtr.Zero,IntPtr.Zero,3,200,out result)!=IntPtr.Zero;}
}
'@
$destination=[IO.Path]::GetFullPath($OutputFile)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))|Out-Null
$writer=[IO.StreamWriter]::new($destination,$true,[Text.UTF8Encoding]::new($false))
try{
    while(Get-Process -Id $RootProcessId -ErrorAction SilentlyContinue){
        $processes=@(Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId)
        $ids=[Collections.Generic.HashSet[uint32]]::new();[void]$ids.Add([uint32]$RootProcessId)
        do{$changed=$false;foreach($process in $processes){if($ids.Contains([uint32]$process.ParentProcessId)){$changed=$ids.Add([uint32]$process.ProcessId)-or $changed}}}while($changed)
        foreach($window in [RtrWindowProbe]::Windows($ids)){
            $owner=0u;[void][RtrWindowProbe]::GetWindowThreadProcessId($window,[ref]$owner)
            $clock=[Diagnostics.Stopwatch]::StartNew();$responsive=[RtrWindowProbe]::Responds($window);$clock.Stop()
            $writer.WriteLine((@{utc=[DateTime]::UtcNow.ToString('o');process=$owner;window=$window.ToInt64();responsive=$responsive;probeMilliseconds=$clock.Elapsed.TotalMilliseconds}|ConvertTo-Json -Compress));$writer.Flush()
        }
        Start-Sleep -Milliseconds $IntervalMilliseconds
    }
}finally{$writer.Dispose()}
