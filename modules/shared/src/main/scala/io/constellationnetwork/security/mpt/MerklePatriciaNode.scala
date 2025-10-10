package io.constellationnetwork.security.mpt

import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.MerklePatriciaCommitment.Extension.extensionCommitEncoder

import io.circe._
import io.circe.syntax.EncoderOps

sealed trait MerklePatriciaNode {
  def digest: Hash
}

object MerklePatriciaNode {
  private[mpt] val LeafPrefix: Array[Byte] = Array(0: Byte)
  private[mpt] val BranchPrefix: Array[Byte] = Array(1: Byte)
  private[mpt] val ExtensionPrefix: Array[Byte] = Array(2: Byte)

  final case class Leaf private (remaining: Seq[Nibble], data: Json, digest: Hash) extends MerklePatriciaNode
  final case class Branch private (paths: Map[Nibble, MerklePatriciaNode], digest: Hash) extends MerklePatriciaNode
  final case class Extension private (shared: Seq[Nibble], child: Branch, digest: Hash) extends MerklePatriciaNode

  object Leaf {

    def apply[F[_]: Sync: Hasher](remaining: Seq[Nibble], data: Json): F[Leaf] = for {
      dataDigest <- Hasher[F].hash(data)
      commitment <- MerklePatriciaCommitment.Leaf(remaining, dataDigest).pure[F]
      nodeDigest <- Hasher[F].prefixedHash(commitment.asJson, LeafPrefix)
    } yield Leaf(remaining, data, nodeDigest)

    implicit val leafNodeEncoder: Encoder[Leaf] =
      Encoder.instance { node =>
        Json.obj(
          "remaining" -> node.remaining.asJson(Nibble.nibbleSeqEncoder),
          "data"      -> node.data.asJson,
          "digest"    -> node.digest.asJson
        )
      }

    implicit val leafNodeDecoder: Decoder[Leaf] =
      Decoder.instance { hCursor =>
        for {
          remaining <- hCursor.downField("remaining").as[Seq[Nibble]](Nibble.nibbleSeqDecoder)
          data      <- hCursor.downField("data").as[Json]
          digest    <- hCursor.downField("digest").as[Hash]
        } yield new Leaf(remaining, data, digest)
      }
  }

  object Branch {

    def apply[F[_]: Sync: Hasher](paths: Map[Nibble, MerklePatriciaNode]): F[Branch] = for {
      pathDigests <- paths.toSeq.sortBy(_._1.value).map { case (k, v) => k -> v.digest }.toMap.pure[F]
      commitment  <- MerklePatriciaCommitment.Branch(pathDigests).pure[F]
      nodeDigest  <- Hasher[F].prefixedHash(commitment.asJson, BranchPrefix)
    } yield Branch(paths, nodeDigest)

    implicit val encodeBranchNode: Encoder[Branch] =
      Encoder.instance { node =>
        Json.obj(
          "paths"  -> node.paths.toSeq.sortBy(_._1.value).toMap.asJson,
          "digest" -> node.digest.asJson
        )
      }

    implicit val decodeBranchNode: Decoder[Branch] =
      Decoder.instance { hCursor =>
        for {
          children <- hCursor.downField("paths").as[Map[Nibble, MerklePatriciaNode]]
          digest   <- hCursor.downField("digest").as[Hash]
        } yield new Branch(children, digest)
      }
  }

  object Extension {

    def apply[F[_]: Sync: Hasher](shared: Seq[Nibble], child: Branch): F[Extension] = for {
      commitment <- MerklePatriciaCommitment.Extension(shared, child.digest).pure[F]
      nodeDigest <- Hasher[F].prefixedHash(commitment, ExtensionPrefix)
    } yield Extension(shared, child, nodeDigest)

    implicit val encodeExtensionNode: Encoder[Extension] =
      Encoder.instance { node =>
        Json.obj(
          "shared" -> node.shared.asJson(Nibble.nibbleSeqEncoder),
          "child"  -> (node.child: MerklePatriciaNode).asJson,
          "digest" -> node.digest.asJson
        )
      }

    implicit val decodeExtensionNode: Decoder[Extension] =
      Decoder.instance { hCursor =>
        for {
          shared <- hCursor.downField("shared").as[Seq[Nibble]](Nibble.nibbleSeqDecoder)
          child  <- hCursor.downField("child").downField("contents").as[Branch]
          digest <- hCursor.downField("digest").as[Hash]
        } yield new Extension(shared, child, digest)
      }
  }

  implicit val encodeMptNode: Encoder[MerklePatriciaNode] = Encoder.instance {
    case node: Leaf =>
      Json.obj(
        "type"     -> Json.fromString("Leaf"),
        "contents" -> node.asJson
      )
    case node: Extension =>
      Json.obj(
        "type"     -> Json.fromString("Extension"),
        "contents" -> node.asJson
      )
    case node: Branch =>
      Json.obj(
        "type"     -> Json.fromString("Branch"),
        "contents" -> node.asJson
      )
  }

  implicit val decodeMptNode: Decoder[MerklePatriciaNode] = Decoder.instance { cursor =>
    cursor.downField("type").as[String].flatMap {
      case "Leaf"      => cursor.downField("contents").as[Leaf]
      case "Extension" => cursor.downField("contents").as[Extension]
      case "Branch"    => cursor.downField("contents").as[Branch]
      case other       => Left(DecodingFailure(s"Unknown type: $other", cursor.history))
    }
  }
}