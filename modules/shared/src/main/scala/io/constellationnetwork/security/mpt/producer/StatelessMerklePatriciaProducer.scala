package io.constellationnetwork.security.mpt.producer

import cats.data.NonEmptyList
import cats.effect.{Async, Ref, Sync}
import cats.syntax.all._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver
import io.constellationnetwork.security.mpt.verifier._

import io.circe.syntax._
import io.circe.{Encoder, Json}

/** Result of trie operations that tracks both the trie and external data storage. */
case class MerklePatriciaTrieWithData(
  trie: MerklePatriciaTrie,
  dataStore: Map[Hash, Json]
) {

  /** Get data by its digest. */
  def getData(digest: Hash): Option[Json] = dataStore.get(digest)

  /** Get all stored data. */
  def allData: Map[Hash, Json] = dataStore
}

/** Stateless MPT producer with memory-optimized operations.
  *
  * Data is stored externally in a Map[Hash, Json] rather than in leaf nodes. This dramatically reduces memory usage for large tries.
  */
class StatelessMerklePatriciaProducer[F[_]: Hasher: Async] extends MerklePatriciaProducer[F] {

  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]] =
    MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]

  private val yieldEveryN = 50

  /** Create a trie with external data storage. */
  def createWithData[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrieWithData] =
    NonEmptyList.fromList(data.toList) match {
      case Some(nel) =>
        for {
          dataStoreRef <- Ref.of[F, Map[Hash, Json]](Map.empty)

          (hPath, hData) = nel.head
          hDataJson = hData.asJson
          hDataDigest <- Hasher[F].hash(hDataJson)
          _ <- dataStoreRef.update(_ + (hDataDigest -> hDataJson))

          initialNode <- MerklePatriciaNode.Leaf.fromDataDigest[F](
            CompactNibblePath.fromHexString(hPath.value),
            hDataDigest
          )

          sortedTail = nel.tail.sortBy(_._1.value.length)

          resultNode <- sortedTail.zipWithIndex
            .foldM[F, MerklePatriciaNode](initialNode) {
              case (acc, ((path, value), idx)) =>
                val compactPath = CompactNibblePath.fromHexString(path.value)
                val valueJson = value.asJson
                val work = for {
                  dataDigest <- Hasher[F].hash(valueJson)
                  _ <- dataStoreRef.update(_ + (dataDigest -> valueJson))
                  result <- insertWithDigest(acc, compactPath, dataDigest).flatMap {
                    case Left(err)   => err.raiseError[F, MerklePatriciaNode]
                    case Right(node) => node.pure[F]
                  }
                } yield result

                if (idx % yieldEveryN == 0) Async[F].cede *> work <* Async[F].cede
                else work
            }

          finalDataStore <- dataStoreRef.get
        } yield MerklePatriciaTrieWithData(MerklePatriciaTrie(resultNode), finalDataStore)

      case None => new RuntimeException("Empty data provided").raiseError
    }

  /** Original create method - returns trie without data store for backward compatibility. */
  def create[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie] =
    createWithData(data).map(_.trie)

  /** Insert with external data storage tracking. */
  def insertWithData[A: Encoder](
    current: MerklePatriciaTrieWithData,
    data: Map[Hex, A]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrieWithData]] =
    if (data.isEmpty) {
      current.asRight[MerklePatriciaError].pure[F]
    } else {
      (for {
        dataStoreRef <- Ref.of[F, Map[Hash, Json]](current.dataStore)
        result <- insertMultipleWithStore(current.trie.rootNode, data.toList, dataStoreRef)
        finalDataStore <- dataStoreRef.get
      } yield result.map(node => MerklePatriciaTrieWithData(MerklePatriciaTrie(node), finalDataStore)))
        .handleError(e => OperationError(e.getMessage).asLeft)
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

  /** Remove with data store cleanup. */
  def removeWithData(
    current: MerklePatriciaTrieWithData,
    paths: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrieWithData]] =
    if (paths.isEmpty) {
      current.asRight[MerklePatriciaError].pure[F]
    } else {
      // Collect digests to remove, then remove from trie
      val digestsToRemove = paths.flatMap { path =>
        findLeafDigest(current.trie.rootNode, CompactNibblePath.fromHexString(path.value))
      }.toSet

      removeMultiple(current.trie.rootNode, paths)
        .map(_.map { node =>
          val newDataStore = current.dataStore -- digestsToRemove
          MerklePatriciaTrieWithData(MerklePatriciaTrie(node), newDataStore)
        })
        .handleError(e => OperationError(e.getMessage).asLeft)
    }

  private def findLeafDigest(node: MerklePatriciaNode, path: CompactNibblePath): Option[Hash] =
    node match {
      case leaf: MerklePatriciaNode.Leaf =>
        if (leaf.remainingPath == path) Some(leaf.dataDigest) else None
      case branch: MerklePatriciaNode.Branch =>
        if (path.isEmpty) None
        else branch.getChild(path.head).flatMap(child => findLeafDigest(child, path.tail))
      case ext: MerklePatriciaNode.Extension =>
        val shared = ext.sharedPath
        if (path.startsWith(shared)) findLeafDigest(ext.child, path.drop(shared.length))
        else None
    }

  private def insertMultipleWithStore[A: Encoder](
    initialNode: MerklePatriciaNode,
    entries: List[(Hex, A)],
    dataStoreRef: Ref[F, Map[Hash, Json]]
  ): F[Either[MerklePatriciaError, MerklePatriciaNode]] =
    entries.zipWithIndex.foldM(initialNode.asRight[MerklePatriciaError]) {
      case (Right(acc), ((path, value), idx)) =>
        val compactPath = CompactNibblePath.fromHexString(path.value)
        val valueJson = value.asJson
        val work = for {
          dataDigest <- Hasher[F].hash(valueJson)
          _ <- dataStoreRef.update(_ + (dataDigest -> valueJson))
          result <- insertWithDigest(acc, compactPath, dataDigest)
        } yield result
        if (idx % yieldEveryN == 0) Async[F].cede *> work <* Async[F].cede
        else work
      case (Left(err), _) =>
        err.asLeft[MerklePatriciaNode].pure[F]
    }

  private def insertMultiple[A: Encoder](
    initialNode: MerklePatriciaNode,
    entries: List[(Hex, A)]
  ): F[Either[MerklePatriciaError, MerklePatriciaNode]] =
    entries.zipWithIndex.foldM(initialNode.asRight[MerklePatriciaError]) {
      case (Right(acc), ((path, value), idx)) =>
        val compactPath = CompactNibblePath.fromHexString(path.value)
        val valueJson = value.asJson
        val work = for {
          dataDigest <- Hasher[F].hash(valueJson)
          result <- insertWithDigest(acc, compactPath, dataDigest)
        } yield result
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
        val compactPath = CompactNibblePath.fromHexString(path.value)
        val work = removeEncodedCompact(acc, compactPath)
        if (idx % yieldEveryN == 0) Async[F].cede *> work <* Async[F].cede
        else work
      case (Left(err), _) =>
        err.asLeft[MerklePatriciaNode].pure[F]
    }

  /** Insert using pre-computed data digest. */
  private def insertWithDigest(
    currentNode: MerklePatriciaNode,
    path: CompactNibblePath,
    dataDigest: Hash
  ): F[Either[MerklePatriciaError, MerklePatriciaNode]] = {

    sealed trait InsertState
    case class InsertContinue(
      currentNode: MerklePatriciaNode,
      key: CompactNibblePath,
      updateParent: MerklePatriciaNode => F[Either[MerklePatriciaError, MerklePatriciaNode]]
    ) extends InsertState
    case class InsertDone(node: Either[MerklePatriciaError, MerklePatriciaNode]) extends InsertState

    def insertForLeafNode(
      leafNode: MerklePatriciaNode.Leaf,
      _key: CompactNibblePath,
      updateParent: MerklePatriciaNode => F[Either[MerklePatriciaError, MerklePatriciaNode]]
    ): F[Either[InsertState, Either[MerklePatriciaError, MerklePatriciaNode]]] = {
      val leafRemaining = leafNode.remainingPath

      if (leafRemaining.length == _key.length && leafRemaining == _key) {
        for {
          newLeaf <- MerklePatriciaNode.Leaf.fromDataDigest[F](_key, dataDigest)
          result <- updateParent(newLeaf)
        } yield result.asRight[InsertState]
      } else {
        val commonPrefixLen = leafRemaining.commonPrefixLength(_key)
        val commonPrefix = leafRemaining.take(commonPrefixLen)
        val leafRemainingAfter = leafRemaining.drop(commonPrefixLen)
        val keyRemainingAfter = _key.drop(commonPrefixLen)

        (for {
          existingLeaf <- MerklePatriciaNode.Leaf.fromDataDigest[F](leafRemainingAfter.tail, leafNode.dataDigest)
          newLeaf <- MerklePatriciaNode.Leaf.fromDataDigest[F](keyRemainingAfter.tail, dataDigest)
          branchNode <- MerklePatriciaNode.Branch.fromByteKeys[F](
            Map[Byte, MerklePatriciaNode](
              leafRemainingAfter.head -> existingLeaf,
              keyRemainingAfter.head -> newLeaf
            )
          )
          resultNode <-
            if (commonPrefix.nonEmpty) MerklePatriciaNode.Extension.fromCompact[F](commonPrefix, branchNode)
            else branchNode.pure[F]
          updatedNode <- updateParent(resultNode)
        } yield InsertDone(updatedNode).asLeft[Either[MerklePatriciaError, MerklePatriciaNode]]).handleError { e =>
          InsertDone(OperationError(e.getMessage).asLeft[MerklePatriciaNode]).asLeft
        }.widen
      }
    }

    def insertForExtensionNode(
      extensionNode: MerklePatriciaNode.Extension,
      _key: CompactNibblePath,
      updateParent: MerklePatriciaNode => F[Either[MerklePatriciaError, MerklePatriciaNode]]
    ): F[Either[InsertState, Either[MerklePatriciaError, MerklePatriciaNode]]] = {
      val extShared = extensionNode.sharedPath
      val commonPrefixLen = extShared.commonPrefixLength(_key)
      val sharedRemaining = extShared.drop(commonPrefixLen)
      val keyRemaining = _key.drop(commonPrefixLen)

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
              MerklePatriciaNode.Extension
                .fromCompact[F](extShared, branch)
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
          existingSubtree <-
            if (sharedRemaining.tail.isEmpty)
              extensionNode.child.pure[F].widen[MerklePatriciaNode]
            else
              MerklePatriciaNode.Extension.fromCompact[F](sharedRemaining.tail, extensionNode.child).widen[MerklePatriciaNode]
          newLeaf <- MerklePatriciaNode.Leaf.fromDataDigest[F](keyRemaining.tail, dataDigest)
          branchNode <- MerklePatriciaNode.Branch.fromByteKeys[F](
            Map(
              sharedRemaining.head -> existingSubtree,
              keyRemaining.head -> newLeaf
            )
          )
          resultNode <-
            if (commonPrefixLen > 0) MerklePatriciaNode.Extension.fromCompact[F](extShared.take(commonPrefixLen), branchNode)
            else branchNode.pure[F]
          updatedNode <- updateParent(resultNode)
        } yield InsertDone(updatedNode).asLeft[Either[MerklePatriciaError, MerklePatriciaNode]]).handleError { e =>
          InsertDone(OperationError(e.getMessage).asLeft[MerklePatriciaNode]).asLeft
        }.widen
      }
    }

    def insertForBranchNode(
      branchNode: MerklePatriciaNode.Branch,
      _key: CompactNibblePath,
      updateParent: MerklePatriciaNode => F[Either[MerklePatriciaError, MerklePatriciaNode]]
    ): F[Either[InsertState, Either[MerklePatriciaError, MerklePatriciaNode]]] =
      if (_key.isEmpty) {
        InsertDone(OperationError("Key exhausted at branch node").asLeft)
          .asLeft[Either[MerklePatriciaError, MerklePatriciaNode]]
          .pure[F]
          .widen
      } else {
        val nibbleValue = _key.head
        val keyRemaining = _key.tail

        branchNode.getChild(nibbleValue) match {
          case Some(childNode) =>
            (InsertContinue(
              childNode,
              keyRemaining,
              (updatedChild: MerklePatriciaNode) => {
                val newPaths = branchNode.internalPaths + (nibbleValue -> updatedChild)
                MerklePatriciaNode.Branch
                  .fromByteKeys[F](newPaths)
                  .flatMap(updateParent)
                  .handleError(e => OperationError(e.getMessage).asLeft)
              }
            ): InsertState).asLeft[Either[MerklePatriciaError, MerklePatriciaNode]].pure[F]

          case None =>
            (for {
              newLeaf <- MerklePatriciaNode.Leaf.fromDataDigest[F](keyRemaining, dataDigest)
              updatedBranch <- MerklePatriciaNode.Branch.fromByteKeys[F](
                branchNode.internalPaths + (nibbleValue -> newLeaf)
              )
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
    key: CompactNibblePath,
    updateParent: Option[MerklePatriciaNode] => F[Either[MerklePatriciaError, Option[MerklePatriciaNode]]]
  ) extends RemoveState
  private case class RemoveDone(nodeOpt: Either[MerklePatriciaError, Option[MerklePatriciaNode]]) extends RemoveState

  private def removeEncodedCompact(
    currentNode: MerklePatriciaNode,
    path: CompactNibblePath
  ): F[Either[MerklePatriciaError, MerklePatriciaNode]] = {

    def removeForLeafNode(
      leafNode: MerklePatriciaNode.Leaf,
      _key: CompactNibblePath,
      updateParent: Option[MerklePatriciaNode] => F[Either[MerklePatriciaError, Option[MerklePatriciaNode]]]
    ): F[Either[RemoveState, Either[MerklePatriciaError, Option[MerklePatriciaNode]]]] = {
      val leafRemaining = leafNode.remainingPath
      if (leafRemaining == _key) {
        updateParent(None).map(_.asRight)
      } else {
        leafNode.some.asRight[MerklePatriciaError].asRight[RemoveState].pure[F].widen
      }
    }

    def removeForExtensionNode(
      extensionNode: MerklePatriciaNode.Extension,
      _key: CompactNibblePath,
      updateParent: Option[MerklePatriciaNode] => F[Either[MerklePatriciaError, Option[MerklePatriciaNode]]]
    ): F[Either[RemoveState, Either[MerklePatriciaError, Option[MerklePatriciaNode]]]] = {
      val extShared = extensionNode.sharedPath
      val commonPrefixLen = extShared.commonPrefixLength(_key)

      if (commonPrefixLen == extShared.length) {
        (RemoveContinue(
          extensionNode.child,
          _key.drop(commonPrefixLen),
          {
            case Some(updatedChild) =>
              updatedChild match {
                case childBranch: MerklePatriciaNode.Branch =>
                  MerklePatriciaNode.Extension
                    .fromCompact[F](extShared, childBranch)
                    .flatMap(node => updateParent(Some(node)))
                    .handleError(e => OperationError(e.getMessage).asLeft)

                case childLeaf: MerklePatriciaNode.Leaf =>
                  MerklePatriciaNode.Leaf
                    .fromDataDigest[F](extShared ++ childLeaf.remainingPath, childLeaf.dataDigest)
                    .flatMap(node => updateParent(Some(node)))
                    .handleError(e => OperationError(e.getMessage).asLeft)

                case childExtension: MerklePatriciaNode.Extension =>
                  MerklePatriciaNode.Extension
                    .fromCompact[F](extShared ++ childExtension.sharedPath, childExtension.child)
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
      _key: CompactNibblePath,
      updateParent: Option[MerklePatriciaNode] => F[Either[MerklePatriciaError, Option[MerklePatriciaNode]]]
    ): F[Either[RemoveState, Either[MerklePatriciaError, Option[MerklePatriciaNode]]]] =
      if (_key.nonEmpty) {
        val nibbleValue = _key.head
        val keyRemaining = _key.tail

        branchNode.getChild(nibbleValue) match {
          case Some(childNode) =>
            RemoveContinue(
              childNode,
              keyRemaining,
              {
                case Some(updatedChild) =>
                  val newPaths = branchNode.internalPaths + (nibbleValue -> updatedChild)
                  MerklePatriciaNode.Branch
                    .fromByteKeys[F](newPaths)
                    .flatMap(node => updateParent(Some(node)))
                    .handleError(e => OperationError(e.getMessage).asLeft)

                case None =>
                  val updatedPaths = branchNode.internalPaths - nibbleValue

                  updatedPaths.size match {
                    case 0 =>
                      updateParent(None)

                    case 1 =>
                      val (remainingNibbleValue, onlyChild) = updatedPaths.head
                      onlyChild match {
                        case leafNode: MerklePatriciaNode.Leaf =>
                          MerklePatriciaNode.Leaf
                            .fromDataDigest[F](
                              CompactNibblePath.single(remainingNibbleValue) ++ leafNode.remainingPath,
                              leafNode.dataDigest
                            )
                            .flatMap(node => updateParent(Some(node)))
                            .handleError(e => OperationError(e.getMessage).asLeft)

                        case extensionNode: MerklePatriciaNode.Extension =>
                          MerklePatriciaNode.Extension
                            .fromCompact[F](CompactNibblePath.single(remainingNibbleValue) ++ extensionNode.sharedPath, extensionNode.child)
                            .flatMap(node => updateParent(Some(node)))
                            .handleError(e => OperationError(e.getMessage).asLeft)

                        case branchNode: MerklePatriciaNode.Branch =>
                          MerklePatriciaNode.Extension
                            .fromCompact[F](CompactNibblePath.single(remainingNibbleValue), branchNode)
                            .flatMap(node => updateParent(Some(node)))
                            .handleError(e => OperationError(e.getMessage).asLeft)
                      }

                    case _ =>
                      MerklePatriciaNode.Branch
                        .fromByteKeys[F](updatedPaths)
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
