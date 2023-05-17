package org.tessellation.currency.l0

import org.tessellation.currency.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency._
import org.tessellation.sdk.infrastructure.snapshot._
import org.tessellation.security.signature.Signed

package object snapshot {

  type CurrencySnapshotEvent = Either[Signed[CurrencyBlock], Signed[DataApplicationBlock]]

  type CurrencySnapshotArtifact = CurrencyIncrementalSnapshot

  type CurrencySnapshotContext = CurrencySnapshotInfo

  type CurrencySnapshotConsensus[F[_]] =
    SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent]

}
