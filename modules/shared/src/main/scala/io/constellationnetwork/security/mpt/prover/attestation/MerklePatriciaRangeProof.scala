package io.constellationnetwork.security.mpt.prover.attestation

import io.constellationnetwork.security.hex.Hex

import io.circe._
import io.circe.syntax.EncoderOps

final case class MerklePatriciaRangeProof(
  startPath: Hex,
  endPath: Hex,
  inclusionProofs: List[MerklePatriciaInclusionProof],
  exclusionBoundaries: Option[RangeExclusionBoundaries]
)

final case class RangeExclusionBoundaries(
  leftBoundary: Option[MerklePatriciaInclusionProof],
  rightBoundary: Option[MerklePatriciaInclusionProof]
)

object RangeExclusionBoundaries {

  implicit val exclusionBoundariesEncoder: Encoder[RangeExclusionBoundaries] =
    (boundaries: RangeExclusionBoundaries) =>
      Json.obj(
        "leftBoundary" -> boundaries.leftBoundary.asJson,
        "rightBoundary" -> boundaries.rightBoundary.asJson
      )

  implicit val exclusionBoundariesDecoder: Decoder[RangeExclusionBoundaries] = (c: HCursor) =>
    for {
      leftBoundary <- c.downField("leftBoundary").as[Option[MerklePatriciaInclusionProof]]
      rightBoundary <- c.downField("rightBoundary").as[Option[MerklePatriciaInclusionProof]]
    } yield RangeExclusionBoundaries(leftBoundary, rightBoundary)
}

object MerklePatriciaRangeProof {

  implicit val rangeProofEncoder: Encoder[MerklePatriciaRangeProof] =
    (proof: MerklePatriciaRangeProof) =>
      Json.obj(
        "startPath" -> proof.startPath.asJson,
        "endPath" -> proof.endPath.asJson,
        "inclusionProofs" -> proof.inclusionProofs.asJson,
        "exclusionBoundaries" -> proof.exclusionBoundaries.asJson
      )

  implicit val rangeProofDecoder: Decoder[MerklePatriciaRangeProof] = (c: HCursor) =>
    for {
      startPath <- c.downField("startPath").as[Hex]
      endPath <- c.downField("endPath").as[Hex]
      inclusionProofs <- c.downField("inclusionProofs").as[List[MerklePatriciaInclusionProof]]
      exclusionBoundaries <- c.downField("exclusionBoundaries").as[Option[RangeExclusionBoundaries]]
    } yield MerklePatriciaRangeProof(startPath, endPath, inclusionProofs, exclusionBoundaries)
}
