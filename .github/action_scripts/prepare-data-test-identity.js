const fs = require('node:fs');
const path = require('node:path');
const { prepareIdentity } = require('./shared/data-test-identity');

// Run after artifact download, before either cluster starts. Never modify the
// checked-in CSV or cached artifacts; only this job's downloaded metagraph copy.
if (!process.env.RUNNER_TEMP || !process.env.GITHUB_ENV) {
    throw new Error('This entry point requires the GitHub Actions job environment');
}
const directory = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP, 'data-test-identity-'));
const keyFile = path.join(directory, 'private-key.hex');
const genesis = path.resolve(__dirname, '../code/metagraphs/project-template-metagraph/metagraph-l0/genesis-node/genesis.csv');
const address = prepareIdentity(genesis, keyFile);
fs.appendFileSync(process.env.GITHUB_ENV, `CI_TEST_KEY_FILE=${keyFile}\n`);
console.log(`Funded throwaway data-test address ${address} in job-local metagraph genesis`);
