package io.constellationnetwork.security.mpt

import cats.effect.Sync

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

  def make[F[_]: Hasher: Sync, A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    MerklePatriciaProducer
      .stateless[F]
      .create(data)

  def collectLeafNodes(trie: MerklePatriciaTrie): List[MerklePatriciaNode.Leaf] = {

    @tailrec
    def traverse(nodes: List[MerklePatriciaNode], acc: List[MerklePatriciaNode.Leaf]): List[MerklePatriciaNode.Leaf] =
      nodes match {
        case Nil                                               => acc
        case (head: MerklePatriciaNode.Leaf) :: tail           => traverse(tail, head :: acc)
        case MerklePatriciaNode.Branch(paths, _) :: tail       => traverse(paths.values.toList ++ tail, acc)
        case MerklePatriciaNode.Extension(_, child, _) :: tail => traverse(child :: tail, acc)
      }

    traverse(List(trie.rootNode), List()).reverse
  }

  def collectLeafNodesWithPaths(trie: MerklePatriciaTrie): List[(Hex, MerklePatriciaNode.Leaf)] = {
    case class NodeWithPath(node: MerklePatriciaNode, pathSoFar: Seq[Nibble])

    @tailrec
    def traverse(nodes: List[NodeWithPath], acc: List[(Hex, MerklePatriciaNode.Leaf)]): List[(Hex, MerklePatriciaNode.Leaf)] =
      nodes match {
        case Nil => acc
        case NodeWithPath(leaf: MerklePatriciaNode.Leaf, pathSoFar) :: tail =>
          val fullPath = pathSoFar ++ leaf.remaining
          traverse(tail, (Nibble.toHex(fullPath), leaf) :: acc)
        case NodeWithPath(MerklePatriciaNode.Branch(paths, _), pathSoFar) :: tail =>
          val childNodes = paths.toList.map {
            case (nibble, child) =>
              NodeWithPath(child, pathSoFar :+ nibble)
          }
          traverse(childNodes ++ tail, acc)
        case NodeWithPath(MerklePatriciaNode.Extension(shared, child, _), pathSoFar) :: tail =>
          traverse(NodeWithPath(child, pathSoFar ++ shared) :: tail, acc)
      }

    traverse(List(NodeWithPath(trie.rootNode, Seq.empty)), List()).reverse
  }
}
