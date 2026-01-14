package io.constellationnetwork.security.mpt.producer

import cats.data.NonEmptyList
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.ArraySeq

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver
import io.constellationnetwork.security.mpt.verifier._

import io.circe.syntax._
import io.circe.{Encoder, Json}

class StatelessMerklePatriciaProducer[F[_]: Hasher: Async] extends MerklePatriciaProducer[F] {

  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]] =
    MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]

  private val yieldEveryN = 50

  def create[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    NonEmptyList.fromList(data.toList) match {
      case Some(nel) =>
        val (hPath, hData) = nel.head

        for {
          initialNode <- MerklePatriciaNode.Leaf[F](Nibble(hPath), hData.asJson)
          sortedTail = nel.tail.sortBy(_._1.value.length)

          resultNode <- sortedTail.zipWithIndex
            .foldM[F, MerklePatriciaNode](initialNode) {
              case (acc, ((path, value), idx)) =>
                val work = insertEncoded(acc, Nibble(path), value.asJson).flatMap {
                  case Left(err)    => err.raiseError[F, MerklePatriciaNode]
                  case Right(value) => value.pure[F]
                }

                if (idx % yieldEveryN == 0) Async[F].cede *> work <* Async[F].cede
                else work
            }
        } yield MerklePatriciaTrie(resultNode)

      case None => new RuntimeException("Empty data provided").raiseError
    }

  def insert[A: Encoder](
    current: MerklePatriciaTrie,
    data: Map[Hex, A]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    if (data.isEmpty) {
      current.asRight[MerklePatriciaError].pure[F]
    } else {
      insertMultiple(current.rootNode, data.toList)
        .map(_.map(MerklePatriciaTrie(_)))
        .handleError(e => OperationError(e.getMessage).asLeft[MerklePatriciaTrie])
    }

  def remove(current: MerklePatriciaTrie, data: List[Hex]): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    if (data.isEmpty) {
      current.asRight[MerklePatriciaError].pure[F]
    } else {
      removeMultiple(current.rootNode, data)
        .map(_.map(MerklePatriciaTrie(_)))
        .handleError(e => OperationError(e.getMessage).asLeft[MerklePatriciaTrie])
    }

  private def insertMultiple[A: Encoder](
    initialNode: MerklePatriciaNode,
    entries: List[(Hex, A)]
  ): F[Either[MerklePatriciaError, MerklePatriciaNode]] =
    entries.zipWithIndex.foldM(initialNode.asRight[MerklePatriciaError]) {
      case (Right(acc), ((path, value), idx)) =>
        val work = insertEncoded(acc, Nibble(path), value.asJson)
        if (idx % yieldEveryN == 0) Async[F].cede *> work <* Async[F].cede
        else work
      case (Left(err), _) =>
        err.asLeft[MerklePatriciaNode].pure[F]
    }

  private def removeMultiple(
    initialNode: MerklePatriciaNode,
    paths: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaNode]] =
    paths.zipWithIndex.foldM(initialNode.asRight[MerklePatriciaError]) {
      case (Right(acc), (path, idx)) =>
        val work = removeEncoded(acc, Nibble(path))
        if (idx % yieldEveryN == 0) Async[F].cede *> work <* Async[F].cede
        else work
      case (Left(err), _) =>
        err.asLeft[MerklePatriciaNode].pure[F]
    }

  sealed private trait InsertState

  private case class InsertContinue(
    currentNode: MerklePatriciaNode,
    key: Seq[Nibble],
    updateParent: MerklePatriciaNode => F[Either[MerklePatriciaError, MerklePatriciaNode]]
  ) extends InsertState
  private case class InsertDone(node: Either[MerklePatriciaError, MerklePatriciaNode]) extends InsertState

  private def insertEncoded(
    currentNode: MerklePatriciaNode,
    path: Seq[Nibble],
    data: Json
  ): F[Either[MerklePatriciaError, MerklePatriciaNode]] = {
    def insertForLeafNode(
      leafNode: MerklePatriciaNode.Leaf,
      _key: Seq[Nibble],
      updateParent: MerklePatriciaNode => F[Either[MerklePatriciaError, MerklePatriciaNode]]
    ): F[Either[InsertState, Either[MerklePatriciaError, MerklePatriciaNode]]] =
      if (leafNode.remaining == _key) {
        for {
          newLeaf <- MerklePatriciaNode.Leaf[F](_key, data)
          result <- updateParent(newLeaf)
        } yield result.asRight[InsertState]
      } else {
        val commonPrefix = Nibble.commonPrefix(leafNode.remaining, _key)
        val leafRemaining = leafNode.remaining.drop(commonPrefix.length)
        val keyRemaining = _key.drop(commonPrefix.length)

        (for {
          existingLeaf <- MerklePatriciaNode.Leaf[F](leafRemaining.tail, leafNode.data)
          newLeaf <- MerklePatriciaNode.Leaf[F](keyRemaining.tail, data)
          branchNode <- MerklePatriciaNode.Branch[F](
            Map[Nibble, MerklePatriciaNode](
              leafRemaining.head -> existingLeaf,
              keyRemaining.head -> newLeaf
            )
          )
          resultNode <-
            if (commonPrefix.nonEmpty) MerklePatriciaNode.Extension[F](commonPrefix, branchNode)
            else branchNode.pure[F]
          updatedNode <- updateParent(resultNode)
        } yield InsertDone(updatedNode).asLeft[Either[MerklePatriciaError, MerklePatriciaNode]]).handleError { e =>
          InsertDone(OperationError(e.getMessage).asLeft[MerklePatriciaNode]).asLeft
        }.widen
      }

    def insertForExtensionNode(
      extensionNode: MerklePatriciaNode.Extension,
      _key: Seq[Nibble],
      updateParent: MerklePatriciaNode => F[Either[MerklePatriciaError, MerklePatriciaNode]]
    ): F[Either[InsertState, Either[MerklePatriciaError, MerklePatriciaNode]]] = {
      val commonPrefix = Nibble.commonPrefix(extensionNode.shared, _key)
      val sharedRemaining = extensionNode.shared.drop(commonPrefix.length)
      val keyRemaining = _key.drop(commonPrefix.length)

      if (_key.isEmpty) {
        InsertDone(OperationError("Key exhausted at extension node").asLeft)
          .asLeft[Either[MerklePatriciaError, MerklePatriciaNode]]
          .pure[F]
          .widen
      } else if (sharedRemaining.isEmpty) {
        (InsertContinue(
          extensionNode.child,
          keyRemaining,
          {
            case branch: MerklePatriciaNode.Branch =>
              MerklePatriciaNode
                .Extension[F](extensionNode.shared, branch)
                .flatMap(ext => updateParent(ext))
                .handleError(e => OperationError(e.getMessage).asLeft)
            case _ =>
              OperationError("Unexpected node type while creating extension node")
                .asLeft[MerklePatriciaNode]
                .pure[F]
                .widen
          }
        ): InsertState).asLeft[Either[MerklePatriciaError, MerklePatriciaNode]].pure[F]
      } else {
        (for {
          // If sharedRemaining.tail is empty, use child directly instead of wrapping in Extension([])
          existingSubtree <-
            if (sharedRemaining.tail.isEmpty)
              extensionNode.child.pure[F].widen[MerklePatriciaNode]
            else
              MerklePatriciaNode.Extension[F](sharedRemaining.tail, extensionNode.child).widen[MerklePatriciaNode]
          newLeaf <- MerklePatriciaNode.Leaf[F](keyRemaining.tail, data)
          branchNode <- MerklePatriciaNode.Branch[F](
            Map(
              sharedRemaining.head -> existingSubtree,
              keyRemaining.head -> newLeaf
            )
          )
          resultNode <-
            if (commonPrefix.nonEmpty) MerklePatriciaNode.Extension[F](commonPrefix, branchNode)
            else branchNode.pure[F]
          updatedNode <- updateParent(resultNode)
        } yield InsertDone(updatedNode).asLeft[Either[MerklePatriciaError, MerklePatriciaNode]]).handleError { e =>
          InsertDone(OperationError(e.getMessage).asLeft[MerklePatriciaNode]).asLeft
        }.widen
      }
    }

    def insertForBranchNode(
      branchNode: MerklePatriciaNode.Branch,
      _key: Seq[Nibble],
      updateParent: MerklePatriciaNode => F[Either[MerklePatriciaError, MerklePatriciaNode]]
    ): F[Either[InsertState, Either[MerklePatriciaError, MerklePatriciaNode]]] =
      if (_key.isEmpty) {
        InsertDone(OperationError("Key exhausted at branch node").asLeft)
          .asLeft[Either[MerklePatriciaError, MerklePatriciaNode]]
          .pure[F]
          .widen
      } else {
        val nibble = _key.head
        val keyRemaining = _key.tail

        branchNode.paths.get(nibble) match {
          case Some(childNode) =>
            (InsertContinue(
              childNode,
              keyRemaining,
              (updatedChild: MerklePatriciaNode) =>
                MerklePatriciaNode
                  .Branch[F](branchNode.paths + (nibble -> updatedChild))
                  .flatMap(updateParent)
                  .handleError(e => OperationError(e.getMessage).asLeft)
            ): InsertState).asLeft[Either[MerklePatriciaError, MerklePatriciaNode]].pure[F]

          case None =>
            (for {
              newLeaf <- MerklePatriciaNode.Leaf[F](keyRemaining, data)
              updatedBranch <- MerklePatriciaNode.Branch[F](branchNode.paths + (nibble -> newLeaf))
              result <- updateParent(updatedBranch)
            } yield result.asRight[InsertState]).handleError { e =>
              InsertDone(OperationError(e.getMessage).asLeft[MerklePatriciaNode]).asLeft
            }
        }
      }

    def step(state: InsertState): F[Either[InsertState, Either[MerklePatriciaError, MerklePatriciaNode]]] =
      state match {
        case InsertContinue(currentNode, key, updateParent) =>
          currentNode match {
            case node: MerklePatriciaNode.Leaf      => insertForLeafNode(node, key, updateParent)
            case node: MerklePatriciaNode.Extension => insertForExtensionNode(node, key, updateParent)
            case node: MerklePatriciaNode.Branch    => insertForBranchNode(node, key, updateParent)
          }
        case InsertDone(node) => node.asRight[InsertState].pure[F]
      }

    val initialState: InsertState = InsertContinue(
      currentNode,
      path,
      node => node.asRight[MerklePatriciaError].pure[F]
    )

    initialState.tailRecM[F, Either[MerklePatriciaError, MerklePatriciaNode]](step)
  }

  sealed private trait RemoveState

  private case class RemoveContinue(
    currentNode: MerklePatriciaNode,
    key: Seq[Nibble],
    updateParent: Option[MerklePatriciaNode] => F[Either[MerklePatriciaError, Option[MerklePatriciaNode]]]
  ) extends RemoveState
  private case class RemoveDone(nodeOpt: Either[MerklePatriciaError, Option[MerklePatriciaNode]]) extends RemoveState

  private def removeEncoded(
    currentNode: MerklePatriciaNode,
    path: Seq[Nibble]
  ): F[Either[MerklePatriciaError, MerklePatriciaNode]] = {
    def removeForLeafNode(
      leafNode: MerklePatriciaNode.Leaf,
      _key: Seq[Nibble],
      updateParent: Option[MerklePatriciaNode] => F[Either[MerklePatriciaError, Option[MerklePatriciaNode]]]
    ): F[Either[RemoveState, Either[MerklePatriciaError, Option[MerklePatriciaNode]]]] =
      if (leafNode.remaining == _key) {
        updateParent(None).map(_.asRight)
      } else {
        leafNode.some.asRight[MerklePatriciaError].asRight[RemoveState].pure[F].widen
      }

    def removeForExtensionNode(
      extensionNode: MerklePatriciaNode.Extension,
      _key: Seq[Nibble],
      updateParent: Option[MerklePatriciaNode] => F[Either[MerklePatriciaError, Option[MerklePatriciaNode]]]
    ): F[Either[RemoveState, Either[MerklePatriciaError, Option[MerklePatriciaNode]]]] = {
      val commonPrefix = Nibble.commonPrefix(extensionNode.shared, _key)

      if (commonPrefix.length == extensionNode.shared.length) {
        (RemoveContinue(
          extensionNode.child,
          _key.drop(commonPrefix.length),
          {
            case Some(updatedChild) =>
              updatedChild match {
                case childBranch: MerklePatriciaNode.Branch =>
                  MerklePatriciaNode
                    .Extension[F](extensionNode.shared, childBranch)
                    .flatMap(node => updateParent(Some(node)))
                    .handleError(e => OperationError(e.getMessage).asLeft)

                case childLeaf: MerklePatriciaNode.Leaf =>
                  MerklePatriciaNode
                    .Leaf[F](extensionNode.shared ++ childLeaf.remaining, childLeaf.data)
                    .flatMap(node => updateParent(Some(node)))
                    .handleError(e => OperationError(e.getMessage).asLeft)

                case childExtension: MerklePatriciaNode.Extension =>
                  MerklePatriciaNode
                    .Extension[F](extensionNode.shared ++ childExtension.shared, childExtension.child)
                    .flatMap(node => updateParent(Some(node)))
                    .handleError(e => OperationError(e.getMessage).asLeft)
              }

            case None => updateParent(None)
          }
        ): RemoveState).asLeft[Either[MerklePatriciaError, Option[MerklePatriciaNode]]].pure[F]
      } else {
        extensionNode.some.asRight[MerklePatriciaError].asRight[RemoveState].pure[F].widen
      }
    }

    def removeForBranchNode(
      branchNode: MerklePatriciaNode.Branch,
      _key: Seq[Nibble],
      updateParent: Option[MerklePatriciaNode] => F[Either[MerklePatriciaError, Option[MerklePatriciaNode]]]
    ): F[Either[RemoveState, Either[MerklePatriciaError, Option[MerklePatriciaNode]]]] =
      if (_key.nonEmpty) {
        val nibble = _key.head
        val keyRemaining = _key.tail

        branchNode.paths.get(nibble) match {
          case Some(childNode) =>
            RemoveContinue(
              childNode,
              keyRemaining,
              {
                case Some(updatedChild) =>
                  MerklePatriciaNode
                    .Branch[F](branchNode.paths + (nibble -> updatedChild))
                    .flatMap(node => updateParent(Some(node)))
                    .handleError(e => OperationError(e.getMessage).asLeft)
                case None =>
                  val updatedPaths = branchNode.paths - nibble

                  updatedPaths.size match {
                    case 0 =>
                      updateParent(None)

                    case 1 =>
                      val (remainingNibble, onlyChild) = updatedPaths.head
                      onlyChild match {
                        case leafNode: MerklePatriciaNode.Leaf =>
                          MerklePatriciaNode
                            .Leaf[F](ArraySeq(remainingNibble) ++ leafNode.remaining, leafNode.data)
                            .flatMap(node => updateParent(Some(node)))
                            .handleError(e => OperationError(e.getMessage).asLeft)

                        case extensionNode: MerklePatriciaNode.Extension =>
                          MerklePatriciaNode
                            .Extension[F](ArraySeq(remainingNibble) ++ extensionNode.shared, extensionNode.child)
                            .flatMap(node => updateParent(Some(node)))
                            .handleError(e => OperationError(e.getMessage).asLeft)

                        case branchNode: MerklePatriciaNode.Branch =>
                          MerklePatriciaNode
                            .Extension[F](ArraySeq(remainingNibble), branchNode)
                            .flatMap(node => updateParent(Some(node)))
                            .handleError(e => OperationError(e.getMessage).asLeft)
                      }

                    case _ =>
                      MerklePatriciaNode
                        .Branch[F](updatedPaths)
                        .flatMap(node => updateParent(Some(node)))
                        .handleError(e => OperationError(e.getMessage).asLeft)
                  }
              }
            ).asLeft[Either[MerklePatriciaError, Option[MerklePatriciaNode]]].pure[F].widen

          case None => branchNode.some.asRight[MerklePatriciaError].asRight[RemoveState].pure[F].widen
        }
      } else branchNode.some.asRight[MerklePatriciaError].asRight[RemoveState].pure[F].widen

    def step(state: RemoveState): F[Either[RemoveState, Either[MerklePatriciaError, Option[MerklePatriciaNode]]]] =
      state match {
        case RemoveContinue(currentNode, key, updateParent) =>
          currentNode match {
            case node: MerklePatriciaNode.Leaf      => removeForLeafNode(node, key, updateParent)
            case node: MerklePatriciaNode.Extension => removeForExtensionNode(node, key, updateParent)
            case node: MerklePatriciaNode.Branch    => removeForBranchNode(node, key, updateParent)
          }
        case RemoveDone(nodeOpt) => nodeOpt.asRight[RemoveState].pure[F]
      }

    val initialState: RemoveState = RemoveContinue(
      currentNode,
      path,
      nodeOpt => nodeOpt.asRight[MerklePatriciaError].pure[F]
    )

    initialState.tailRecM[F, Either[MerklePatriciaError, Option[MerklePatriciaNode]]](step).flatMap {
      case Right(Some(newRootNode)) => newRootNode.asRight[MerklePatriciaError].pure[F]
      case Right(None)              => MerklePatriciaNode.Branch[F](Map.empty).map(_.asRight[MerklePatriciaError])
      case Left(err)                => err.asLeft[MerklePatriciaNode].pure[F]
    }
  }
}

object StatelessMerklePatriciaProducer {

  def apply[F[_]: Hasher: Async]: StatelessMerklePatriciaProducer[F] =
    new StatelessMerklePatriciaProducer[F]
}
