package org.tessellation.node.shared.snapshot

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency._
import org.tessellation.schema.Block
import org.tessellation.security.signature.Signed

object currency {

  type CurrencySnapshotEvent = Either[Signed[Block], Signed[DataApplicationBlock]]

  type CurrencySnapshotArtifact = CurrencyIncrementalSnapshot
}
