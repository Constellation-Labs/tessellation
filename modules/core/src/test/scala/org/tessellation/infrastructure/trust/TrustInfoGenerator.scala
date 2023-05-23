package org.tessellation.infrastructure.trust

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.TrustInfo

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

object TrustInfoGenerator {

  val genDouble: Gen[Double] = arbitrary[Double]

  val genPeerId: Gen[PeerId] = arbitrary[PeerId]

  val genPeerLabel: Gen[(PeerId, Double)] = for {
    peer <- genPeerId
    label <- genDouble
  } yield (peer, label)

  val genTrustInfo: Gen[TrustInfo] = for {
    label <- Gen.option(genDouble)
    predicted <- Gen.option(genDouble)
    observed <- Gen.option(genDouble)
    peerLabels <- Gen.mapOfN(3, genPeerLabel)
  } yield TrustInfo(label, predicted, observed, peerLabels)

  val genPeerTrustInfo: Gen[(PeerId, TrustInfo)] = for {
    peer <- genPeerId
    trust <- genTrustInfo
  } yield (peer, trust)

}
