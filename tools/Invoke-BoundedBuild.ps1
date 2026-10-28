param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [string[]]$ArgumentList = @(),
    [string]$WorkingDirectory = (Get-Location).Path,
    [ValidateRange(1, 1024)][double]$MemoryLimitGB = 10
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

# Leave 0.5 GB for this PowerShell host within the total build budget.
# A Job Object includes descendants and limits native allocations, unlike Java -Xmx.
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
public static class RendererBoundedBuild {
    [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
        public long ProcessTime, JobTime;
        public uint Flags;
        public UIntPtr MinimumWorkingSet, MaximumWorkingSet;
        public uint ActiveProcesses;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] struct IoCounters {
        public ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
        public BasicLimits Basic;
        public IoCounters Io;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct Startup {
        public int Size;
        public string Reserved, Desktop, Title;
        public int X, Y, Width, Height, XChars, YChars, Fill, Flags;
        public short ShowWindow, ReservedBytes;
        public IntPtr ReservedData, Stdin, Stdout, Stderr;
    }
    [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
        public IntPtr Process, Thread;
        public uint ProcessId, ThreadId;
    }
    [StructLayout(LayoutKind.Sequential)] struct Accounting {
        public long UserTime, KernelTime, PeriodUserTime, PeriodKernelTime;
        public uint PageFaults, TotalProcesses, ActiveProcesses, TerminatedProcesses;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetInformationJobObject(IntPtr job, int info, ref ExtendedLimits limits, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool QueryInformationJobObject(IntPtr job, int info, ref ExtendedLimits limits, uint size, IntPtr length);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool QueryInformationJobObject(IntPtr job, int info, out Accounting accounting, uint size, IntPtr length);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CreateProcess(string app, StringBuilder command, IntPtr processAttributes,
        IntPtr threadAttributes, bool inherit, uint flags, IntPtr environment, string directory,
        ref Startup startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError = true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process, uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int kind);
    static string Quote(string value) {
        var text = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') text.Append('\\', slashes * 2 + 1);
            else text.Append('\\', slashes);
            text.Append(c); slashes = 0;
        }
        return text.Append('\\', slashes * 2).Append('"').ToString();
    }
    public static int Run(string executable, string[] arguments, string directory, double memoryLimitGB) {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero) throw new Win32Exception();
        var process = new ProcessInfo();
        var limits = new ExtendedLimits();
        limits.Basic.Flags = 0x200 | 0x2000; // JOB_MEMORY | KILL_ON_JOB_CLOSE
        limits.JobMemory = new UIntPtr((ulong)((memoryLimitGB - 0.5) * 1e9));
        uint size = (uint)Marshal.SizeOf(typeof(ExtendedLimits));
        try {
            if (!SetInformationJobObject(job, 9, ref limits, size)) throw new Win32Exception();
            var command = new StringBuilder(Quote(executable));
            foreach (string arg in arguments) command.Append(' ').Append(Quote(arg));
            var startup = new Startup();
            startup.Size = Marshal.SizeOf(typeof(Startup));
            startup.Flags = 0x100;
            startup.Stdin = GetStdHandle(-10); startup.Stdout = GetStdHandle(-11); startup.Stderr = GetStdHandle(-12);
            if (!CreateProcess(executable, command, IntPtr.Zero, IntPtr.Zero, true, 0x08000004,
                    IntPtr.Zero, directory, ref startup, out process)) throw new Win32Exception();
            if (!AssignProcessToJobObject(job, process.Process)) {
                int error = Marshal.GetLastWin32Error();
                TerminateProcess(process.Process, 1);
                throw new Win32Exception(error);
            }
            Console.WriteLine("Bounded build: process-tree native/heap commit cap={0:F1} GB; host reserve=0.5 GB", memoryLimitGB - 0.5);
            var timer = Stopwatch.StartNew();
            if (ResumeThread(process.Thread) == uint.MaxValue) throw new Win32Exception();
            while (WaitForSingleObject(process.Process, 1000) == 258) { }
            timer.Stop();
            uint exit;
            if (!GetExitCodeProcess(process.Process, out exit)) throw new Win32Exception();
            if (QueryInformationJobObject(job, 9, ref limits, size, IntPtr.Zero))
                Console.WriteLine("Bounded build peak process-tree commit: {0:F3} GB; exit={1}",
                    limits.PeakJobMemory.ToUInt64() / 1e9, exit);
            Accounting accounting;
            if (QueryInformationJobObject(job, 1, out accounting, (uint)Marshal.SizeOf(typeof(Accounting)), IntPtr.Zero)) {
                double cpuSeconds = (accounting.UserTime + accounting.KernelTime) / 1e7;
                Console.WriteLine("Bounded build timing: wall={0:F3}s; tree CPU={1:F3}s; average busy cores={2:F2}",
                    timer.Elapsed.TotalSeconds, cpuSeconds, cpuSeconds / Math.Max(timer.Elapsed.TotalSeconds, 0.001));
            }
            return unchecked((int)exit);
        } finally {
            if (process.Thread != IntPtr.Zero) CloseHandle(process.Thread);
            if (process.Process != IntPtr.Zero) CloseHandle(process.Process);
            CloseHandle(job);
        }
    }
}
'@
$buildExecutable = (Get-Command $FilePath -CommandType Application).Source
exit [RendererBoundedBuild]::Run($buildExecutable, $ArgumentList, $WorkingDirectory, $MemoryLimitGB)
