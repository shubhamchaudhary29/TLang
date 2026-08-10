$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cli = Join-Path $root 'build\install\tlang\bin\tlang.bat'
$expected = (Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^version=' }) -replace '^version=', ''
if (!(Test-Path $cli)) { throw 'Distribution launcher is missing.' }
if ((& $cli version) -ne "TLang version $expected") { throw 'Unexpected CLI version.' }
if ((& $cli help) -notcontains '  tlang run <file>') { throw 'Help output is incomplete.' }
$work = Join-Path ([IO.Path]::GetTempPath()) ('TLang smoke ' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $program = Join-Path $work 'hello world.tiny'
    Set-Content -NoNewline -Path $program -Value 'show "Hello, World!"'
    if ((& $cli run $program) -ne 'Hello, World!') { throw 'Hello World failed.' }
    if ((& $cli run (Join-Path $root 'examples\hello.tiny')) -ne 'Hello from TLang!') { throw 'Example failed.' }
} finally {
    Remove-Item -Recurse -Force $work
}
Write-Output 'Windows distribution smoke test passed.'
