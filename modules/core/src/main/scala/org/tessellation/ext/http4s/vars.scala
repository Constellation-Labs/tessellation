package org.tessellation.ext.http4s

import scala.util.Try

import org.tessellation.dag.snapshot.SnapshotOrdinal
import org.tessellation.schema.address.{Address, DAGAddressRefined}

import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV

object vars {

  object AddressVar {
    def unapply(str: String): Option[Address] = refineV[DAGAddressRefined](str).toOption.map(Address(_))
  }

  object SnapshotOrdinalVar {

    def unapply(str: String): Option[SnapshotOrdinal] =
      Try(str.toLong).toOption
        .flatMap(refineV[NonNegative](_).toOption)
        .map(SnapshotOrdinal(_))
  }
}
