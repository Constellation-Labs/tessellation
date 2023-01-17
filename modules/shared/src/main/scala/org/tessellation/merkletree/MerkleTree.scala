package org.tessellation.merkletree

import cats.data.NonEmptyList
import cats.kernel.Eq
import cats.syntax.eq._
import cats.syntax.option._

import org.tessellation.security.hash.Hash

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegInt

case class ProofEntry(target: Hash, sibling: Either[Hash, Hash])

case class Proof(entries: NonEmptyList[ProofEntry]) {
  def verify(candidate: Hash): Boolean =
    entries
      .foldLeft(candidate.some.map(MerkleTree.hashLeaf)) {
        case (Some(curr), pe) =>
          val (lsib, rsib) = pe.sibling match {
            case Left(sib)  => (sib, curr)
            case Right(sib) => (curr, sib)
          }
          val hash = MerkleTree.hashIntermediate(lsib, rsib)
          Option.when(hash == pe.target)(hash)
        case (None, _) => None
      }
      .isDefined
}

case class MerkleTree(leafCount: NonNegInt, nodes: NonEmptyList[Hash]) {
  def getRoot: Hash =
    nodes.last

  def findPath(leaf: Hash): Option[Proof] =
    findPath(nodes.toList.indexOf(MerkleTree.hashLeaf(leaf)))

  def findPath(index: Int): Option[Proof] =
    if (index < 0 || index >= leafCount.value)
      None
    else {

      def go(
        levelLen: Int = leafCount.value,
        levelStart: Int = 0,
        path: List[ProofEntry] = List.empty,
        nodeIndex: Int = index,
        sib: Option[Either[Hash, Hash]] = None
      ): List[ProofEntry] =
        if (levelLen <= 0) {
          path
        } else {
          val level = nodes.toList.slice(levelStart, levelStart + levelLen)
          val target = level.toList(nodeIndex)

          val newPath = sib.map { s =>
            path.appended(ProofEntry(target, s))
          }.getOrElse(path)

          val newSib = if (nodeIndex % 2 == 0) {
            if (nodeIndex + 1 < level.size)
              Right(level(nodeIndex + 1))
            else Right(level(nodeIndex))
          } else {
            Left(level(nodeIndex - 1))
          }

          val newNodeIndex = nodeIndex / 2
          val newLevelStart = levelStart + levelLen
          val newLevelLen = MerkleTree.nextLevelLen(levelLen)

          go(newLevelLen, newLevelStart, newPath, newNodeIndex, Some(newSib))

        }

      NonEmptyList.fromList(go()).map(Proof(_))
    }
}

object MerkleTree {

  implicit val eq: Eq[MerkleTree] = (x, y) => x.leafCount === y.leafCount && x.getRoot === y.getRoot

  private val leafPrefix: Byte = 0x00
  private val intermediatePrefix: Byte = 0x01

  private[merkletree] def hashLeaf(node: Hash): Hash =
    Hash.fromBytes(node.value.getBytes.prepended(leafPrefix))

  private[merkletree] def hashIntermediate(left: Hash, right: Hash): Hash =
    Hash.fromBytes((left.value + right.value).getBytes.prepended(intermediatePrefix))

  private def nextLevelLen(levelLen: Int): Int =
    if (levelLen == 1) 0 else (levelLen + 1) / 2

  def from(items: NonEmptyList[Hash]): MerkleTree = {
    val leafCount = NonNegInt.unsafeFrom(items.size) // Note: size cannot be less than 0
    val nodes = items.map(hashLeaf)

    def go(
      mt: MerkleTree = MerkleTree(leafCount, nodes),
      levelLen: Int = nextLevelLen(leafCount.value),
      levelStart: Int = leafCount.value,
      prevLevelLen: Int = leafCount.value,
      prevLevelStart: Int = 0
    ): MerkleTree =
      if (levelLen <= 0)
        mt
      else {
        val newMt = (0 to levelLen - 1).foldLeft(mt) {
          case (prevMt, i) =>
            val prevLevelIdx = 2 * i;
            val pointer = prevLevelStart + prevLevelIdx;
            val leftSibling = prevMt.nodes.toList(pointer)
            val rightSibling =
              if (prevLevelIdx + 1 < prevLevelLen)
                prevMt.nodes.toList(pointer + 1)
              else
                prevMt.nodes.toList(pointer)

            MerkleTree(prevMt.leafCount, prevMt.nodes.append(hashIntermediate(leftSibling, rightSibling)))
        }

        go(newMt, nextLevelLen(levelLen), levelStart + levelLen, levelLen, levelStart)
      }

    go()
  }

}
