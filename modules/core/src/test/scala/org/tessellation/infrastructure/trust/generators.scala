package org.tessellation.infrastructure.trust

import org.tessellation.schema.generators.{chooseNumRefined, peerIdGen}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._

import eu.timepit.refined.api.Refined
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

object generators {

  val genPeerLabel: Gen[(PeerId, Double)] = for {
    peer <- peerIdGen
    label <- arbitrary[Double]
  } yield (peer, label)

  val genTrustInfo: Gen[TrustInfo] = for {
    label <- Gen.option(arbitrary[Double])
    predicted <- Gen.option(arbitrary[Double])
    observed <- Gen.option(arbitrary[Double])
    peerLabels <- Gen.mapOfN(3, genPeerLabel)
  } yield TrustInfo(label, predicted, observed, peerLabels)

  val genPeerTrustInfo: Gen[(PeerId, TrustInfo)] = for {
    peer <- peerIdGen
    trust <- genTrustInfo
  } yield (peer, trust)

  val genPeerObservationAdjustmentUpdate: Gen[PeerObservationAdjustmentUpdate] = for {
    peerId <- peerIdGen
    label <- chooseNumRefined(
      Refined.unsafeApply[Double, TrustValueRefinement](-1.0),
      Refined.unsafeApply[Double, TrustValueRefinement](1.0)
    )
  } yield PeerObservationAdjustmentUpdate(peerId, label)

  val genPeerPublicTrust: Gen[(PeerId, PublicTrust)] = {
    val gen = for {
      peer <- peerIdGen
      label <- Gen.chooseNum(-1.0, 1.0)
    } yield (peer, label)

    for {
      peer <- peerIdGen
      labels <- Gen.mapOfN(4, gen)
      publicTrust = PublicTrust(labels)
    } yield (peer, publicTrust)
  }

}
