package io.constellationnetwork.node.shared.infrastructure.snapshot.daemon

/** Marker trait for download errors that should trigger a switch from full-download to incremental-recovery on the next attempt. Mix into
  * the concrete error case objects in each layer's `Download.scala`. The daemon detects via `isInstanceOf` rather than string-matching
  * `getClass.getSimpleName`, which would silently break on rename.
  *
  * Per-layer triggers:
  *   - dag-l0 `CannotFetchGenesisSnapshot` — peers don't serve genesis (too old anchor); recovery path downloads from the tip and resyncs
  *     MptStore from the checkpoint data
  *   - dag-l0 `InvalidChain` — contiguous chain validation failed; same recovery path
  *   - currency-l0 `InvalidChain` — chain validation failed; currency-l0's `recoveryDownload` currently delegates to `download` so the
  *     marker drives the daemon's fallback flag without changing the download behavior beyond that
  *
  * No `CannotFetchGenesisSnapshot` exists in currency-l0; only dag-l0 has a genesis-fetch error type.
  */
trait RecoveryFallbackEligible
