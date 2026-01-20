package io.constellationnetwork.security.mpt

import cats.Parallel
import cats.effect.Async

import scala.annotation.tailrec

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.producer.MerklePatriciaProducer

import io.circe._
import io.circe.syntax._

final case class MerklePatriciaTrie(rootNode: MerklePatriciaNode) {
  def rootHash: MptRoot = MptRoot(rootNode.digest)
}

object MerklePatriciaTrie {

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

  /** Collect leaf nodes with paths using Seq[Nibble] for compatibility. Use collectLeafNodesWithPaths for better performance.
    */
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
