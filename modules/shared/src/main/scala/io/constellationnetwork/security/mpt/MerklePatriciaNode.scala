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

sealed trait MerklePatriciaNode {

  /** Cached digest - None means dirty/not yet computed */
  def cachedDigest: Option[Hash]

  /** Check if this node needs rehashing */
  def isDirty: Boolean = cachedDigest.isEmpty
}

object MerklePatriciaNode {
  private[mpt] val LeafPrefix: Array[Byte] = Array(0: Byte)
  private[mpt] val BranchPrefix: Array[Byte] = Array(1: Byte)
  private[mpt] val ExtensionPrefix: Array[Byte] = Array(2: Byte)

  final case class Leaf private (
    private val remainingCompact: CompactNibblePath,
    dataDigest: Hash,
    cachedDigest: Option[Hash]
  ) extends MerklePatriciaNode {
    def remaining: Seq[Nibble] = remainingCompact.toNibbleSeq
    def remainingPath: CompactNibblePath = remainingCompact

    /** Mark as dirty (needs rehashing) */
    private[mpt] def markDirty: Leaf =
      if (cachedDigest.isEmpty) this else copy(cachedDigest = None)

    /** Set computed hash */
    private[mpt] def withDigest(hash: Hash): Leaf =
      copy(cachedDigest = Some(hash))
  }

  final case class Branch private[mpt] (
    private val pathsInternal: Map[Byte, MerklePatriciaNode],
    cachedDigest: Option[Hash]
  ) extends MerklePatriciaNode {

    def paths: Map[Nibble, MerklePatriciaNode] =
      pathsInternal.map { case (k, v) => Nibble.unsafe(k) -> v }

    def getChild(nibbleValue: Byte): Option[MerklePatriciaNode] =
      pathsInternal.get(nibbleValue)

    def hasChild(nibbleValue: Byte): Boolean =
      pathsInternal.contains(nibbleValue)

    def internalPaths: Map[Byte, MerklePatriciaNode] = pathsInternal

    def childCount: Int = pathsInternal.size

    def foreachChild(f: (Byte, MerklePatriciaNode) => Unit): Unit =
      pathsInternal.foreach { case (k, v) => f(k, v) }

    /** Update a child and mark this branch as dirty */
    def withUpdatedChild(nibble: Byte, child: MerklePatriciaNode): Branch =
      new Branch(pathsInternal.updated(nibble, child), None)

    /** Remove a child and mark as dirty */
    def withRemovedChild(nibble: Byte): Branch =
      new Branch(pathsInternal - nibble, None)

    /** Set computed hash */
    def withDigest(hash: Hash): Branch =
      copy(cachedDigest = Some(hash))

    /** Mark as dirty */
    def markDirty: Branch =
      if (cachedDigest.isEmpty) this else copy(cachedDigest = None)
  }

  final case class Extension private[mpt] (
    private val sharedCompact: CompactNibblePath,
    child: Branch,
    cachedDigest: Option[Hash]
  ) extends MerklePatriciaNode {
    def shared: Seq[Nibble] = sharedCompact.toNibbleSeq
    def sharedPath: CompactNibblePath = sharedCompact

    /** Update child and mark as dirty */
    def withUpdatedChild(newChild: Branch): Extension =
      new Extension(sharedCompact, newChild, None)

    /** Set computed hash */
    def withDigest(hash: Hash): Extension =
      copy(cachedDigest = Some(hash))

    /** Mark as dirty */
    def markDirty: Extension =
      if (cachedDigest.isEmpty) this else copy(cachedDigest = None)
  }

  object Leaf {

    /** Create dirty leaf (no hash computed yet) */
    def apply[F[_]: Sync](remaining: Seq[Nibble], dataDigest: Hash): F[Leaf] =
      new Leaf(CompactNibblePath.fromNibbleSeq(remaining), dataDigest, None).pure[F]

    def fromCompact[F[_]: Sync](remaining: CompactNibblePath, dataDigest: Hash): F[Leaf] =
      new Leaf(remaining, dataDigest, None).pure[F]

    def fromData[F[_]: Sync: Hasher](remaining: CompactNibblePath, data: Array[Byte]): F[(Leaf, Hash)] =
      for {
        dataDigest <- Hasher[F].hash(data)
      } yield (new Leaf(remaining, dataDigest, None), dataDigest)

    def fromDataDigest[F[_]: Sync](remaining: CompactNibblePath, dataDigest: Hash): F[Leaf] =
      new Leaf(remaining, dataDigest, None).pure[F]

    def fromDataDigestSeq[F[_]: Sync](remaining: Seq[Nibble], dataDigest: Hash): F[Leaf] =
      fromDataDigest(CompactNibblePath.fromNibbleSeq(remaining), dataDigest)

    implicit val leafNodeEncoder: Encoder[Leaf] =
      Encoder.instance { node =>
        Json.obj(
          "remaining" -> node.remaining.asJson(Nibble.nibbleSeqEncoder),
          "dataDigest" -> node.dataDigest.asJson,
          "cachedDigest" -> node.cachedDigest.asJson
        )
      }

    implicit val leafNodeDecoder: Decoder[Leaf] =
      Decoder.instance { hCursor =>
        for {
          remaining <- hCursor.downField("remaining").as[Seq[Nibble]](Nibble.nibbleSeqDecoder)
          dataDigest <- hCursor.downField("dataDigest").as[Hash]
          cachedDigest <- hCursor.downField("cachedDigest").as[Option[Hash]]
        } yield new Leaf(CompactNibblePath.fromNibbleSeq(remaining), dataDigest, cachedDigest)
      }
  }

  object Branch {

    /** Create dirty branch (no hash computed yet) */
    def apply[F[_]: Sync](paths: Map[Nibble, MerklePatriciaNode]): F[Branch] = {
      val byteKeyedPaths: Map[Byte, MerklePatriciaNode] = paths.map { case (k, v) => k.value -> v }
      new Branch(byteKeyedPaths, None).pure[F]
    }

    def fromByteKeys[F[_]: Sync](paths: Map[Byte, MerklePatriciaNode]): F[Branch] =
      new Branch(paths, None).pure[F]

    /** Create empty branch */
    def empty[F[_]: Sync]: F[Branch] =
      new Branch(Map.empty, None).pure[F]

    implicit val encodeBranchNode: Encoder[Branch] =
      Encoder.instance { node =>
        Json.obj(
          "paths" -> node.paths.toSeq.sortBy(_._1.value).toMap.asJson,
          "cachedDigest" -> node.cachedDigest.asJson
        )
      }

    implicit val decodeBranchNode: Decoder[Branch] =
      Decoder.instance { hCursor =>
        for {
          children <- hCursor.downField("paths").as[Map[Nibble, MerklePatriciaNode]]
          cachedDigest <- hCursor.downField("cachedDigest").as[Option[Hash]]
        } yield new Branch(children.map { case (k, v) => k.value -> v }, cachedDigest)
      }
  }

  object Extension {

    /** Create dirty extension (no hash computed yet) */
    def apply[F[_]: Sync](shared: Seq[Nibble], child: Branch): F[Extension] =
      new Extension(CompactNibblePath.fromNibbleSeq(shared), child, None).pure[F]

    def fromCompact[F[_]: Sync](shared: CompactNibblePath, child: Branch): F[Extension] =
      new Extension(shared, child, None).pure[F]

    implicit val encodeExtensionNode: Encoder[Extension] =
      Encoder.instance { node =>
        Json.obj(
          "shared" -> node.shared.asJson(Nibble.nibbleSeqEncoder),
          "child" -> (node.child: MerklePatriciaNode).asJson,
          "cachedDigest" -> node.cachedDigest.asJson
        )
      }

    implicit val decodeExtensionNode: Decoder[Extension] =
      Decoder.instance { hCursor =>
        for {
          shared <- hCursor.downField("shared").as[Seq[Nibble]](Nibble.nibbleSeqDecoder)
          child <- hCursor.downField("child").downField("contents").as[Branch]
          cachedDigest <- hCursor.downField("cachedDigest").as[Option[Hash]]
        } yield new Extension(CompactNibblePath.fromNibbleSeq(shared), child, cachedDigest)
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
