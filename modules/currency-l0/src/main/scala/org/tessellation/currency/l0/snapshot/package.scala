package org.tessellation.currency.l0

import org.tessellation.currency.schema.currency._
import org.tessellation.schema.Block
import org.tessellation.sdk.infrastructure.snapshot._
import org.tessellation.security.signature.Signed

package object snapshot {

  type CurrencySnapshotEvent = Signed[Block]

  type CurrencySnapshotArtifact = CurrencyIncrementalSnapshot

  type CurrencySnapshotContext = CurrencySnapshotInfo

  type CurrencySnapshotConsensus[F[_]] =
    SnapshotConsensus[F, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent]

}
