# Gossip response-deadline validation

These are isolated testing-environment observations, not public-network telemetry
or a guarantee of Mainnet reward rates. No runtime keystores or full raw logs are
included. JSON reports contain SHA256 hashes of the retained input evidence.

## Artifact identity

| Role | Source | GL0 JAR SHA256 |
| --- | --- | --- |
| Official stock v3.5.30 | `9b1f826db65d56d1736a298fd18c842e0c93f5d6` | `9a5726027b962f3a8271d77c37e9c11c66d10a5a960da44d06c283b2a523ed5d` |
| Initial candidate | Production source later recorded in `caa0b70ca192e2b4df54c685e42afd7c54b21801` | `10fbceff4c8ae7b76f1c7bab24929e7bbdfc808133f1a8a40179dc7f1e40da1f` |
| Narrowed candidate | Production source later recorded in `44125ff8784e56b485a4d14f0e1df3ba84c082d4` | `6c53c84f0ec7fff444c5b96eb913d3765e7819866bd3a56d798d793f994a3e1e` |

Both candidates were built from working-tree source before those commits were
created. The source mapping does not assert that the artifact's embedded revision
equals the later source commit. Each native harness verifies the supplied artifact
checksum before starting and records the JAR selected for each validator.

## Tested-source publication mapping

The GitHub API publication changes commit metadata, so published commit IDs differ
from the local test revisions. Complete tree hashes were compared, not just the
production file:

| Stage | Tested local revision | Published revision | Identical Git tree |
| --- | --- | --- | --- |
| Stock reproduction | `843551f51cecfa35762413133b0cf62dda1d2248` | [d59cfb5](https://github.com/Proph151Music/tessellation/commit/d59cfb5afef78cd21ae2ec8146f58a29ee82fa22) | `00241b1cf53bf6fd4be2cdca1f35e116699fee65` |
| Narrowed implementation | `44125ff8784e56b485a4d14f0e1df3ba84c082d4` | [280b33a](https://github.com/Proph151Music/tessellation/commit/280b33aca94436c3e33fc15e1f052241fa9d6ce1) | `785604fd53f957ae0d22c53ee430b0096a860445` |

The final review-package commit adds documentation, measured reports and stronger
evidence-analysis tests. It does not change production Scala source or the native
fault harness relative to the tested implementation tree. Stock reproduction and
behavioral changes remain separate commits.

Native image ID:
`sha256:eb716b08291d5e4bf25578dc9d3078982e7d061cb8edebcea28cf4f3a2d30364`.
The image supplies Java and throwaway-key tooling; the tested GL0 JAR is separately
mounted read-only. Each validator is limited to 1.5 CPUs, 1800 MiB container memory
and a 1200 MiB maximum Java heap. There is no public-network connection or seedlist.

## Reports

- [Stock](stock.json): no timed-epoch progress during the fault. The optional
  reward-amount collector captured no rows, so amount fields are null, not zero.
- [Initial candidate](initial-candidate.json): two timed-epoch advances and two
  captured reward-bearing snapshots during the fault. This version also bounded
  initialization calls; that broader change was subsequently removed.
- [Narrowed candidate](candidate.json): three timed-epoch advances and three
  captured reward-bearing snapshots during the 210-second fault. It passed the
  stronger post-restoration gate, advancing from matching ordinal 12 to 15.
- [Selected Scala result lines](scala-results.txt).

The original recovery gate counted rounds after the affected ordinal, not strictly
after restoration. Those first two reports explicitly show
`post_restoration_rounds_qualified: false`. The revised gate records its baseline
after restoration and requires agreement across all three healthy observers after
three further ordinal advances. It does not assert full reentry of the two fault
targets, which is separately outside the completed qualification.

## Reproduction

Run the commands from the repository root on an isolated Linux Docker host with
the native test image already built. Do not substitute public endpoints or keys.
Use separate, new output directories for every trial. Never run heavy assembly
concurrently with the five-validator test. Paths below refer to locally obtained
and checksum-verified artifacts, not files fetched by the harness.

```bash
python3 -m unittest discover -s docker/bin -p 'test_mainnet*py'

python3 docker/bin/mainnet-response-deadline-devnet.py \
  --mode stock --jar /path/to/verified-v3.5.30-cl-node.jar \
  --expected-sha256 9a5726027b962f3a8271d77c37e9c11c66d10a5a960da44d06c283b2a523ed5d \
  --output /path/to/new-stock-results --seconds 900

python3 docker/bin/mainnet-response-deadline-devnet.py \
  --mode fixed --jar /path/to/locally-built-candidate.jar \
  --expected-sha256 YOUR_VERIFIED_BUILD_SHA256 \
  --output /path/to/new-candidate-results --seconds 900
```

In a second terminal, after the selected run records its `isolation` event, start
the optional reward collector using that same output directory. It reads only the
fixed private bridge; it never sends a transaction. The file must not already exist.

```bash
python3 docker/bin/collect-mainnet-lab-rewards.py \
  --output /path/to/new-candidate-results --seconds 1800
python3 docker/bin/analyze-mainnet-response-deadline.py \
  /path/to/new-candidate-results
```

Run the analyzer only after the native harness and optional collector exit. It
rejects missing completion, insufficient observation time, conflicting sampled
values, unexpected stock progress, and a candidate without measured epoch progress.
The collector reports read errors and fails explicitly if it captures no rows;
startup HTTP 503 responses are retained as capture errors, not hidden.

Scala commands used Java 21 with `-J-Xmx5G -J-XX:ActiveProcessorCount=4` for sbt
and `-Xmx2G -XX:ActiveProcessorCount=4` for forked tests, plus test JVM opens for
`java.util`, `java.security`, and `java.lang.invoke`. Tasks were `nodeShared/test`,
`dagL0/test`, `currencyL0/test`, `shared/test`, and `dagL0/assembly`. The final
`scalafmtCheckAll` also passed. Total: 561 Scala passes, zero failures/errors,
two existing ignored tests; 28 Python unit tests passed. Results are local,
not GitHub CI results.

## Interpretation

The fault is deliberately persistent: headers arrive, but the body stays incomplete
while whitespace prevents an idle-read timeout from ending it. Stock and candidate
use the same two-worker override in the five-validator topology. Mainnet's eight
workers are covered by the separate real-runner fixture. This does not represent
Mainnet topology, workload, a Byzantine threshold proof, or a supported rolling
upgrade. Any reward amounts in the reports are synthetic devnet atomic units.
