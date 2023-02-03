package org.tessellation.sdk.domain.snapshot

import cats.syntax.order._

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.schema.height.SubHeight
import org.tessellation.security.Hashed

object Validator {

  def isNextSnapshot(previous: Hashed[GlobalSnapshot], next: Hashed[GlobalSnapshot]): Boolean = {
    val isLastSnapshotHashCorrect = previous.hash === next.lastSnapshotHash
    lazy val isNextOrdinal = previous.ordinal.next === next.ordinal
    lazy val isNextHeight = previous.height < next.height && next.subHeight === SubHeight.MinValue
    lazy val isNextSubHeight = previous.height === next.height && previous.subHeight.next === next.subHeight

    isLastSnapshotHashCorrect && isNextOrdinal && (isNextHeight || isNextSubHeight)
  }
}
