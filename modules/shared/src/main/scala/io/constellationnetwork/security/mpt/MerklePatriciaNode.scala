package io.constellationnetwork.security.mpt

import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Eq, Order, Show}

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.MerklePatriciaCommitment.Extension.extensionCommitEncoder

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps

@derive(decoder, encoder, eqv, show, order)
case class MptRoot(value: Hash) extends AnyVal

sealed trait MerklePatriciaNode {
  def digest: Hash
}

object MerklePatriciaNode {
  private[mpt] val LeafPrefix: Array[Byte] = Array(0: Byte)
  private[mpt] val BranchPrefix: Array[Byte] = Array(1: Byte)
  private[mpt] val ExtensionPrefix: Array[Byte] = Array(2: Byte)

  /** Leaf node with memory-optimized path storage.
    *
    * Uses CompactNibblePath internally (2 nibbles per byte) but converts to Seq[Nibble] for hashing to maintain blockchain compatibility.
    *
    * Memory savings: ~16x reduction for path storage (64-nibble path: ~1KB -> ~40 bytes)
    */
  final case class Leaf private (
    private val remainingCompact: CompactNibblePath,
    data: Json,
    digest: Hash
  ) extends MerklePatriciaNode {

    /** Get remaining path as Seq[Nibble] for compatibility. */
    def remaining: Seq[Nibble] = remainingCompact.toNibbleSeq

    /** Get remaining path in compact form for efficient operations. */
    def remainingPath: CompactNibblePath = remainingCompact
  }

  /** Branch node with optimized child map using Byte keys instead of Nibble.
    *
    * Using Byte keys avoids Nibble boxing in the map keys. There are at most 16 children (nibble values 0-15).
    */
  final case class Branch private (
    private val pathsInternal: Map[Byte, MerklePatriciaNode],
    digest: Hash
  ) extends MerklePatriciaNode {

    /** Get paths with Nibble keys for compatibility. */
    def paths: Map[Nibble, MerklePatriciaNode] =
      pathsInternal.map { case (k, v) => Nibble.unsafe(k) -> v }

    /** Get child by nibble value directly (avoids Nibble boxing). Use this for efficient operations. For Nibble, use
      * getChild(nibble.value).
      */
    def getChild(nibbleValue: Byte): Option[MerklePatriciaNode] =
      pathsInternal.get(nibbleValue)

    /** Check if has child for nibble value. */
    def hasChild(nibbleValue: Byte): Boolean =
      pathsInternal.contains(nibbleValue)

    /** Internal map for efficient operations. */
    private[mpt] def internalPaths: Map[Byte, MerklePatriciaNode] = pathsInternal

    /** Number of children. */
    def childCount: Int = pathsInternal.size

    /** Iterate over children with byte keys (avoids boxing). */
    def foreachChild(f: (Byte, MerklePatriciaNode) => Unit): Unit =
      pathsInternal.foreach { case (k, v) => f(k, v) }
  }

  /** Extension node with memory-optimized shared path storage.
    */
  final case class Extension private (
    private val sharedCompact: CompactNibblePath,
    child: Branch,
    digest: Hash
  ) extends MerklePatriciaNode {

    /** Get shared path as Seq[Nibble] for compatibility. */
    def shared: Seq[Nibble] = sharedCompact.toNibbleSeq

    /** Get shared path in compact form for efficient operations. */
    def sharedPath: CompactNibblePath = sharedCompact
  }

  object Leaf {

    def apply[F[_]: Sync: Hasher](remaining: Seq[Nibble], data: Json): F[Leaf] =
      for {
        dataDigest <- Hasher[F].hash(data)
        leaf <- withDataDigest[F](remaining, data, dataDigest)
      } yield leaf

    /** Create from CompactNibblePath (preferred for new code).
      */
    def fromCompact[F[_]: Sync: Hasher](remaining: CompactNibblePath, data: Json): F[Leaf] =
      for {
        dataDigest <- Hasher[F].hash(data)
        leaf <- withDataDigestCompact[F](remaining, data, dataDigest)
      } yield leaf

    /** Fast path: create leaf when data digest is already computed. This saves one hash operation per leaf when batch-hashing.
      */
    def withDataDigest[F[_]: Sync: Hasher](remaining: Seq[Nibble], data: Json, dataDigest: Hash): F[Leaf] = {
      val remainingCompact = CompactNibblePath.fromNibbleSeq(remaining)
      withDataDigestCompact[F](remainingCompact, data, dataDigest)
    }

    /** Fast path with CompactNibblePath (avoids conversion).
      */
    def withDataDigestCompact[F[_]: Sync: Hasher](remaining: CompactNibblePath, data: Json, dataDigest: Hash): F[Leaf] = {
      // CRITICAL: Use toNibbleSeq for hashing to maintain blockchain compatibility
      val commitment = MerklePatriciaCommitment.Leaf(remaining.toNibbleSeq, dataDigest)
      Hasher[F].prefixedHash(commitment.asJson, LeafPrefix).map { nodeDigest =>
        new Leaf(remaining, data, nodeDigest)
      }
    }

    implicit val leafNodeEncoder: Encoder[Leaf] =
      Encoder.instance { node =>
        Json.obj(
          "remaining" -> node.remaining.asJson(Nibble.nibbleSeqEncoder),
          "data" -> node.data.asJson,
          "digest" -> node.digest.asJson
        )
      }

    implicit val leafNodeDecoder: Decoder[Leaf] =
      Decoder.instance { hCursor =>
        for {
          remaining <- hCursor.downField("remaining").as[Seq[Nibble]](Nibble.nibbleSeqDecoder)
          data <- hCursor.downField("data").as[Json]
          digest <- hCursor.downField("digest").as[Hash]
        } yield new Leaf(CompactNibblePath.fromNibbleSeq(remaining), data, digest)
      }
  }

  object Branch {

    def apply[F[_]: Sync: Hasher](paths: Map[Nibble, MerklePatriciaNode]): F[Branch] = {
      // Convert to byte-keyed map
      val byteKeyedPaths: Map[Byte, MerklePatriciaNode] = paths.map { case (k, v) => k.value -> v }
      fromByteKeys(byteKeyedPaths)
    }

    /** Create from byte-keyed map (preferred for new code, avoids Nibble boxing).
      */
    def fromByteKeys[F[_]: Sync: Hasher](paths: Map[Byte, MerklePatriciaNode]): F[Branch] = {
      // For hashing, we need Nibble keys (blockchain compatibility)
      val pathDigests: Map[Nibble, Hash] = {
        val builder = Map.newBuilder[Nibble, Hash]
        builder.sizeHint(paths.size)
        paths.foreach { case (k, v) => builder += (Nibble.unsafe(k) -> v.digest) }
        builder.result()
      }
      val commitment = MerklePatriciaCommitment.Branch(pathDigests)
      Hasher[F].prefixedHash(commitment.asJson, BranchPrefix).map { nodeDigest =>
        new Branch(paths, nodeDigest)
      }
    }

    /** Fast path: create branch when child digests are already extracted. Avoids re-iterating the paths map.
      */
    def fromDigests[F[_]: Sync: Hasher](
      paths: Map[Nibble, MerklePatriciaNode],
      pathDigests: Map[Nibble, Hash]
    ): F[Branch] = {
      val commitment = MerklePatriciaCommitment.Branch(pathDigests)
      val byteKeyedPaths: Map[Byte, MerklePatriciaNode] = paths.map { case (k, v) => k.value -> v }
      Hasher[F].prefixedHash(commitment.asJson, BranchPrefix).map { nodeDigest =>
        new Branch(byteKeyedPaths, nodeDigest)
      }
    }

    /** Fast path with byte keys and pre-computed digests.
      */
    def fromByteKeysWithDigests[F[_]: Sync: Hasher](
      paths: Map[Byte, MerklePatriciaNode],
      pathDigests: Map[Nibble, Hash]
    ): F[Branch] = {
      val commitment = MerklePatriciaCommitment.Branch(pathDigests)
      Hasher[F].prefixedHash(commitment.asJson, BranchPrefix).map { nodeDigest =>
        new Branch(paths, nodeDigest)
      }
    }

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

    def apply[F[_]: Sync: Hasher](shared: Seq[Nibble], child: Branch): F[Extension] = {
      val sharedCompact = CompactNibblePath.fromNibbleSeq(shared)
      fromCompact[F](sharedCompact, child)
    }

    /** Create from CompactNibblePath (preferred for new code).
      */
    def fromCompact[F[_]: Sync: Hasher](shared: CompactNibblePath, child: Branch): F[Extension] = {
      // CRITICAL: Use toNibbleSeq for hashing to maintain blockchain compatibility
      val commitment = MerklePatriciaCommitment.Extension(shared.toNibbleSeq, child.digest)
      Hasher[F].prefixedHash(commitment, ExtensionPrefix).map { nodeDigest =>
        new Extension(shared, child, nodeDigest)
      }
    }

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
        } yield new Extension(CompactNibblePath.fromNibbleSeq(shared), child, digest)
      }
  }

  implicit val encodeMptNode: Encoder[MerklePatriciaNode] = Encoder.instance {
    case node: Leaf =>
      Json.obj(
        "type" -> Json.fromString("Leaf"),
        "contents" -> node.asJson
      )
    case node: Extension =>
      Json.obj(
        "type" -> Json.fromString("Extension"),
        "contents" -> node.asJson
      )
    case node: Branch =>
      Json.obj(
        "type" -> Json.fromString("Branch"),
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
