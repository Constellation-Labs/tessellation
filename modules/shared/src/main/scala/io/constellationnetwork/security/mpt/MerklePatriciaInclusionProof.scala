package io.constellationnetwork.security.mpt

import io.constellationnetwork.security.hex.Hex

import io.circe._
import io.circe.syntax.EncoderOps

final case class MerklePatriciaInclusionProof(
  path: Hex,
  witness: List[MerklePatriciaCommitment]
)

object MerklePatriciaInclusionProof {

  implicit val mpInclusionProofEncoder: Encoder[MerklePatriciaInclusionProof] = (proof: MerklePatriciaInclusionProof) =>
    Json.obj(
      "path"    -> proof.path.asJson,
      "witness" -> proof.witness.asJson
    )

  implicit val mpInclusionProofDecoder: Decoder[MerklePatriciaInclusionProof] = (c: HCursor) =>
    for {
      path    <- c.downField("path").as[Hex]
      witness <- c.downField("witness").as[List[MerklePatriciaCommitment]]
    } yield MerklePatriciaInclusionProof(path, witness)
}