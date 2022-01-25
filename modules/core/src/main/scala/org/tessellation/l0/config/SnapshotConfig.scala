package org.tessellation.l0.config
import scala.concurrent.duration.FiniteDuration

import fs2.io.file.Path

case class SnapshotConfig(
  storedSnapshotPath: Path,
  fallbackTriggerTimeout: FiniteDuration
)
