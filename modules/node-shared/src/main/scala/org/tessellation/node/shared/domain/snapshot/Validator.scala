package org.tessellation.node.shared.domain.snapshot

import cats.syntax.order._

import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.schema.height.SubHeight
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.security.Hashed

object Validator {

  def isNextSnapshot[S <: Snapshot](previous: Hashed[S], next: S): Boolean =
    compare[S](previous, next).isInstanceOf[Next]

  def compare[S <: Snapshot](previous: Hashed[S], next: S): ComparisonResult = {
    val isLastSnapshotHashCorrect = previous.hash === next.lastSnapshotHash
    lazy val isNextOrdinal = previous.ordinal.next === next.ordinal
    lazy val isNextHeight = previous.height < next.height && next.subHeight === SubHeight.MinValue
    lazy val isNextSubHeight = previous.height === next.height && previous.subHeight.next === next.subHeight

    if (isLastSnapshotHashCorrect && isNextOrdinal && isNextHeight)
      NextHeight
    else if (isLastSnapshotHashCorrect && isNextOrdinal && isNextSubHeight)
      NextSubHeight
    else
      NotNext
  }

  sealed trait ComparisonResult
  case object NotNext extends ComparisonResult
  sealed trait Next extends ComparisonResult
  case object NextHeight extends Next
  case object NextSubHeight extends Next
}
