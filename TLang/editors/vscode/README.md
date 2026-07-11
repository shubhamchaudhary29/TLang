# TLang VS Code Support

This extension adds syntax highlighting and real-time syntax/semantic diagnostics (via Language Server Protocol) for `.tiny` (TLang) files in VS Code.

## Quick Start / Setup

### 1. Build TLang locally
First, build and package the TLang CLI tool:
```bash
./gradlew installDist
```
This generates the binary executable inside your local build folder:
`build/install/tlang/bin/tlang`

### 2. Install VS Code Extension dependencies
Move to the extension directory:
```bash
cd editors/vscode
npm install
```

### 3. Package and Install Extension
To package the extension into a VSIX archive:
```bash
npx @vscode/vsce package
```
This produces `tlang-vscode-0.0.1.vsix`.

Install it into your running VS Code instance:
```bash
code --install-extension tlang-vscode-0.0.1.vsix
```
*(If you are running in Codium, replace `code` with `codium`).*

### 4. Point Extension to the Local Executable
In your VS Code settings (`settings.json`):
```json
{
  "tlang.executablePath": "/absolute/path/to/TLang/build/install/tlang/bin/tlang"
}
```
Replace `/absolute/path/to/TLang` with the actual absolute path to your TLang directory. After setting this, reload your VS Code window (`Ctrl+R` or run `Developer: Reload Window` from the Command Palette).
