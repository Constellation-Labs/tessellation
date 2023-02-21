package org.tessellation.sdk.ext.http4s

import scala.util.Try

import org.tessellation.schema.SnapshotOrdinal

import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV

object SnapshotOrdinalVar {

  def unapply(str: String): Option[SnapshotOrdinal] =
    Try(str.toLong).toOption
      .flatMap(refineV[NonNegative](_).toOption)
      .map(SnapshotOrdinal(_))
}
