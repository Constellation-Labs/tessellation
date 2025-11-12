package io.constellationnetwork.merkletree

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.kernel.Eq
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import io.constellationnetwork.schema.{nonNegIntDecoder, nonNegIntEncoder}
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegInt
import io.circe.disjunctionCodecs._

@derive(eqv, show, encoder, decoder)
case class ProofEntry(target: Hash, sibling: Either[Hash, Hash])

@derive(eqv, show, encoder, decoder)
case class Proof(entries: NonEmptyList[ProofEntry]) {
  def verify[F[_]: Sync](candidate: Hash): F[Boolean] =
    entries.toList match {
      case x :: Nil =>
        MerkleTree.hashLeaf(candidate).map { leaf =>
          x.target === leaf && x.sibling === Right(leaf)
        }
      case _ =>
        candidate.some.traverse(MerkleTree.hashLeaf[F]).flatMap { candidateLeaf =>
          entries
            .foldLeftM(candidateLeaf) {
              case (Some(curr), pe) =>
                val (lsib, rsib) = pe.sibling match {
                  case Left(sib)  => (sib, curr)
                  case Right(sib) => (curr, sib)
                }
                MerkleTree.hashIntermediate(lsib, rsib).map { hash =>
                  Option.when(hash == pe.target)(hash)
                }
              case (None, _) => none[Hash].pure[F]
            }
            .map(_.isDefined)
        }
    }
}

@derive(show, encoder, decoder, eqv)
case class MerkleRoot(leafCount: NonNegInt, hash: Hash)

@derive(show, encoder, decoder)
case class MerkleTree(leafCount: NonNegInt, nodes: NonEmptyList[Hash]) {
  def getRoot: MerkleRoot =
    MerkleRoot(leafCount, nodes.last)

  def findPath[F[_]: Sync](leaf: Hash): F[Option[Proof]] =
    MerkleTree.hashLeaf(leaf).flatMap(hash => findPath[F](nodes.toList.indexOf(hash)))

  def findPath[F[_]: Sync](index: Int): F[Option[Proof]] = Sync[F].delay(
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

      nodes.toList match {
        case x :: Nil => Some(Proof(NonEmptyList.one(ProofEntry(x, Right(x)))))
        case _        => NonEmptyList.fromList(go()).map(Proof(_))
      }
    }
  )
}

object MerkleTree {

  implicit val eq: Eq[MerkleTree] = (x, y) => x.leafCount === y.leafCount && x.getRoot === y.getRoot

  private val leafPrefix: Byte = 0x00
  private val intermediatePrefix: Byte = 0x01

  private[merkletree] def hashLeaf[F[_]: Sync](node: Hash): F[Hash] =
    Hash.fromBytesForSync(node.value.getBytes.prepended(leafPrefix))

  private[merkletree] def hashIntermediate[F[_]: Sync](left: Hash, right: Hash): F[Hash] =
    Hash.fromBytesForSync((left.value + right.value).getBytes.prepended(intermediatePrefix))

  private def nextLevelLen(levelLen: Int): Int =
    if (levelLen == 1) 0 else (levelLen + 1) / 2

  def from[F[_]: Sync](items: NonEmptyList[Hash]): F[MerkleTree] = {
    val leafCount = NonNegInt.unsafeFrom(items.size) // Note: size cannot be less than 0
    items.traverse(hashLeaf[F]).flatMap { nodes =>
      case class State(
        mt: MerkleTree,
        levelLen: Int,
        levelStart: Int,
        prevLevelLen: Int,
        prevLevelStart: Int
      )

      def go(state: State): F[State] =
        if (state.levelLen <= 0) {
          state.pure[F]
        } else {
          val newMt = (0 to state.levelLen - 1).toList.foldLeftM(state.mt) {
            case (prevMt, i) =>
              val prevLevelIdx = 2 * i;
              val pointer = state.prevLevelStart + prevLevelIdx;
              val leftSibling = prevMt.nodes.toList(pointer)
              val rightSibling =
                if (prevLevelIdx + 1 < state.prevLevelLen)
                  prevMt.nodes.toList(pointer + 1)
                else
                  prevMt.nodes.toList(pointer)

              hashIntermediate[F](leftSibling, rightSibling).map(hash => MerkleTree(prevMt.leafCount, prevMt.nodes.append(hash)))
          }

          newMt.map { updatedMt =>
            State(
              mt = updatedMt,
              levelLen = nextLevelLen(state.levelLen),
              levelStart = state.levelStart + state.levelLen,
              prevLevelLen = state.levelLen,
              prevLevelStart = state.levelStart
            )
          }
        }

      // Use tailRecM for stack-safe recursion
      Sync[F].tailRecM(
        State(
          mt = MerkleTree(leafCount, nodes),
          levelLen = nextLevelLen(leafCount.value),
          levelStart = leafCount.value,
          prevLevelLen = leafCount.value,
          prevLevelStart = 0
        )
      ) { state =>
        if (state.levelLen <= 0) {
          state.mt.asRight[State].pure[F]
        } else {
          go(state).map(_.asLeft[MerkleTree])
        }
      }
    }
  }
}
