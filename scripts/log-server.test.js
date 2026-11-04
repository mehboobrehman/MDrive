const test = require('node:test');
const assert = require('node:assert');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { Readable } = require('stream');

const { handleRequest, DEBUG_TOKEN, setTestLogsDir } = require('./log-server.js');

const TEST_LOGS_DIR = path.join(__dirname, '../logs-test-' + Date.now());
setTestLogsDir(TEST_LOGS_DIR);

function createMockReqRes(method, url, headers = {}, bodyString = '', remoteAddress = '127.0.0.2') {
    const req = Readable.from(bodyString ? [bodyString] : []);
    req.method = method;
    req.url = url;
    req.socket = {
        remoteAddress: remoteAddress,
        destroy: () => {}
    };
    req.headers = {
        'host': 'localhost:8080',
        'x-debug-token': DEBUG_TOKEN,
        ...headers
    };

    let statusCode = 200;
    let responseHeaders = {};
    let responseBody = '';

    const res = {
        setHeader(name, value) {
            responseHeaders[name] = value;
        },
        writeHead(code, headers) {
            statusCode = code;
            if (headers) responseHeaders = { ...responseHeaders, ...headers };
        },
        end(data) {
            if (data) responseBody += data;
        },
        getStatusCode: () => statusCode,
        getHeaders: () => responseHeaders,
        getBody: () => responseBody
    };

    return { req, res };
}

test('Log server accepts valid telemetry envelope with correct auth token', async () => {
    const validEnvelope = {
        schemaVersion: "1.0",
        sessionId: "sess_test_123",
        deviceId: "dev_abc_789",
        sequenceNumber: 1,
        monotonicTimestamp: 123456789,
        wallClockTimestamp: new Date().toISOString(),
        messageType: "LOG",
        payload: {
            level: "INFO",
            category: "EXECUTION",
            event: "TEST_EVENT",
            message: "Hello secure telemetry",
            payload: { key: "value" },
            stack: null
        }
    };

    const { req, res } = createMockReqRes('POST', '/api/log', {}, JSON.stringify(validEnvelope));
    await handleRequest(req, res);

    assert.strictEqual(res.getStatusCode(), 200);
    const jsonRes = JSON.parse(res.getBody());
    assert.strictEqual(jsonRes.status, 'success');

    // Verify multi-device and multi-session namespaced log file exists
    const namespacedLog = path.join(TEST_LOGS_DIR, 'dev_abc_789', 'sess_test_123', 'device-stream.log');
    assert.strictEqual(fs.existsSync(namespacedLog), true);
    const logContent = fs.readFileSync(namespacedLog, 'utf8');
    assert.ok(logContent.includes('Hello secure telemetry'));
});

test('Log server rejects request with missing or invalid auth token', async () => {
    const validEnvelope = {
        schemaVersion: "1.0",
        sessionId: "sess_test_123",
        deviceId: "dev_abc_789",
        sequenceNumber: 2,
        monotonicTimestamp: 123456790,
        wallClockTimestamp: new Date().toISOString(),
        messageType: "LOG",
        payload: { level: "INFO", category: "EXECUTION", event: "TEST", message: "unauthorized test" }
    };

    const { req, res } = createMockReqRes('POST', '/api/log', { 'x-debug-token': 'wrong_token' }, JSON.stringify(validEnvelope));
    await handleRequest(req, res);

    assert.strictEqual(res.getStatusCode(), 401);
    const jsonRes = JSON.parse(res.getBody());
    assert.ok(jsonRes.error.includes('Unauthorized'));
});

test('Log server rejects envelope failing schema validation', async () => {
    const invalidEnvelope = {
        schemaVersion: "2.0", // invalid version
        sessionId: "",        // empty session id
        deviceId: "dev_1",
        // missing required fields
        payload: { level: "INFO" }
    };

    const { req, res } = createMockReqRes('POST', '/api/log', {}, JSON.stringify(invalidEnvelope));
    await handleRequest(req, res);

    assert.strictEqual(res.getStatusCode(), 400);
    const jsonRes = JSON.parse(res.getBody());
    assert.ok(jsonRes.error.includes('Schema Validation Failed'));
});

test('Log server rejects payload exceeding size limit (>1MB)', async () => {
    const largeMessage = 'A'.repeat(1024 * 1025); // 1MB + 1 byte
    const largeEnvelope = {
        schemaVersion: "1.0",
        sessionId: "sess_large",
        deviceId: "dev_large",
        sequenceNumber: 1,
        monotonicTimestamp: 123,
        wallClockTimestamp: new Date().toISOString(),
        messageType: "LOG",
        payload: { level: "INFO", category: "EXECUTION", event: "LARGE", message: largeMessage }
    };

    const { req, res } = createMockReqRes('POST', '/api/log', {}, JSON.stringify(largeEnvelope));
    await handleRequest(req, res);

    assert.strictEqual(res.getStatusCode(), 413);
    const jsonRes = JSON.parse(res.getBody());
    assert.ok(jsonRes.error.includes('Payload Too Large'));
});

test('Log server enforces rate limiting', async () => {
    const validEnvelope = {
        schemaVersion: "1.0",
        sessionId: "sess_rate",
        deviceId: "dev_rate_limit",
        sequenceNumber: 1,
        monotonicTimestamp: 123,
        wallClockTimestamp: new Date().toISOString(),
        messageType: "LOG",
        payload: { level: "INFO", category: "EXECUTION", event: "RATE", message: "ratelimit test" }
    };

    // Fire requests rapidly above limit (120 per 10s)
    let lastStatus = 200;
    for (let i = 0; i < 125; i++) {
        validEnvelope.sequenceNumber = i + 1;
        const { req, res } = createMockReqRes('POST', '/api/log', {}, JSON.stringify(validEnvelope), '127.0.0.1');
        await handleRequest(req, res);
        lastStatus = res.getStatusCode();
    }

    assert.strictEqual(lastStatus, 429);
});

test('Log server accepts valid ANR/CRASH telemetry envelopes', async () => {
    const anrEnvelope = {
        schemaVersion: "1.0",
        sessionId: "sess_anr",
        deviceId: "dev_anr",
        sequenceNumber: 1,
        monotonicTimestamp: 12345,
        wallClockTimestamp: new Date().toISOString(),
        messageType: "ANR",
        payload: {
            level: "ERROR",
            category: "DIAGNOSTIC",
            event: "ANR_DETECTED",
            message: "Main thread hang detected",
            stack: "at MainThread.run"
        }
    };

    const { req, res } = createMockReqRes('POST', '/api/log', {}, JSON.stringify(anrEnvelope));
    await handleRequest(req, res);

    assert.strictEqual(res.getStatusCode(), 200);
    const jsonRes = JSON.parse(res.getBody());
    assert.strictEqual(jsonRes.status, 'success');
});

test('Log server accepts allow-listed commands at POST /api/commands and rejects invalid ones', async () => {
    // Valid command
    const validCmd = { action: "set_force_401", params: { enabled: true } };
    const { req: req1, res: res1 } = createMockReqRes('POST', '/api/commands', {}, JSON.stringify(validCmd));
    await handleRequest(req1, res1);
    assert.strictEqual(res1.getStatusCode(), 200);
    assert.strictEqual(JSON.parse(res1.getBody()).status, 'success');

    // Invalid / unallow-listed command
    const invalidCmd = { action: "delete_all_files" };
    const { req: req2, res: res2 } = createMockReqRes('POST', '/api/commands', {}, JSON.stringify(invalidCmd));
    await handleRequest(req2, res2);
    assert.strictEqual(res2.getStatusCode(), 400);
    assert.ok(JSON.parse(res2.getBody()).error.includes('Invalid or unallow-listed action'));
});

// Cleanup test logs dir after tests
process.on('exit', () => {
    try {
        fs.rmSync(TEST_LOGS_DIR, { recursive: true, force: true });
    } catch (e) {}
});
