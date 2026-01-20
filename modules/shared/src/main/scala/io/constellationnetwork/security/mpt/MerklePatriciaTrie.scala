package io.constellationnetwork.security.mpt

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.annotation.tailrec

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.producer.MerklePatriciaProducer

import io.circe._
import io.circe.syntax._

final case class MerklePatriciaTrie(rootNode: MerklePatriciaNode)

object MerklePatriciaTrie {

  private val CedeThreshold = 500

  /** Compute root hash incrementally - only rehashes dirty nodes. Returns updated trie with all hashes computed and cached.
    */
  def rootHash[F[_]: Async: Hasher: Parallel](trie: MerklePatriciaTrie): F[(MptRoot, MerklePatriciaTrie)] =
    for {
      counter <- Async[F].ref(0)
      updatedRoot <- computeAndCacheHash[F](trie.rootNode, counter)
    } yield (MptRoot(updatedRoot.cachedDigest.get), MerklePatriciaTrie(updatedRoot))

  /** Compute root hash without returning updated trie (for compatibility) */
  def getRootHash[F[_]: Async: Hasher: Parallel](trie: MerklePatriciaTrie): F[MptRoot] =
    rootHash[F](trie).map(_._1)

  private def maybeYield[F[_]: Async](counter: cats.effect.Ref[F, Int]): F[Unit] =
    counter.updateAndGet(_ + 1).flatMap { count =>
      if (count % CedeThreshold == 0) Async[F].cede
      else Async[F].unit
    }

  /** Compute hash for a node, using cached value if available. Returns the node with its hash cached.
    */
  private def computeAndCacheHash[F[_]: Async: Hasher: Parallel](
    node: MerklePatriciaNode,
    counter: cats.effect.Ref[F, Int]
  ): F[MerklePatriciaNode] =
    node.cachedDigest match {
      case Some(_) =>
        // Already computed, return as-is
        node.pure[F]
      case None =>
        // Need to compute - first recurse to children, then hash this node
        maybeYield(counter) >> (node match {
          case leaf: MerklePatriciaNode.Leaf =>
            computeLeafHash[F](leaf)

          case branch: MerklePatriciaNode.Branch =>
            computeBranchHash[F](branch, counter)

          case ext: MerklePatriciaNode.Extension =>
            computeExtensionHash[F](ext, counter)
        })
    }

  private def computeLeafHash[F[_]: Async: Hasher](leaf: MerklePatriciaNode.Leaf): F[MerklePatriciaNode] = {
    val commitment = MerklePatriciaCommitment.Leaf(leaf.remaining, leaf.dataDigest)
    Hasher[F].prefixedHash(commitment.asJson, MerklePatriciaNode.LeafPrefix).map { hash =>
      leaf.withDigest(hash)
    }
  }

  private def computeBranchHash[F[_]: Async: Hasher: Parallel](
    branch: MerklePatriciaNode.Branch,
    counter: cats.effect.Ref[F, Int]
  ): F[MerklePatriciaNode] =
    for {
      // First, recursively compute hashes for all dirty children
      updatedChildren <- branch.internalPaths.toList.parTraverse {
        case (nibbleByte, child) =>
          computeAndCacheHash[F](child, counter).map(nibbleByte -> _)
      }

      // Build the updated paths map
      updatedPaths = updatedChildren.toMap

      // Compute this branch's hash from children's hashes
      pathDigests = updatedPaths.map {
        case (k, v) => Nibble.unsafe(k) -> v.cachedDigest.get
      }
      commitment = MerklePatriciaCommitment.Branch(pathDigests)
      hash <- Hasher[F].prefixedHash(commitment.asJson, MerklePatriciaNode.BranchPrefix)

    } yield new MerklePatriciaNode.Branch(updatedPaths, Some(hash))

  private def computeExtensionHash[F[_]: Async: Hasher: Parallel](
    ext: MerklePatriciaNode.Extension,
    counter: cats.effect.Ref[F, Int]
  ): F[MerklePatriciaNode] =
    for {
      // First compute child's hash
      updatedChild <- computeAndCacheHash[F](ext.child, counter)
      updatedBranch = updatedChild.asInstanceOf[MerklePatriciaNode.Branch]

      // Then compute this extension's hash
      commitment = MerklePatriciaCommitment.Extension(ext.shared, updatedBranch.cachedDigest.get)
      hash <- Hasher[F].prefixedHash(commitment.asJson, MerklePatriciaNode.ExtensionPrefix)

    } yield new MerklePatriciaNode.Extension(ext.sharedPath, updatedBranch, Some(hash))

  implicit val merklePatriciaTrieEncoder: Encoder[MerklePatriciaTrie] =
    (tree: MerklePatriciaTrie) => Json.obj("rootNode" -> tree.rootNode.asJson)

  implicit val merklePatriciaTrieDecoder: Decoder[MerklePatriciaTrie] = (c: HCursor) =>
    c.downField("rootNode").as[MerklePatriciaNode].map(MerklePatriciaTrie(_))

  def make[F[_]: Hasher: Async, A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    MerklePatriciaProducer
      .stateless[F]
      .create(data)

  def makeParallel[F[_]: Hasher: Async: Parallel, A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    MerklePatriciaProducer
      .parallel[F]
      .create(data)

  def collectLeafNodes(trie: MerklePatriciaTrie): List[MerklePatriciaNode.Leaf] = {
    @tailrec
    def traverse(nodes: List[MerklePatriciaNode], acc: List[MerklePatriciaNode.Leaf]): List[MerklePatriciaNode.Leaf] =
      nodes match {
        case Nil => acc
        case (head: MerklePatriciaNode.Leaf) :: tail =>
          traverse(tail, head :: acc)
        case (branch: MerklePatriciaNode.Branch) :: tail =>
          val children = scala.collection.mutable.ListBuffer[MerklePatriciaNode]()
          branch.foreachChild((_, child) => children += child)
          traverse(children.toList ++ tail, acc)
        case (ext: MerklePatriciaNode.Extension) :: tail =>
          traverse(ext.child :: tail, acc)
      }

    traverse(List(trie.rootNode), List()).reverse
  }

  def collectLeafNodesWithPaths(trie: MerklePatriciaTrie): List[(Hex, MerklePatriciaNode.Leaf)] = {
    case class NodeWithPath(node: MerklePatriciaNode, pathSoFar: CompactNibblePath)

    @tailrec
    def traverse(nodes: List[NodeWithPath], acc: List[(Hex, MerklePatriciaNode.Leaf)]): List[(Hex, MerklePatriciaNode.Leaf)] =
      nodes match {
        case Nil => acc
        case NodeWithPath(leaf: MerklePatriciaNode.Leaf, pathSoFar) :: tail =>
          val fullPath = pathSoFar ++ leaf.remainingPath
          val hex = Hex(fullPath.toHexString)
          traverse(tail, (hex, leaf) :: acc)
        case NodeWithPath(branch: MerklePatriciaNode.Branch, pathSoFar) :: tail =>
          val childNodes = scala.collection.mutable.ListBuffer[NodeWithPath]()
          branch.foreachChild { (nibbleValue, child) =>
            childNodes += NodeWithPath(child, pathSoFar ++ CompactNibblePath.single(nibbleValue))
          }
          traverse(childNodes.toList ++ tail, acc)
        case NodeWithPath(ext: MerklePatriciaNode.Extension, pathSoFar) :: tail =>
          traverse(NodeWithPath(ext.child, pathSoFar ++ ext.sharedPath) :: tail, acc)
      }

    traverse(List(NodeWithPath(trie.rootNode, CompactNibblePath.empty)), List()).reverse
  }

  def collectLeafNodesWithPathsCompat(trie: MerklePatriciaTrie): List[(Hex, MerklePatriciaNode.Leaf)] = {
    case class NodeWithPath(node: MerklePatriciaNode, pathSoFar: Seq[Nibble])

    @tailrec
    def traverse(nodes: List[NodeWithPath], acc: List[(Hex, MerklePatriciaNode.Leaf)]): List[(Hex, MerklePatriciaNode.Leaf)] =
      nodes match {
        case Nil => acc
        case NodeWithPath(leaf: MerklePatriciaNode.Leaf, pathSoFar) :: tail =>
          val fullPath = pathSoFar ++ leaf.remaining
          traverse(tail, (Nibble.toHex(fullPath), leaf) :: acc)
        case NodeWithPath(branch: MerklePatriciaNode.Branch, pathSoFar) :: tail =>
          val childNodes = branch.paths.toList.map {
            case (nibble, child) =>
              NodeWithPath(child, pathSoFar :+ nibble)
          }
          traverse(childNodes ++ tail, acc)
        case NodeWithPath(ext: MerklePatriciaNode.Extension, pathSoFar) :: tail =>
          traverse(NodeWithPath(ext.child, pathSoFar ++ ext.shared) :: tail, acc)
      }

    traverse(List(NodeWithPath(trie.rootNode, Seq.empty)), List()).reverse
  }
}
