# Mainnet gossip response lifetime and snapshot progress

Status: reproduced transport-liveness fix with native A/B evidence; not a release
or a confirmed explanation of the September Mainnet incident.

## Problem and intended effect

Mainnet v3.5.30 has eight concurrent **peer-gossip workers**. These are HTTP
request workers, not rotating consensus seats. This work does not backport v4
committee rotation or change the Mainnet facilitator-selection algorithm.

The five-second gossip client timeout wraps `Client.run`, a Resource acquisition.
An HTTP response can arrive promptly while its body remains incomplete. The
worker stays busy consuming that body; it never reaches its failure handler or
releases its place for another peer. Enough stalled responses can consequently
stop a healthy peer's messages from being fetched.

The correction applies the existing timeout to each complete recurring query,
including response consumption and decoding. A timeout raises an error, closes
the response resource, and lets the existing runner release the worker and invoke
the existing health check. Truncated streams are not reported as successful.
There is no new retry, timer setting, feature flag, activation gate, or blacklist.
The timeout is total elapsed query time, not an idle timeout reset by each byte.

Only `/rumors/peer/query`, `/rumors/common/query`, and `/rumors/common/offer`
change. One-time initialization calls retain their existing behavior. Initialization
runs outside the bounded recurring workers, and its one-shot lifecycle needs
separate qualification before imposing a new body deadline there.

This targets avoidable failure to fetch consensus inputs. It does not eliminate
the configured 50-second phase recovery waits for genuinely missing declarations.
The intended reward benefit is continued production of reward-bearing timed
snapshots while faulty responses persist. The reward formula, supply rules, and
recipient eligibility are unchanged. No retrospective compensation is implemented;
the tests do not predict a daily Mainnet return or prove the September incident's cause.

## Stock control and code overlap

- Immutable Mainnet baseline: `9b1f826db65d56d1736a298fd18c842e0c93f5d6`, v3.5.30.
- Official GL0 JAR SHA256:
  `9a5726027b962f3a8271d77c37e9c11c66d10a5a960da44d06c283b2a523ed5d`.
- September 8 upstream head checks: Mainnet remains at the baseline; develop is
  `65b3667d414760763fe342e720a9486d2c0beb82`.
- Develop already has a per-peer gossip failure cooldown in `GossipRoundRunner`.
  It is an adjacent existing protection, not a new idea introduced here. A body
  that never finishes produces no failure for that cooldown to count. Develop's
  inspected GossipClient still uses an acquisition-only timeout.
- Complete changed-file lists were inspected for all nine open upstream PRs:
  #1597, #1596, #1595, #1594, #1593, #1592, #1591, #1538, #1526. None directly
  touches GossipClient. Adjacent ordinal configuration and download recovery work
  were not copied. This path-level check is not a full audit of every branch or a
  guarantee against indirect interactions. Heads were rechecked September 8.
- The previously published disconnect-retry PR is separate and is not required
  by this candidate. Production changes here start from the stock Mainnet client.

## Completed checks

The initial reproduction ran against unchanged production code: both new tests
passed. One demonstrated that an acquired gossip body remained open after 180
seconds of virtual time despite the five-second acquisition timeout. The second
used the real runner and real GossipClient with eight stalled response fixtures;
a ninth healthy peer was never queried in that interval. No health check fired.

The corrected comparison used the same runner and eight-worker configuration.
Within its twelve-second observation window, failed bodies were released, health
checks were invoked, and the healthy peer's query completed.

The real Ember client/server loopback test also reproduced the gap: stock kept
reading whitespace beyond its configured 500ms query timeout; the candidate
raised TimeoutException. Both versions used the same real transport. This is a
scaled timeout test, not a native-validator or Mainnet timing measurement.

The initial candidate passed 25 focused tests and 294 node-shared tests. Review
then narrowed the correction to the recurring calls, preserving initialization.
The revised candidate passed 295 node-shared tests, including shared acquisition/body
budget, cancellation, trickled incomplete JSON, healthy responses, malformed-body
rejection, invalid-session body isolation, and unchanged six-second initialization
bodies. Existing acknowledgment threshold and phase-timer tests also passed.
Global L0: 93 passed, two existing ignored; Currency L0: 41 passed; Shared: 132
passed, with zero failures/errors. Those three additional suites were rerun on the
narrowed source and passed again. The combined Scala count is 561 passed, with
two existing ignored tests. GL0 assembly and `scalafmtCheckAll` passed.
Python namespace/parser/analysis tests: 28 passed. Two initial Scala fixture
compile errors (parenthesis and InetSocketAddress API) were corrected before the
passing run; neither was a production failure.

Session validation is stubbed in the new component fixtures. They do not prove
cryptographic authentication; no signature or finality claim follows from them.
The invalid-session test confirms that the existing middleware withholds an
unauthorized response body; it does not replace a cryptographic security review.

The FS2 3.4.0 timeout implementation used here is a total stream deadline:
https://github.com/typelevel/fs2/blob/v3.4.0/core/shared/src/main/scala/fs2/Stream.scala#L2532

## Native comparison procedure

`docker/bin/mainnet-response-deadline-devnet.py` uses five isolated GL0 validators,
three early and two late, with throwaway identities and an internal-only Docker
network. Stock and candidate both use two peer-gossip workers so two faulty
responders can saturate the runner in this small topology. This is a deliberate
test-only override, not the Mainnet eight-worker default or a proposed setting.
No phase timer, consensus threshold, admission rule, or signed payload is changed.

After two healthy rounds, private response proxies on the two fault targets
forward native requests and response identity/session headers. For peer queries
only, they withhold the body and trickle whitespace. No rumor or signature is
manufactured. Exact private-namespace NAT rules affect only the three healthy
observers' connections to those two responders. Other requests are forwarded.

The controller observes the fault for 210 seconds, checks that all three observers
were affected, requires stock not to finish the target round and the candidate
to finish it, then removes the fault. Three subsequent matching rounds on the
three healthy observers are required. Those gates do not assert complete reentry
of both fault targets. All samples, epoch counters, signer sets, phase logs and
resource measurements are retained; owned containers/networks are cleaned up.

## Native observations and evidence limits

The first comparison completed on September 8 with the official stock artifact
and initial candidate artifact `10fbceff4c8ae7b76f1c7bab24929e7bbdfc808133f1a8a40179dc7f1e40da1f`.

| During the 210-second response-body fault | Stock | Initial candidate |
| --- | ---: | ---: |
| Healthy observers' accepted ordinal/epoch | 9 → 9 | 9 → 11 |
| Timed epoch advances | 0 | 2 |
| Affected round completed during fault | No | Yes, about 67–70 seconds after creation |
| Observed conflicting snapshot values | 0 | 0 |

The candidate captured rewards in ordinals 10 and 11. These are synthetic devnet
emissions, not Mainnet payout amounts. Both trials eventually reached matching
ordinal 13 on the three healthy observers. Fault-target reentry was not established.
The initial candidate was built before its source commit was recorded; source
and artifact identity are documented separately rather than equating their hashes.

Two measurement limitations were found and preserved:

- The original optional stock reward collector produced an empty file. The revised
  collector explicitly requests JSON and reports read-error counts. Missing stock
  amount coverage is reported as null, not zero rewards. Independent ordinal/epoch
  samples still establish no new timed epochs during the fault.
- The original recovery gate counted three rounds after the affected ordinal,
  rather than after fault removal, and compared only two observers at the gate.
  The retained samples show agreement on all three. The revised harness anchors
  after restoration and explicitly checks all three; the original trials are not
  labeled as passing that stronger gate.

The narrowed candidate's fresh native run completed successfully. Its JAR SHA256 is
`6c53c84f0ec7fff444c5b96eb913d3765e7819866bd3a56d798d793f994a3e1e`.
During the 210.170-second fault, all three healthy observers advanced from
ordinal/epoch 9 to 12. Three reward-bearing snapshots were captured. The affected
round completed in approximately 65.317, 68.151, and 65.178 seconds.
No conflicting sampled values were observed. After restoration, the stronger gate
anchored at ordinal 12 and observed agreement at ordinal 15. All owned containers
and the private bridge were cleaned up; retained evidence was preserved.

This is a demonstrated improvement in timed-snapshot progress under the reproduced
fault. It is not evidence that the three observed September peer removals were
caused by unfinished HTTP bodies. The supplied Broken pipe error alone cannot
establish that mechanism.

Full reentry, production-scale throughput and upgrade qualification remain outside
the completed checks. Existing joining validation compares version hashes, so an
arbitrary mixed-binary native test cannot be equated to a supported rolling upgrade.
No version, identity, signature or certificate check was bypassed in these trials.
Mainnet release coordination and normal upstream CI/review remain required.
