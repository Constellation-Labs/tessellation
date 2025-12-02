package io.constellationnetwork.security.mpt.prover.attestation

import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaCommitment

import io.circe._
import io.circe.syntax.EncoderOps

final case class MerklePatriciaBatchInclusionProof(
  paths: List[Hex],
  witness: List[MerklePatriciaCommitment]
)

object MerklePatriciaBatchInclusionProof {

  implicit val batchProofEncoder: Encoder[MerklePatriciaBatchInclusionProof] =
    (proof: MerklePatriciaBatchInclusionProof) =>
      Json.obj(
        "paths" -> proof.paths.asJson,
        "witness" -> proof.witness.asJson
      )

  implicit val batchProofDecoder: Decoder[MerklePatriciaBatchInclusionProof] = (c: HCursor) =>
    for {
      paths <- c.downField("paths").as[List[Hex]]
      witness <- c.downField("witness").as[List[MerklePatriciaCommitment]]
    } yield MerklePatriciaBatchInclusionProof(paths, witness)
}
