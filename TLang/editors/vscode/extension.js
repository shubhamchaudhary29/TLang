const vscode = require('vscode');
const { LanguageClient } = require('vscode-languageclient/node');

let client;

function activate(context) {
    const config = vscode.workspace.getConfiguration('tlang');
    let executablePath = config.get('executablePath');

    if (!executablePath) {
        executablePath = 'tlang';
    }

    // Configure the server options to launch the local compiled TLang CLI binary with 'lsp' argument
    const serverOptions = {
        run: { command: executablePath, args: ['lsp'] },
        debug: { command: executablePath, args: ['lsp'] }
    };

    // Options to control the language client
    const clientOptions = {
        // Register the server for .tiny files
        documentSelector: [{ scheme: 'file', language: 'tiny' }]
    };

    // Create the language client and start the client.
    client = new LanguageClient(
        'tlangLanguageServer',
        'TLang Language Server',
        serverOptions,
        clientOptions
    );

    // Start the client. This will also launch the server
    client.start();
}

function deactivate() {
    if (!client) {
        return undefined;
    }
    return client.stop();
}

module.exports = {
    activate,
    deactivate
};
