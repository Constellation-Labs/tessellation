# Metagraph upgrade guide: Tessellation v4.1 / Currency protocol v1

This guide covers the coordinated metagraph work required for the Tessellation v4.1
release that carries Global L0 consensus schema v35 and the separately activated Currency
snapshot protocol `1.0.0`.

The node release and Currency protocol transition are separate gates:

- the release version and deterministic configuration fences which binaries can join a
  cluster; and
- `fields-added-ordinals.currency-snapshot-protocol-v1` selects Currency snapshot
  protocol `1.0.0` at one announced **Global L0 ordinal**.

Do not infer either gate from SemVer. Record the exact release tag and announced ordinal
in the release announcement and deployment manifest.

## Required build contract

Every application in an active metagraph stack must be rebuilt from the exact Tessellation
SDK published for the candidate release. This includes:

- Currency L0;
- Currency L1;
- every Data L1 application; and
- any other application that embeds or advertises the Tessellation SDK version.

Use the following toolchain unless the release manifest explicitly replaces it:

| Input | Required value |
|---|---|
| Tessellation SDK | Exact announced v4.1 release tag; no version range, branch build, or older RC |
| JDK | Java 21 for build and runtime |
| Scala | 2.13.18 |
| sbt | 1.9.8 |

Set and record the candidate version explicitly. For the repository CI template this is:

```bash
export TESSELLATION_VERSION="<exact-announced-v4.1-tag>"
```

For an external metagraph, pin the same exact value in its dependency definition. Do not
publish or deploy an assembly whose build metadata advertises a different Tessellation
version. Record the source commit, SDK coordinate, assembly checksums or image digests,
JDK vendor/version, and metagraph version in the release manifest.

### Assembly merge strategy

BouncyCastle JARs include OSGI metadata that may conflict during assembly. Projects that
do not already inherit an equivalent rule need:

```scala
assembly / assemblyMergeStrategy := {
  case "META-INF/io.netty.versions.properties" => MergeStrategy.first
  case "META-INF/versions/9/module-info.class"  => MergeStrategy.first
  case PathList("META-INF", "versions", _, "OSGI-INF", _ @_*) =>
    MergeStrategy.discard
  case PathList(xs @ _*) if xs.last == "module-info.class" =>
    MergeStrategy.first
  case x if x.endsWith("/module-info.class") =>
    MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
```

## Currency protocol-v1 compatibility boundary

At the announced Global L0 ordinal, each eligible Currency lineage advances its signed
incremental snapshot version from `0.0.1` to `1.0.0`. Protocol v1 makes historical Global
dependencies deterministic and gives `GlobalSnapshotsProcessed` cumulative semantics.
It does not introduce a second Currency consensus engine or put Global v35 certificates
into Currency snapshots.

This is a signed-history transition. An old Currency L0 cannot produce an acceptable
post-boundary child, and an old Currency L1 or Data L1 application may fail the advertised
version gate even when its business logic did not change. Therefore the complete active
stack must be rebuilt and ready before the boundary.

This release does **not** claim source- or binary-compatible metagraph lifecycle APIs.
Compilation, unit tests, assembly, and an end-to-end protocol-boundary rehearsal are the
compatibility proof for each metagraph. Resolve application changes against the selected
SDK instead of assuming that a dependency-only edit is sufficient.

## Network-wide metagraph census

Before selecting the activation ordinal, the release owner must create a census of every
known Currency lineage. At minimum record:

| Field | Required evidence |
|---|---|
| Metagraph address | Canonical address |
| Operator/contact | Named owner and escalation path |
| State | Active, intentionally dormant, retired, or unknown |
| Last accepted Currency artifact | Ordinal, hash, and signed version |
| Current stack | Currency L0/L1/Data L1 version and artifact digest |
| Upgrade status | Built, tested, deployed, or intentionally held offline |
| Unapplied Global changes | Complete signed `unappliedGlobalChangeOrdinals` set |

Unknown lineages are not silently classified as retired. Resolve ownership or keep their
producers offline until compatibility is established.

### Unapplied Global-change preflight

For every active lineage, inspect the signed Global Snapshot Info immediately before
activation:

```text
unappliedGlobalChangeOrdinals
```

The preferred precondition is an empty set. A nonempty set may delay protocol transition
with deterministic `blocked_unproven` while an entry at or below the selected Global L0
view remains unresolved. It is not safe to infer processed history from a validator's
process-local cache.

For a nonempty set:

1. identify the owning lineage and every outstanding ordinal;
2. allow the ordinary signed acknowledgment path to drain it before activation;
3. record the signed preflight evidence; and
4. postpone that lineage or the global activation if the set cannot be cleared.

Do not restart a `blocked_unproven` lineage as a remedy. Investigate the signed unapplied
history first.

## Active and dormant lineage policy

### Active lineages

Before the announced boundary:

1. build every Currency L0/L1/Data L1 assembly against the exact candidate SDK;
2. run unit, assembly, replay, and end-to-end tests;
3. cold-restart each complete Currency cluster on one aligned version;
4. verify its version and deterministic-config join fences; and
5. verify the lineage's unapplied preflight and post-boundary `1.0.0` successor.

Never run a mixed Currency L0 committee during the upgrade.

### Dormant lineages

Keep dormant legacy producers offline across the boundary. They may return only after the
complete stack has been rebuilt against the activated release and the operator has followed
the deterministic resurrection procedure. An old `0.0.1` producer cannot safely resume by
posting descendants after the protocol-v1 boundary.

See [Currency L0 deterministic history and dormant-lineage resurrection](../operations/currency-l0-dormant-resurrection.md)
for the authorized rollback-lead flow and its retained-window deadline.

## Required verification

- [ ] Exact candidate Tessellation SDK coordinate is pinned in every application.
- [ ] Build and runtime use JDK 21; Scala is 2.13.18 and sbt is 1.9.8.
- [ ] Currency L0, Currency L1, and every Data L1 assembly is rebuilt.
- [ ] Source commits, assembly checksums/image digests, and advertised versions are recorded.
- [ ] The active/dormant/retired/unknown lineage census is complete.
- [ ] Each active lineage has an empty signed `unappliedGlobalChangeOrdinals` set, or activation is held.
- [ ] Dormant and unknown legacy producers are stopped until upgraded.
- [ ] Full unit and assembly tests pass.
- [ ] The generated CI metagraph crosses `0.0.1 -> 1.0.0` and Global L0 accepts its binary.
- [ ] A representative production metagraph rehearses the boundary with the exact candidate artifacts.
- [ ] Every active Currency cluster is upgraded as a complete cohort before the announced ordinal.
- [ ] The first eligible post-boundary snapshot is `1.0.0`, descendants cannot downgrade, and
      `dag_currency_l0_snapshot_protocol_total{outcome="blocked_unproven"}` does not rise.

## Troubleshooting

### Assembly fails with an OSGI conflict

Apply the merge strategy above and rebuild the complete assembly. Do not discard arbitrary
security-provider resources beyond the named OSGI metadata.

### Java compilation or runtime mismatch

Verify both the shell used by sbt and the runtime image:

```bash
java -version
sbt --version
```

Both build and deployed runtime must use Java 21.

### Protocol v1 remains blocked

Inspect the lineage's signed `unappliedGlobalChangeOrdinals` and selected Global L0 view.
Do not clear local files, invent processed history, or repeatedly restart the cohort.

### Dormant lineage cannot resume

Keep it offline and use the documented protocol-v1 resurrection flow. Do not replay an
old `0.0.1` lineage into post-boundary Global L0 or bypass the version gate.
