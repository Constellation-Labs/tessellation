package io.constellationnetwork.security.mpt

import cats.effect.Sync

import scala.annotation.tailrec

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.api.MerklePatriciaProducer

import io.circe._
import io.circe.syntax._

final case class MerklePatriciaTrie(rootNode: MerklePatriciaNode)

object MerklePatriciaTrie {

  implicit val merkleTreeEncoder: Encoder[MerklePatriciaTrie] =
    (tree: MerklePatriciaTrie) => Json.obj("rootNode" -> tree.rootNode.asJson)

  implicit val merkleTreeDecoder: Decoder[MerklePatriciaTrie] = (c: HCursor) =>
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
}