const { spawn } = require('child_process');
const path = require('path');

const tlangPath = path.join(__dirname, '../build/install/tlang/bin/tlang');
const tlang = spawn(tlangPath, ['lsp']);

let buffer = '';

tlang.stderr.on('data', (data) => {
    console.error('[Server Err]', data.toString().trim());
});

function send(msg) {
    const json = JSON.stringify(msg);
    const header = `Content-Length: ${Buffer.byteLength(json, 'utf8')}\r\n\r\n`;
    tlang.stdin.write(header + json);
}

let receivedMessages = [];
let pendingLength = -1;

tlang.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    while (true) {
        if (pendingLength === -1) {
            const index = buffer.indexOf('\r\n\r\n');
            if (index === -1) break;
            const header = buffer.substring(0, index);
            const match = header.match(/Content-Length:\s*(\d+)/i);
            if (!match) {
                console.error('Invalid header:', header);
                process.exit(1);
            }
            pendingLength = parseInt(match[1], 10);
            buffer = buffer.substring(index + 4);
        }
        if (buffer.length < pendingLength) break;
        const body = buffer.substring(0, pendingLength);
        buffer = buffer.substring(pendingLength);
        pendingLength = -1;
        
        const msg = JSON.parse(body);
        receivedMessages.push(msg);
        handleMessage(msg);
    }
});

function handleMessage(msg) {
    if (msg.method === 'textDocument/publishDiagnostics') {
        console.log('\n--- Received Diagnostics ---');
        console.log(`URI: ${msg.params.uri}`);
        console.log(`Diagnostics count: ${msg.params.diagnostics.length}`);
        msg.params.diagnostics.forEach((d, idx) => {
            console.log(` [${idx}] Severity: ${d.severity === 1 ? 'Error' : d.severity}`);
            console.log(`     Message:  "${d.message}"`);
            console.log(`     Source:   "${d.source}"`);
            console.log(`     Range:    Line ${d.range.start.line}:${d.range.start.character} -> Line ${d.range.end.line}:${d.range.end.character}`);
        });
        console.log('----------------------------');
        
        triggerNextStep();
    } else if (msg.id === 1) {
        console.log('Server Initialized successfully.');
        triggerNextStep();
    }
}

let step = 0;
function triggerNextStep() {
    step++;
    if (step === 1) {
        // Send initialize
        send({
            jsonrpc: '2.0',
            id: 1,
            method: 'initialize',
            params: {
                processId: process.pid,
                rootUri: 'file://' + path.join(__dirname, '..'),
                capabilities: {}
            }
        });
    } else if (step === 2) {
        console.log('\nStep 1: Open file with undefined variable "foo"');
        send({
            jsonrpc: '2.0',
            method: 'textDocument/didOpen',
            params: {
                textDocument: {
                    uri: 'file://' + path.join(__dirname, 'test.tiny'),
                    languageId: 'tiny',
                    version: 1,
                    text: 'show foo\n'
                }
            }
        });
    } else if (step === 3) {
        console.log('\nStep 2: Correcting the error by declaring "foo"');
        send({
            jsonrpc: '2.0',
            method: 'textDocument/didChange',
            params: {
                textDocument: {
                    uri: 'file://' + path.join(__dirname, 'test.tiny'),
                    version: 2
                },
                contentChanges: [{
                    text: 'let foo be 42\nshow foo\n'
                }]
            }
        });
    } else if (step === 4) {
        console.log('\nStep 3: Creating two simultaneous undefined variable errors ("foo" and "bar")');
        send({
            jsonrpc: '2.0',
            method: 'textDocument/didChange',
            params: {
                textDocument: {
                    uri: 'file://' + path.join(__dirname, 'test.tiny'),
                    version: 3
                },
                contentChanges: [{
                    text: 'show foo\nshow bar\n'
                }]
            }
        });
    } else if (step === 5) {
        console.log('\nVerification complete. Exiting.');
        send({
            jsonrpc: '2.0',
            method: 'shutdown'
        });
        setTimeout(() => {
            send({ jsonrpc: '2.0', method: 'exit' });
            process.exit(0);
        }, 100);
    }
}

// Start
triggerNextStep();
