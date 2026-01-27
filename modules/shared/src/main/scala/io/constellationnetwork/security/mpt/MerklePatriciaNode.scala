package io.constellationnetwork.security.mpt

import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps

@derive(decoder, encoder, eqv, show, order)
case class MptRoot(value: Hash) extends AnyVal

/** Immutable Merkle Patricia Trie node with pre-computed digest. Hash is computed at construction time - no caching or invalidation needed.
  *
  * MEMORY OPTIMIZATION: Paths are stored as CompactNibblePath (2 nibbles per byte) instead of Seq[Nibble] (1 object per nibble). This
  * reduces memory by ~20-40x for path storage.
  */
sealed trait MerklePatriciaNode {
  def digest: Hash
}

object MerklePatriciaNode {
  private[mpt] val LeafPrefix: Array[Byte] = Array(0: Byte)
  private[mpt] val BranchPrefix: Array[Byte] = Array(1: Byte)
  private[mpt] val ExtensionPrefix: Array[Byte] = Array(2: Byte)

  /** Leaf node with compact path storage. Uses CompactNibblePath internally but serializes as Seq[Nibble] for compatibility.
    */
  final case class Leaf private (
    remainingCompact: CompactNibblePath,
    dataDigest: Hash,
    digest: Hash
  ) extends MerklePatriciaNode {

    /** Get remaining path as Seq[Nibble] for compatibility */
    def remaining: Seq[Nibble] = remainingCompact.toNibbleSeq

    /** Get remaining path as CompactNibblePath for efficient operations */
    def remainingPath: CompactNibblePath = remainingCompact
  }

  /** Branch node with byte-keyed children for memory efficiency. Uses Map[Byte, Node] instead of Map[Nibble, Node] to avoid Nibble boxing.
    */
  final case class Branch private (
    pathsInternal: Map[Byte, MerklePatriciaNode],
    digest: Hash
  ) extends MerklePatriciaNode {
    def dataDigest: Hash = Hash.empty

    /** Get paths as Map[Nibble, Node] for compatibility */
    def paths: Map[Nibble, MerklePatriciaNode] =
      pathsInternal.map { case (k, v) => Nibble.unsafe(k) -> v }

    /** Get child by nibble byte value - avoids Nibble allocation */
    def getChild(nibbleValue: Byte): Option[MerklePatriciaNode] =
      pathsInternal.get(nibbleValue)

    /** Get internal byte-keyed map for efficient operations */
    def internalPaths: Map[Byte, MerklePatriciaNode] = pathsInternal

    /** Number of children */
    def childCount: Int = pathsInternal.size
  }

  /** Extension node with compact path storage. Uses CompactNibblePath internally but serializes as Seq[Nibble] for compatibility.
    */
  final case class Extension private (
    sharedCompact: CompactNibblePath,
    child: Branch,
    digest: Hash
  ) extends MerklePatriciaNode {
    def dataDigest: Hash = Hash.empty

    /** Get shared path as Seq[Nibble] for compatibility */
    def shared: Seq[Nibble] = sharedCompact.toNibbleSeq

    /** Get shared path as CompactNibblePath for efficient operations */
    def sharedPath: CompactNibblePath = sharedCompact
  }

  object Leaf {

    /** Create a leaf node from Seq[Nibble] - computes digest immediately.
      */
    def apply[F[_]: Sync: Hasher](remaining: Seq[Nibble], dataDigest: Hash): F[Leaf] =
      for {
        commitment <- MerklePatriciaCommitment.Leaf(remaining, dataDigest).pure[F]
        nodeDigest <- Hasher[F].prefixedHash(commitment.asJson, LeafPrefix)
      } yield new Leaf(CompactNibblePath.fromNibbles(remaining), dataDigest, nodeDigest)

    /** Create from CompactNibblePath for convenience.
      */
    def fromCompact[F[_]: Sync: Hasher](remaining: CompactNibblePath, dataDigest: Hash): F[Leaf] =
      apply(remaining.toNibbleSeq, dataDigest)

    /** Create from CompactNibblePath with pre-computed data digest.
      */
    def fromDataDigest[F[_]: Sync: Hasher](remaining: CompactNibblePath, dataDigest: Hash): F[Leaf] =
      fromCompact(remaining, dataDigest)

    implicit val leafNodeEncoder: Encoder[Leaf] =
      Encoder.instance { node =>
        Json.obj(
          "remaining" -> node.remaining.asJson(Nibble.nibbleSeqEncoder),
          "dataDigest" -> node.dataDigest.asJson,
          "digest" -> node.digest.asJson
        )
      }

    implicit val leafNodeDecoder: Decoder[Leaf] =
      Decoder.instance { hCursor =>
        for {
          remaining <- hCursor.downField("remaining").as[Seq[Nibble]](Nibble.nibbleSeqDecoder)
          dataDigest <- hCursor.downField("dataDigest").as[Hash]
          digest <- hCursor.downField("digest").as[Hash]
        } yield new Leaf(CompactNibblePath.fromNibbles(remaining), dataDigest, digest)
      }
  }

  object Branch {

    /** Create a branch node from Map[Nibble, Node] - computes digest immediately.
      */
    def apply[F[_]: Sync: Hasher](paths: Map[Nibble, MerklePatriciaNode]): F[Branch] =
      for {
        pathDigests <- paths.toSeq.sortBy(_._1.value).map { case (k, v) => k -> v.digest }.toMap.pure[F]
        commitment <- MerklePatriciaCommitment.Branch(pathDigests).pure[F]
        nodeDigest <- Hasher[F].prefixedHash(commitment.asJson, BranchPrefix)
      } yield new Branch(paths.map { case (k, v) => k.value -> v }, nodeDigest)

    /** Create from byte-keyed map for convenience - avoids Nibble allocations.
      */
    def fromByteKeys[F[_]: Sync: Hasher](paths: Map[Byte, MerklePatriciaNode]): F[Branch] =
      apply(paths.map { case (k, v) => Nibble.unsafe(k) -> v })

    /** Create empty branch.
      */
    def empty[F[_]: Sync: Hasher]: F[Branch] =
      apply(Map.empty)

    implicit val encodeBranchNode: Encoder[Branch] =
      Encoder.instance { node =>
        Json.obj(
          "paths" -> node.paths.toSeq.sortBy(_._1.value).toMap.asJson,
          "digest" -> node.digest.asJson
        )
      }

    implicit val decodeBranchNode: Decoder[Branch] =
      Decoder.instance { hCursor =>
        for {
          children <- hCursor.downField("paths").as[Map[Nibble, MerklePatriciaNode]]
          digest <- hCursor.downField("digest").as[Hash]
        } yield new Branch(children.map { case (k, v) => k.value -> v }, digest)
      }
  }

  object Extension {

    /** Create an extension node from Seq[Nibble] - computes digest immediately.
      */
    def apply[F[_]: Sync: Hasher](shared: Seq[Nibble], child: Branch): F[Extension] =
      for {
        commitment <- MerklePatriciaCommitment.Extension(shared, child.digest).pure[F]
        nodeDigest <- Hasher[F].prefixedHash(commitment.asJson, ExtensionPrefix)
      } yield new Extension(CompactNibblePath.fromNibbles(shared), child, nodeDigest)

    /** Create from CompactNibblePath for convenience.
      */
    def fromCompact[F[_]: Sync: Hasher](shared: CompactNibblePath, child: Branch): F[Extension] =
      apply(shared.toNibbleSeq, child)

    implicit val encodeExtensionNode: Encoder[Extension] =
      Encoder.instance { node =>
        Json.obj(
          "shared" -> node.shared.asJson(Nibble.nibbleSeqEncoder),
          "child" -> (node.child: MerklePatriciaNode).asJson,
          "digest" -> node.digest.asJson
        )
      }

    implicit val decodeExtensionNode: Decoder[Extension] =
      Decoder.instance { hCursor =>
        for {
          shared <- hCursor.downField("shared").as[Seq[Nibble]](Nibble.nibbleSeqDecoder)
          child <- hCursor.downField("child").downField("contents").as[Branch]
          digest <- hCursor.downField("digest").as[Hash]
        } yield new Extension(CompactNibblePath.fromNibbles(shared), child, digest)
      }
  }

  implicit val encodeMptNode: Encoder[MerklePatriciaNode] = Encoder.instance {
    case node: Leaf =>
      Json.obj("type" -> Json.fromString("Leaf"), "contents" -> node.asJson)
    case node: Extension =>
      Json.obj("type" -> Json.fromString("Extension"), "contents" -> node.asJson)
    case node: Branch =>
      Json.obj("type" -> Json.fromString("Branch"), "contents" -> node.asJson)
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
