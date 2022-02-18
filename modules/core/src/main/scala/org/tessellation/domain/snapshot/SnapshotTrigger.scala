package org.tessellation.domain.snapshot

import org.tessellation.schema.height.Height

sealed trait SnapshotTrigger

case class TipSnapshotTrigger(height: Height) extends SnapshotTrigger

case class TimeSnapshotTrigger() extends SnapshotTrigger
