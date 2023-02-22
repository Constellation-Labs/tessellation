package org.tessellation.currency.infrastructure

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshot, CurrencyTransaction}
import org.tessellation.sdk.infrastructure.snapshot._

package object snapshot {

  type CurrencySnapshotEvent = SnapshotEvent[CurrencyTransaction, CurrencyBlock]

  type CurrencySnapshotKey = SnapshotKey

  type CurrencySnapshotArtifact =
    SnapshotArtifact[CurrencyTransaction, CurrencyBlock, CurrencySnapshot]

  type CurrencySnapshotConsensus[F[_]] = SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot]

}
