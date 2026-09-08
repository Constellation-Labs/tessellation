const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { dag4 } = require('@stardust-collective/dag4');
const { INITIAL_BALANCE, prepareIdentity, readIdentity } = require('../shared/data-test-identity');
const { hasExpectedUsage, getBalances, hasExpectedFeeTransfer } = require('../shared/data-test-assertions');

const original = 'DAG6kfTqFxLLPLopHqR43CeQrcvJ5k3eXgYSeELt,10000000000000';
function fixture(t) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'data-identity-unit-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const genesis = path.join(directory, 'genesis.csv');
    const keyFile = path.join(directory, 'key.hex');
    fs.writeFileSync(genesis, original);
    return { directory, genesis, keyFile };
}

test('fresh identities are funded in local genesis and stored privately', t => {
    const a = fixture(t);
    const b = fixture(t);
    const first = prepareIdentity(a.genesis, a.keyFile);
    const second = prepareIdentity(b.genesis, b.keyFile);
    assert.notEqual(first, second);
    assert.notEqual(readIdentity(a.keyFile), readIdentity(b.keyFile));
    assert.equal(dag4.createAccount(readIdentity(a.keyFile)).address, first);
    assert.equal(fs.statSync(a.keyFile).mode & 0o777, 0o600);
    assert.equal(fs.readFileSync(a.genesis, 'utf8'), `${original}\n${first},${INITIAL_BALANCE}\n`);
});

test('existing key cannot be overwritten or cause extra genesis funding', t => {
    const f = fixture(t);
    prepareIdentity(f.genesis, f.keyFile);
    const before = fs.readFileSync(f.genesis, 'utf8');
    const key = readIdentity(f.keyFile);
    assert.throws(() => prepareIdentity(f.genesis, f.keyFile), /EEXIST/);
    assert.equal(fs.readFileSync(f.genesis, 'utf8'), before);
    assert.equal(readIdentity(f.keyFile), key);
});

test('pre-existing genesis snapshot prevents funding after startup', t => {
    const f = fixture(t);
    fs.writeFileSync(path.join(f.directory, 'genesis.snapshot'), 'test');
    assert.throws(() => prepareIdentity(f.genesis, f.keyFile), /unstarted/);
    assert.equal(fs.existsSync(f.keyFile), false);
    assert.equal(fs.readFileSync(f.genesis, 'utf8'), original);
});

test('malformed genesis is rejected without creating a key', t => {
    const f = fixture(t);
    fs.writeFileSync(f.genesis, 'not a genesis CSV');
    assert.throws(() => prepareIdentity(f.genesis, f.keyFile), /Invalid local genesis/);
    assert.equal(fs.existsSync(f.keyFile), false);
});

test('missing, malformed, oversized, permissive and symlink keys fail closed', t => {
    const f = fixture(t);
    assert.throws(() => readIdentity(), /CI_TEST_KEY_FILE/);
    assert.throws(() => readIdentity(f.keyFile), /ENOENT/);
    fs.writeFileSync(f.keyFile, 'invalid', { mode: 0o600 });
    assert.throws(() => readIdentity(f.keyFile), /Invalid test identity/);
    fs.writeFileSync(f.keyFile, 'a'.repeat(100));
    assert.throws(() => readIdentity(f.keyFile), /private, regular/);
    fs.writeFileSync(f.keyFile, '0'.repeat(64));
    assert.throws(() => readIdentity(f.keyFile));
    fs.writeFileSync(f.keyFile, 'f'.repeat(64));
    assert.throws(() => readIdentity(f.keyFile));
    fs.writeFileSync(f.keyFile, 'a'.repeat(64));
    fs.chmodSync(f.keyFile, 0o644);
    assert.throws(() => readIdentity(f.keyFile), /private, regular/);
    const link = path.join(f.directory, 'link.hex');
    fs.symlinkSync(f.keyFile, link);
    assert.throws(() => readIdentity(link), /ELOOP/);
});

test('data assertion requires exact address and usage, not a nonempty object', () => {
    assert.equal(hasExpectedUsage({}, 'sender'), false);
    assert.equal(hasExpectedUsage({ error: 'rejected' }, 'sender'), false);
    assert.equal(hasExpectedUsage({ usages: { deviceAddress: 'sender', deviceUsage: 0 } }, 'sender'), false);
    assert.equal(hasExpectedUsage({ usages: { deviceAddress: 'other', deviceUsage: 10 } }, 'sender'), false);
    assert.equal(hasExpectedUsage({ usages: { deviceAddress: 'sender', deviceUsage: 10 } }, 'sender'), true);
});

test('fee verification requires exact debit and credit, not a pre-funded recipient', () => {
    const before = { sender: 1000, recipient: 500 };
    assert.equal(hasExpectedFeeTransfer(before, before, 'sender', 'recipient', 100), false);
    assert.equal(hasExpectedFeeTransfer(before, { sender: 900, recipient: 600 }, 'sender', 'recipient', 100), true);
    for (const after of [{ sender: 1000, recipient: 600 }, { sender: 900, recipient: 500 }, { sender: 900, recipient: 601 }]) {
        assert.equal(hasExpectedFeeTransfer(before, after, 'sender', 'recipient', 100), false);
    }
    for (const fee of [0, -1, 0.5, NaN, '100']) assert.equal(hasExpectedFeeTransfer(before, before, 'sender', 'recipient', fee), false);
    assert.equal(hasExpectedFeeTransfer(before, before, 'sender', 'sender', 100), false);
});

test('snapshot lookup selects the requested metagraph and rejects missing state', () => {
    const expected = { sender: 1000 };
    const combined = [{}, { lastCurrencySnapshots: {
        wrong: { Right: [{}, { balances: { recipient: 100 } }] },
        wanted: { Right: [{}, { balances: expected }] }
    } }];
    assert.deepEqual(getBalances(combined, 'wanted'), expected);
    assert.throws(() => getBalances(combined, 'missing'), /not available/);
    assert.throws(() => getBalances(null, 'wanted'), /not available/);
});

test('both entry points fail before networking when a test identity is missing', () => {
    for (const file of ['data-with-fee.js', 'data-without-fee.js']) {
        const env = { ...process.env };
        delete env.CI_TEST_KEY_FILE;
        const result = spawnSync(process.execPath, [path.resolve(__dirname, '../send_transactions', file), '90', '91', '80', '81', '82'], {
            env, encoding: 'utf8', timeout: 10000
        });
        assert.equal(result.status, 1);
        assert.match(result.stderr, /CI_TEST_KEY_FILE is required/);
        assert.equal(result.stdout, '');
    }
});
