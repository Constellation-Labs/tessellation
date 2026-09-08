# Mainnet gossip response lifetime and snapshot progress

Status: candidate under qualification, not a completed incident fix or release.

## Problem and intended effect

Mainnet v3.5.30 has eight concurrent **peer-gossip workers**. These are HTTP
request workers, not rotating consensus seats. This work does not backport v4
committee rotation or change the Mainnet facilitator-selection algorithm.

The five-second gossip client timeout wraps `Client.run`, a Resource acquisition.
An HTTP response can arrive promptly while its body remains incomplete. The
worker stays busy consuming that body; it never reaches its failure handler or
releases its place for another peer. Enough stalled responses can consequently
stop a healthy peer's messages from being fetched.

The concrete correction applies the existing timeout to the complete query,
including response consumption and decoding. A timeout raises an error, closes
the response resource, and lets the existing runner release the worker and invoke
the existing health check. Truncated streams are not reported as successful.
There is no new retry, timer setting, feature flag, activation gate, or blacklist.
The timeout is total elapsed query time, not an idle timeout reset by each byte.

This targets avoidable failure to fetch consensus inputs. It does not eliminate
the configured 50-second phase recovery waits for genuinely missing declarations.
It must not be described as correcting Mainnet rewards without measured snapshot
and epoch-progress evidence. No retrospective reward compensation is implemented.

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
- The nine open upstream PR numbers remain #1597, #1596, #1595, #1594, #1593,
  #1592, #1591, #1538, #1526. A new patch-level compatibility review is still
  required before submission; unchanged branch heads are not a substitute for it.
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

The 25 focused tests passed, covering both controls, all five query paths, shared
acquisition/body budget, cancellation, trickled incomplete JSON, healthy responses,
existing acknowledgment thresholds, and successive phase recovery timers.
Full `nodeShared/test`: 294 passed, zero failures/errors. GL0 assembly passed.
Python namespace/parser/analysis tests: 14 passed. Two initial Scala fixture
compile errors (parenthesis and InetSocketAddress API) were corrected before the
passing run; neither was a production failure.

Session validation is stubbed in the new component fixtures. They do not prove
cryptographic authentication; no signature or finality claim follows from them.

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

Native results, snapshot benefit, complete reentry, large healthy responses,
mixed-version behavior, and production-scale qualification remain pending.
This document must be updated with actual results, including failed trials,
before any claim that the candidate fixes snapshot delays.
