package org.tessellation.infrastructure.trust

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.TrustInfo

object TrustModel {

  def calculateTrust(trustNodes: List[TrustNode], selfPeerIdx: Int): Map[Int, Double] = {

    val eigenTrustScores = EigenTrust.calculate(trustNodes)

    val dattScores = DATT.calculate(DATT.convert(trustNodes), selfPeerIdx)

    val walkScores = SelfAvoidingWalk
      .runWalkFeedbackUpdateSingleNode(selfPeerIdx, trustNodes)
      .edges
      .map(e => e.dst -> e.trust)
      .toMap

    walkScores.map {
      case (id, score) =>
        id ->
          ((score + dattScores.getOrElse(id, 0d) + eigenTrustScores.getOrElse(id, 0d)) / 3)
    }
  }

  def calculateTrust(trust: Map[PeerId, TrustInfo], selfPeerId: PeerId): Map[PeerId, Double] = {
    val selfTrustLabels = trust.flatMap { case (peerId, trustInfo) => trustInfo.publicTrust.map(peerId -> _) }
    val allNodesTrustLabels = trust.view.mapValues(_.peerLabels).toMap + (selfPeerId -> selfTrustLabels)

    val peerIdToIdx = allNodesTrustLabels.keys.zipWithIndex.toMap
    val idxToPeerId = peerIdToIdx.map(_.swap)
    val selfPeerIdx = peerIdToIdx(selfPeerId)

    val trustNodes = allNodesTrustLabels.map {
      case (peerId, labels) =>
        TrustNode(
          peerIdToIdx(peerId),
          0,
          0,
          labels.map {
            case (pid, label) =>
              TrustEdge(peerIdToIdx(peerId), peerIdToIdx(pid), label, peerId == selfPeerId)
          }.toList
        )
    }.toList
    calculateTrust(trustNodes, selfPeerIdx).map { case (k, v) => idxToPeerId(k) -> v }
  }

}
