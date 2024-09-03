package io.constellationnetwork.dag.l0.infrastructure.trust

import cats.syntax.applicative._

import io.constellationnetwork.dag.l0.infrastructure.trust.TrustModel.calculateTrust
import io.constellationnetwork.dag.l0.infrastructure.trust.generators.{genPeerPublicTrust, genPeerTrustInfo}
import io.constellationnetwork.node.shared.domain.trust.storage.{PublicTrustMap, TrustMap}
import io.constellationnetwork.schema.generators.peerIdGen

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TrustModelSuite extends SimpleIOSuite with Checkers {
  test("peer labels contain undiscovered peers does not throw an exception") {
    val gen = for {
      trust <- Gen.mapOfN(4, genPeerTrustInfo)
      labels <- Gen.mapOfN(4, genPeerPublicTrust)
      publicTrustMap = PublicTrustMap(labels)
      peerId <- peerIdGen
    } yield (TrustMap(trust, publicTrustMap), peerId)

    forall(gen) {
      case (trust, selfId) =>
        for {
          trustKeySet <- trust.trust.keySet.pure
          labelKeySet = trust.peerLabels.value.keySet
          result <- calculateTrust(trust, selfId).pure.attempt
        } yield
          expect.all(
            labelKeySet.diff(trustKeySet).nonEmpty,
            result.isRight
          )
    }
  }

}
