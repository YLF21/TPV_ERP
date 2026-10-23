import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { it as test } from 'vitest';

const require = createRequire(import.meta.url);
const { listSerialPorts } = require('./serial-ports.cjs');

function enumerate(stdout, error) {
  let invocation;
  const executor = (file, args, options, callback) => {
    invocation = { file, args, options };
    callback(error, stdout);
  };
  return { result: listSerialPorts({ executor, platform: 'win32' }), getInvocation: () => invocation };
}

test('enumerates zero, one and multiple ports from PowerShell JSON', async () => {
  assert.deepEqual(await enumerate('').result, []);
  assert.deepEqual(await enumerate('"COM3"').result, [{ path: 'COM3' }]);
  assert.deepEqual(await enumerate('["COM2","COM10"]').result, [{ path: 'COM2' }, { path: 'COM10' }]);
});

test('normalizes, filters, deduplicates and sorts COM names numerically', async () => {
  const output = '\uFEFF[" com10 ","COM02","com2","COM1","COM0","COM-1","LPT1","COM3: & calc",42,null]';
  assert.deepEqual(await enumerate(output).result, [
    { path: 'COM1' }, { path: 'COM2' }, { path: 'COM10' },
  ]);
});

test('runs a fixed read-only command with bounded and hidden execution', async () => {
  const call = enumerate('[]');
  await call.result;
  const { file, args, options } = call.getInvocation();
  assert.match(file, /[\\/]WindowsPowerShell[\\/]v1\.0[\\/]powershell\.exe$/i);
  assert.deepEqual(args.slice(0, 4), ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden']);
  assert.equal(args[4], '-Command');
  assert.equal(args[5], '[System.IO.Ports.SerialPort]::GetPortNames() | ConvertTo-Json -Compress');
  assert.equal(options.windowsHide, true);
  assert.equal(options.timeout, 5000);
  assert.equal(options.maxBuffer, 64 * 1024);
});

test('propagates executor failures, including timeout, and malformed JSON', async () => {
  const timeout = Object.assign(new Error('timed out'), { killed: true, signal: 'SIGTERM' });
  await assert.rejects(enumerate('', timeout).result, (error) => error === timeout);
  await assert.rejects(enumerate('{').result, SyntaxError);
});

test('rejects non-Windows platforms without invoking a process', async () => {
  let called = false;
  await assert.rejects(listSerialPorts({
    platform: 'linux', executor: () => { called = true; },
  }), /requiere Windows/);
  assert.equal(called, false);
});
