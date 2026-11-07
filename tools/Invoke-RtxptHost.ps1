param(
    [Parameter(Mandatory=$true)][string]$MainClass,
    [string[]]$ApplicationArguments=@(),
    [string]$RuntimeDirectory='build/upstream-rtxpt/runtime/rtxpt_v.1.9.0_binaries/bin',
    [string]$ClasspathFile='build/renderer-validation-classpath.txt',
    [int]$TimeoutSeconds=180
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$runtime=[IO.Path]::GetFullPath($RuntimeDirectory,$repo)
$java=(Get-Command java.exe -CommandType Application).Source
$variables=@{
    RTR_JVM_DLL=(Join-Path (Split-Path (Split-Path $java)) 'bin/server/jvm.dll')
    RTR_JVM_CLASSPATH=([IO.File]::ReadAllText([IO.Path]::GetFullPath($ClasspathFile,$repo))).Trim()
    RTR_JVM_MAIN=$MainClass
    RTR_JVM_ARG_COUNT=[string]$ApplicationArguments.Count
}
$options=@('-Xmx512m','-XX:-UseJVMCICompiler','-XX:CICompilerCount=2','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',('-Dtop.ceroxe.rt.rtxpt.runtime='+$runtime))
$variables.RTR_JVM_OPTION_COUNT=[string]$options.Count
for($i=0;$i -lt $options.Count;$i++){$variables['RTR_JVM_OPTION_'+$i]=$options[$i]}
for($i=0;$i -lt $ApplicationArguments.Count;$i++){$variables['RTR_JVM_ARG_'+$i]=$ApplicationArguments[$i]}
$previous=@{}
try {
    foreach($key in $variables.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$variables[$key],'Process')}
    & (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath (Join-Path $runtime 'rtrenderer_jvm_host.exe') -WorkingDirectory $repo -TimeoutSeconds $TimeoutSeconds
    $code=$LASTEXITCODE
} finally {
    foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
}
exit $code
