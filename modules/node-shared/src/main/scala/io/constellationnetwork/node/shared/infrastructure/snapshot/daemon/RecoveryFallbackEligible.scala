package io.constellationnetwork.node.shared.infrastructure.snapshot.daemon

/** Marker trait for download errors that should trigger a switch from full-download to incremental-recovery on the next attempt. Mix into
  * the concrete error case objects in each layer's `Download.scala`. The daemon detects via `isInstanceOf` rather than string-matching
  * `getClass.getSimpleName`, which would silently break on rename.
  *
  * Examples (per-layer):
  *   - `CannotFetchGenesisSnapshot` — dag-l0 / currency-l0: peers don't serve genesis (too old anchor)
  *   - `InvalidChain` — either layer: contiguous chain validation failed
  */
trait RecoveryFallbackEligible
