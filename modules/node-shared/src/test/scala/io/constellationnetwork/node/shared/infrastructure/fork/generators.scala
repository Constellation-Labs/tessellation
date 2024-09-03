package io.constellationnetwork.node.shared.infrastructure.fork

import io.constellationnetwork.node.shared.domain.fork.ForkInfo
import io.constellationnetwork.schema.generators.{peerIdGen, snapshotOrdinalGen}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import org.scalacheck.{Arbitrary, Gen}

object generators {
  val genStoredForkInfoEntry: Gen[(PeerId, ForkInfo)] = for {
    peerId <- peerIdGen
    ordinal <- snapshotOrdinalGen
    hash <- Arbitrary.arbitrary[Hash]
  } yield (peerId, ForkInfo(ordinal, hash))
}
