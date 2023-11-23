package org.tessellation.currency.l0

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency._
import org.tessellation.node.shared.infrastructure.snapshot._
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

package object snapshot {

  type CurrencySnapshotEvent = Either[Signed[Block], Signed[DataApplicationBlock]]

  type CurrencySnapshotArtifact = CurrencyIncrementalSnapshot

  type CurrencySnapshotConsensus[F[_]] =
    SnapshotConsensus[F, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent]

}
