package org.tessellation.node.shared.infrastructure.fork

import org.tessellation.node.shared.domain.fork.ForkInfo
import org.tessellation.schema.generators.{peerIdGen, snapshotOrdinalGen}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash

import org.scalacheck.{Arbitrary, Gen}

object generators {
  val genStoredForkInfoEntry: Gen[(PeerId, ForkInfo)] = for {
    peerId <- peerIdGen
    ordinal <- snapshotOrdinalGen
    hash <- Arbitrary.arbitrary[Hash]
  } yield (peerId, ForkInfo(ordinal, hash))
}
