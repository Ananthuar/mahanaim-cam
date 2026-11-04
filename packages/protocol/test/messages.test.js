const test = require('node:test');
const assert = require('node:assert');
const { parseMessage, serializeMessage } = require('../dist/messages.js');

test('parseMessage returns parsed object for valid JSON with type', () => {
    const raw = JSON.stringify({ type: 'PAIR_REQUEST', deviceName: 'Pixel 7', uuid: '123', platformVersion: '34' });
    const parsed = parseMessage(raw);
    assert.ok(parsed);
    assert.strictEqual(parsed.type, 'PAIR_REQUEST');
    assert.strictEqual(parsed.deviceName, 'Pixel 7');
});

test('parseMessage returns null for invalid JSON', () => {
    assert.strictEqual(parseMessage('not json'), null);
});

test('parseMessage returns null for JSON without type field', () => {
    assert.strictEqual(parseMessage(JSON.stringify({ hello: 'world' })), null);
});

test('serializeMessage stringifies message accurately', () => {
    const msg = { type: 'HEARTBEAT', timestamp: 123456789 };
    const serialized = serializeMessage(msg);
    assert.strictEqual(serialized, JSON.stringify(msg));
});
