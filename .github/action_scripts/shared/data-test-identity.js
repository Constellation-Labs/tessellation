const fs = require('node:fs');
const path = require('node:path');
const { createECDH } = require('node:crypto');
const { dag4 } = require('@stardust-collective/dag4');

const INITIAL_BALANCE = 100000000;

// This is a job-local transaction identity, not a validator key or a CI secret.
function prepareIdentity(genesisPath, keyFile) {
    if (!fs.lstatSync(genesisPath).isFile() ||
        fs.existsSync(path.join(path.dirname(genesisPath), 'genesis.snapshot'))) {
        throw new Error('Expected an unstarted local metagraph genesis CSV');
    }
    const genesis = fs.readFileSync(genesisPath, 'utf8');
    if (!genesis.trim() || !genesis.trim().split(/\r?\n/).every(line => /^DAG[0-9][1-9A-HJ-NP-Za-km-z]+,\d+$/.test(line))) {
        throw new Error('Invalid local genesis CSV');
    }
    const key = createECDH('secp256k1');
    key.generateKeys();
    const privateKey = key.getPrivateKey('hex').padStart(64, '0');
    const address = dag4.createAccount(privateKey).address;
    // Exclusive creation prevents accidentally replacing an existing identity.
    fs.writeFileSync(keyFile, privateKey + '\n', { flag: 'wx', mode: 0o600 });
    fs.appendFileSync(genesisPath, `${genesis.endsWith('\n') ? '' : '\n'}${address},${INITIAL_BALANCE}\n`);
    return address;
}

function readIdentity(keyFile) {
    if (!keyFile) throw new Error('CI_TEST_KEY_FILE is required; run prepare-data-test-identity.js first');
    const fd = fs.openSync(keyFile, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    try {
        const info = fs.fstatSync(fd);
        if (!info.isFile() || info.size > 65 || (info.mode & 0o077) !== 0) {
            throw new Error('Test identity must be a private, regular key file');
        }
        const key = fs.readFileSync(fd, 'utf8').trim();
        if (!/^[0-9a-f]{64}$/.test(key)) throw new Error('Invalid test identity');
        createECDH('secp256k1').setPrivateKey(Buffer.from(key, 'hex')); // Reject invalid scalars before HTTP.
        return key;
    } finally {
        fs.closeSync(fd);
    }
}

module.exports = { INITIAL_BALANCE, prepareIdentity, readIdentity };
