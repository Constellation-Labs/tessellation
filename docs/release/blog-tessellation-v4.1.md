# Tessellation v4.1: Consensus for Partial Synchrony

A consensus protocol owes two things. Safety: it never finalizes two conflicting states. Liveness: it
eventually finalizes something. Under partial synchrony these are separable, and the separation is the
whole game. Safety must hold unconditionally, including while the network is slow, partitioned, or
degraded. Liveness only has to hold once the network settles down.

v4.0's Global L0 consensus did not honor that separation. v4.1 does. That is the release.

Everything below is why that sentence matters, what it cost us to learn, and what it means if you run a
node.

---

## What v4.0 got wrong

v4.0 decided who was participating in a round using local, timing-dependent judgements: a stall
detector on each node formed its own opinion about which peers were present and responsive. Fine, except
that opinion fed the committee, and the committee is hashed into the finalized artifact.

So a safety-relevant quantity was made a function of local timing. Two honest nodes, seeing the same
network through slightly different latency, compute slightly different committees, therefore different
hashes, therefore they finalize incompatible artifacts. The chain forks. No node misbehaved, and there
is no single faulty line to point at. The design tied safety to a timing assumption, and that is a
mistake careful coding cannot fix.

This failed to surface on Testnet or IntegrationNet because on these relatively small, fast, lightly loaded networks the
timing assumption effectively always held. The defect was latent, not absent. It needed a network slow
enough, for long enough, to expose it.

MainNet was that network.

---

## MainNet

Let us be exact about what we know and what we don't, because the temptation to narrate a clean root
cause after the fact is strong and usually dishonest.

What we observed:

- The network ran normally for several hours post v4.0.0 launch
- Then it degraded monotonically. Nodes became resource-constrained, fell behind the frontier, and did
  not recover.
- Coordinated cold restarts did not restabilize the cluster. We tried more than once.
- The pressure tracked the size of the state each node had to carry and move.

While we are unable to isolate a single root cause, the recently introduced Merkle Patricia Trie is 
the largest new load on the state footprint and the leading suspect; the state-transfer path is where we later found the clearest resource contention
(more on that below). But "leading suspect" is not "confirmed," and this release does not depend on the
distinction.

That last point is the important one. Whatever pushed nodes into degradation, a correct consensus
protocol must survive it, because degradation is not an exceptional condition on a real network of
independent operators. It is the normal condition. v4.0's engine was unable to survive it, and the reason it
could not is the construction defect above, not the specific trigger. So we fixed the construction.

We reverted MainNet to v3.5.12 while we did.

---

## The fix

v4.1 rebuilds Global L0 consensus as a leader-based, partially synchronous Byzantine Fault Tolerant
protocol. Stated against the failure it replaces:

**Safety does not depend on timing.** Agreement is over certified quorums of signatures on the same
artifact. There is no local-observation input to what gets finalized. Two nodes cannot finalize
conflicting states regardless of how differently they experience the network.

**Membership changes only through certified on-chain evidence, at round boundaries.** A peer joins or
leaves the active signing set because a quorum certified evidence that it should, carried on the signed
chain, applied at a clean boundary. Never because one node's timer fired. This is the direct repair of
the v4.0 defect: the committee is now a function of agreed chain facts, not local timing.

**Liveness is a separate mechanism.** A stalled round does not sit and wait on a dead leader. A timeout
produces a certificate, the certificate rotates leadership, the view advances, the round proceeds. This
is exactly the property partial synchrony asks for: progress resumes once the network is behaving,
without ever putting safety at risk to get it.

**A node that falls behind has a defined way back.** Chain recovery gives a lagging node a reliable path
to the current frontier, and recovering nodes no longer deadlock waiting on each other for state none of
them holds yet.

The threat model is stated and narrow, because pretending otherwise would cost us in the design: a
trusted, permissioned set of operators subject to crashes, GC pauses, partitions, and restarts. Not
adversaries. We optimized for the faults we actually have.

---

## Consensus and state transfer are different problems

The most consequential thing we found during stabilization was not in the consensus logic. It was that
consensus and bulk state transfer were contending for the same resources on the same node.

A node streaming a large snapshot to a peer that had fallen behind had that much less capacity for the
round it owed the network right now. At small scale this is invisible. At MainNet scale, with a large
state to move, it is how a handful of slow nodes becomes a stuck network: the act of helping a laggard
catch up degrades the helper, which creates the next laggard.

The correction is a single principle, enforced structurally: **consensus votes over small hashes; moving
state is a separate, bounded activity.** Catching up a slow peer can no longer starve the consensus loop.
This removed a large class of the slow-grind-to-a-halt behavior on its own, independent of the consensus
rewrite.

---

## What this means if you run a node

The engine leans on a bounded core of actively signing nodes, with a wider tier that also signs and
earns. The consequence is direct: **availability determines participation, and it is rewarded.**

A healthy, responsive node participates on equal footing. A node that drops out during its turn is
routed around; the network does not wait for it, and it does not earn while it is behind, but it can now
reliably catch back up. There is no slashing. Nothing gets confiscated; the network simply stops
waiting on work an absent node owes it and keeps going.

One hard rule: v4.1 deploys as a coordinated cold restart, not a rolling upgrade. Every node runs the
same jar and the same consensus config, and the software enforces this at the handshake. A node on a
mismatched version or config is refused at the door. The alternative is letting a divergent node fork
the chain quietly, and we are done with quiet forks.

---

## Rollout

- Testnet: validating since mid-June.
- IntegrationNet: targeted for Monday, July 13, 2026, via coordinated cold restart.
- MainNet: after IntegrationNet validation, announced separately with full notice.

---

## What it unlocks

This release buys durability under stress. That is the prerequisite for everything else the network is
reaching for, throughput included.

The state-verification work the Merkle Patricia Trie exists to enable (inclusion proofs, light clients,
verifying state instead of trusting a node) is still on the roadmap. That work is exactly why the base
has to survive a bad day. v4.1 is that base, corrected where the design was wrong.

---

**Source and releases:** [github.com/Constellation-Labs/tessellation](https://github.com/Constellation-Labs/tessellation)

**Release notes:** [v4.1.0-testnet.md](v4.1.0-testnet.md)

**Community:** [Discord](https://discord.gg/constellation). Node operator channels answer deploy
questions fastest.

---

# Social Media Excerpts

## LinkedIn Post

**Tessellation v4.1: correcting a consensus design that tied safety to timing.**

A consensus protocol has to keep two promises. Safety: never finalize conflicting states. Liveness:
eventually finalize something. Under partial synchrony they are separable, and safety must hold even
when the network is slow. Liveness only has to hold once it settles.

v4.0's Global L0 consensus did not separate them. It decided round membership from local, timing
dependent judgements, and membership is hashed into the finalized result. So under real latency skew,
honest nodes could finalize incompatible states and fork. Our small networks were too fast to expose
it. MainNet was not, and after several healthy hours it degraded past recovery.

We reverted MainNet and rebuilt the engine as a leader-based, partially synchronous BFT protocol. Safety
no longer takes any timing input. Membership changes only through certified on-chain evidence at round
boundaries. Liveness is a separate pacemaker that advances a stalled round instead of waiting. And we
split consensus from bulk state transfer, because we found the two were starving each other under load.

Testnet now, IntegrationNet July 13, MainNet after it proves out. No MainNet date until then. For
operators: availability determines participation, and the upgrade is a coordinated cold restart enforced
at the handshake.

Writeup: [link]

---

## X/Twitter Thread

**1/** v4.0's L0 consensus had a design defect, not a bug: it tied a safety property to local timing.
v4.1 fixes the construction. Here is the actual mechanism, and the honest version of what happened on
MainNet.

**2/** A BFT protocol owes safety (never finalize conflicting states) and liveness (eventually finalize).
Under partial synchrony these separate: safety unconditional, liveness only once the network settles.
That separation is the whole design.

**3/** v4.0 decided round membership from each node's local, timing-dependent view of who was present.
But membership is hashed into the finalized artifact. So latency skew alone made honest nodes finalize
incompatible states. A safety quantity built on a timing assumption.

**4/** Invisible on Testnet/IntegrationNet, where the timing assumption always held. At MainNet scale it
didn't. Network ran a few hours, then degraded monotonically and would not recover, cold restarts
included. We did not isolate a single trigger, and I won't pretend we did.

**5/** The point: a correct protocol has to survive degradation regardless of trigger, because
degradation is the normal state of a real operator network. v4.0's couldn't, by construction. So we
rebuilt it, not the trigger.

**6/** v4.1: safety takes no timing input (agreement over certified quorums). Membership changes only via
certified on-chain evidence at round boundaries. Liveness is a separate pacemaker that rotates a stalled
leader and advances the view. Lagging nodes have a defined path back.

**7/** Separate finding, equally important: consensus and bulk state transfer were contending for the
same node resources. Helping a laggard catch up degraded the helper. v4.1 splits them. Consensus votes
over hashes; state transfer is bounded and can't starve the round.

**8/** Operators: availability determines participation and is rewarded. No slashing, but the network
doesn't block on an absent node. Deploy is a coordinated cold restart enforced at the handshake, not a
rolling upgrade.

**9/** Testnet now, IntegrationNet July 13, MainNet after it holds up under load. No MainNet date before
then. Committing to one prematurely is the mistake we already made once.
