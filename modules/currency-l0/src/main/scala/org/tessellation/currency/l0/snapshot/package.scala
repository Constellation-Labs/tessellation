package org.tessellation.currency.l0

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshot, CurrencyTransaction}
import org.tessellation.sdk.infrastructure.snapshot._
import org.tessellation.security.signature.Signed

package object snapshot {

  type CurrencySnapshotEvent = Signed[CurrencyBlock]

  type CurrencySnapshotArtifact = CurrencySnapshot

  type CurrencySnapshotConsensus[F[_]] =
    SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotArtifact, CurrencySnapshotEvent]

}
