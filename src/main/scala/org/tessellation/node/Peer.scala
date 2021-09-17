package org.tessellation.node

import cats.data.NonEmptyList
import org.tessellation.majority.SnapshotStorage.MajorityHeight

case class Peer(host: String, port: Int, id: String = "", majorityHeight: NonEmptyList[MajorityHeight])
