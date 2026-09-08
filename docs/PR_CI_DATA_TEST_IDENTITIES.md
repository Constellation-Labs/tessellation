# fix(ci): make data and fee E2E tests self-contained

## Summary

Remove the data and fee E2E jobs' dependency on `CI_PRIVATE_KEY`. Each job now creates a fresh throwaway transaction identity, funds it in its downloaded local metagraph genesis, and keeps the private key in a restricted job-temporary file. Both jobs still submit signed transactions to the real local cluster.

This is CI-only work. It does not change validator code, consensus membership, quorum, snapshot timing, fees, rewards, public-network configuration, or production keys.

## Problem and why this is needed

The existing workflows invoke the data scripts with five port prefixes and a sixth argument supplied by a repository secret. When that secret is unavailable, both scripts exit with a usage error before sending any transaction. Passing other suites cannot compensate for these unexecuted checks.

This was reproduced in [run 34179717401](https://github.com/Proph151Music/tessellation/actions/runs/34179717401): data job `101918720823` and fee job `101918720904` successfully started and checked their local clusters, then failed on the missing argument. This run also had a separate delegated-staking token-lock submission failure; this patch does not claim to fix that failure.

## Changes

- Generate a cryptographically random secp256k1 transaction identity per job; no shared secret or known static key is substituted.
- Add 100,000,000 base units to that address in the job-local metagraph genesis **before startup**. The checked-in CSV and cached build artifacts are unchanged.
- Store the key in a private temporary directory with a mode-0600 file; pass only its path between steps. It is not passed on the command line, printed, committed, or included in log artifacts.
- Reject missing, malformed, overly permissive, oversized, symlinked, or invalid-scalar key files. Refuse to replace an existing key or fund a genesis that already has a snapshot.
- Keep signed data and fee submissions. Fail immediately when submission is rejected instead of swallowing the error.
- Require the expected address and usage value (10), rather than any nonempty response.
- Select the actual local metagraph ID, capture its balances before submission, and require **source debit = recipient credit = estimated positive fee** in Global L0. A pre-funded recipient alone can no longer pass the test.
- Restrict script endpoints to numeric loopback ports, disable HTTP redirects, and bound individual HTTP requests to 10 seconds.

## Safety and compatibility

Only GitHub workflow files and JavaScript test helpers change. The local template's real signature verification, fee validation, snapshot processing and balance accounting remain in use. No assertion is skipped and no production validation is bypassed. The test scripts now take five port arguments and read `CI_TEST_KEY_FILE`; the old sixth private-key argument is deliberately removed.

The tests operate in my isolated testing environment and in ephemeral GitHub-hosted test clusters. Nothing is joined to or deployed on a public network. Funding is synthetic local genesis funding, not a public transfer.

## Validation

Local command:

```sh
node --test .github/action_scripts/test/data-test-identity.test.js
```

Nine test cases passed on Node 22.22.1, covering fresh identities and exact funding, key permissions, overwrite protection, post-start refusal, malformed genesis, invalid key inputs, exact data state, exact fee accounting, correct metagraph selection, and both entry points failing before HTTP without a key. The same command is added to both E2E jobs, which use Node 18. `git diff --check` passed.

The initial local run caught an overly restrictive address matcher that rejected a valid zero checksum digit; it was corrected before publication, and all nine tests then passed. Local helper tests do not replace real-cluster E2E evidence. GitHub validation results will be recorded in the review description after the run finishes.

## Relationship to the gossip fix

This follow-up is a separate CI-only commit on top of the unchanged, published gossip response-deadline patch. The fork review compares against `fix/mainnet-gossip-response-deadline` so only CI changes appear. Its workflow run tests the two changes together. The CI commit can also be cherry-picked independently; it has no production-code dependency on the gossip fix.

This work repairs missing test coverage; it is not itself a fix for slow snapshots or an explanation of the September Mainnet incident.
