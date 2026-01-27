package io.constellationnetwork.security.mpt

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

/** Incremental trie operations for immutable MPT nodes.
  *
  * These operations rebuild only the path from root to the modified leaf, reusing unchanged subtrees. Each new node computes its digest at
  * construction.
  */
object IncrementalTrieOps {

  def insert[F[_]: Async: Hasher](
    root: MerklePatriciaNode,
    key: Hex,
    dataDigest: Hash
  ): F[MerklePatriciaNode] = {
    val path = CompactNibblePath.fromHexString(key.value)
    insertAt(root, path, 0, dataDigest)
  }

  def remove[F[_]: Async: Hasher](
    root: MerklePatriciaNode,
    key: Hex
  ): F[MerklePatriciaNode] = {
    val path = CompactNibblePath.fromHexString(key.value)
    removeAt(root, path, 0)
  }

  def insertMultiple[F[_]: Async: Hasher](
    root: MerklePatriciaNode,
    entries: List[(Hex, Hash)]
  ): F[MerklePatriciaNode] =
    entries.foldLeftM(root) {
      case (node, (key, hash)) =>
        insert(node, key, hash)
    }

  def removeMultiple[F[_]: Async: Hasher](
    root: MerklePatriciaNode,
    keys: List[Hex]
  ): F[MerklePatriciaNode] =
    keys.foldLeftM(root) {
      case (node, key) =>
        remove(node, key)
    }

  private def insertAt[F[_]: Async: Hasher](
    node: MerklePatriciaNode,
    path: CompactNibblePath,
    depth: Int,
    dataDigest: Hash
  ): F[MerklePatriciaNode] =
    node match {
      case branch: MerklePatriciaNode.Branch =>
        insertIntoBranch(branch, path, depth, dataDigest)
      case ext: MerklePatriciaNode.Extension =>
        insertIntoExtension(ext, path, depth, dataDigest)
      case leaf: MerklePatriciaNode.Leaf =>
        insertIntoLeaf(leaf, path, depth, dataDigest)
    }

  private def insertIntoBranch[F[_]: Async: Hasher](
    branch: MerklePatriciaNode.Branch,
    path: CompactNibblePath,
    depth: Int,
    dataDigest: Hash
  ): F[MerklePatriciaNode] =
    if (depth >= path.length) {
      // Key exhausted at branch - rebuild branch (unusual case)
      MerklePatriciaNode.Branch.fromByteKeys(branch.internalPaths).widen
    } else {
      val nibbleValue = path.get(depth)
      branch.getChild(nibbleValue) match {
        case Some(child) =>
          // Recurse into existing child, then rebuild branch with updated child
          insertAt(child, path, depth + 1, dataDigest).flatMap { updatedChild =>
            MerklePatriciaNode.Branch.fromByteKeys(branch.internalPaths.updated(nibbleValue, updatedChild)).widen
          }
        case None =>
          // Create new leaf for this path
          val remaining = if (depth + 1 >= path.length) CompactNibblePath.empty else path.drop(depth + 1)
          MerklePatriciaNode.Leaf.fromCompact[F](remaining, dataDigest).flatMap { newLeaf =>
            MerklePatriciaNode.Branch.fromByteKeys(branch.internalPaths + (nibbleValue -> newLeaf)).widen
          }
      }
    }

  private def insertIntoExtension[F[_]: Async: Hasher](
    ext: MerklePatriciaNode.Extension,
    path: CompactNibblePath,
    depth: Int,
    dataDigest: Hash
  ): F[MerklePatriciaNode] =
    // Fixed-length keys should never be exhausted at an Extension node
    if (depth >= path.length)
      Async[F].raiseError(new IllegalStateException(s"Key exhausted at Extension node (depth=$depth, pathLen=${path.length})"))
    else {
      val shared = ext.sharedPath
      val keyRemaining = path.drop(depth)
      val commonLen = shared.commonPrefixLength(keyRemaining)

      if (commonLen == shared.length) {
        // Full match - recurse into child
        insertAt(ext.child, path, depth + shared.length, dataDigest).flatMap {
          case updatedBranch: MerklePatriciaNode.Branch =>
            MerklePatriciaNode.Extension.fromCompact(shared, updatedBranch).widen
          case other =>
            // Child collapsed to non-branch, merge paths
            other match {
              case leaf: MerklePatriciaNode.Leaf =>
                MerklePatriciaNode.Leaf.fromCompact(shared ++ leaf.remainingPath, leaf.dataDigest).widen
              case childExt: MerklePatriciaNode.Extension =>
                MerklePatriciaNode.Extension.fromCompact(shared ++ childExt.sharedPath, childExt.child).widen
              case b: MerklePatriciaNode.Branch =>
                MerklePatriciaNode.Extension.fromCompact(shared, b).widen
            }
        }
      } else {
        // Partial match - need to split
        splitExtension(shared, ext.child, keyRemaining, dataDigest, commonLen)
      }
    }

  private def splitExtension[F[_]: Async: Hasher](
    extShared: CompactNibblePath,
    extChild: MerklePatriciaNode.Branch,
    keyRemaining: CompactNibblePath,
    dataDigest: Hash,
    commonLen: Int
  ): F[MerklePatriciaNode] =
    for {
      // Create new leaf for the inserted key
      newLeafRemaining <- Async[F].pure(
        if (commonLen + 1 >= keyRemaining.length) CompactNibblePath.empty
        else keyRemaining.drop(commonLen + 1)
      )
      newLeaf <- MerklePatriciaNode.Leaf.fromCompact[F](newLeafRemaining, dataDigest)

      existingNibble = extShared.get(commonLen)
      newNibble = keyRemaining.get(commonLen)

      // Create the existing subtree (extension's remaining path + child)
      existingChildPath = extShared.drop(commonLen + 1)
      existingSubtree <-
        if (existingChildPath.isEmpty) extChild.pure[F].widen[MerklePatriciaNode]
        else MerklePatriciaNode.Extension.fromCompact(existingChildPath, extChild).widen[MerklePatriciaNode]

      // Create branch with both children
      newBranch <- MerklePatriciaNode.Branch.fromByteKeys(
        Map(
          existingNibble -> existingSubtree,
          newNibble -> newLeaf
        )
      )

      // Wrap with common prefix extension if needed
      result <-
        if (commonLen > 0) {
          val commonPath = extShared.take(commonLen)
          MerklePatriciaNode.Extension.fromCompact(commonPath, newBranch).widen
        } else {
          newBranch.pure[F].widen
        }
    } yield result

  private def insertIntoLeaf[F[_]: Async: Hasher](
    leaf: MerklePatriciaNode.Leaf,
    path: CompactNibblePath,
    depth: Int,
    dataDigest: Hash
  ): F[MerklePatriciaNode] = {
    val leafRemaining = leaf.remainingPath
    val keyRemaining = if (depth >= path.length) CompactNibblePath.empty else path.drop(depth)

    if (leafRemaining == keyRemaining) {
      // Same key - update value
      MerklePatriciaNode.Leaf.fromCompact[F](leafRemaining, dataDigest).widen
    } else {
      // Different keys - split into branch
      val commonLen = leafRemaining.commonPrefixLength(keyRemaining)

      for {
        // New leaf for inserted key
        newLeafRemaining <- Async[F].pure(
          if (commonLen + 1 >= keyRemaining.length) CompactNibblePath.empty
          else keyRemaining.drop(commonLen + 1)
        )
        newLeaf <- MerklePatriciaNode.Leaf.fromCompact[F](newLeafRemaining, dataDigest)

        // Existing leaf with shortened path
        existingLeafRemaining =
          if (commonLen + 1 >= leafRemaining.length) CompactNibblePath.empty
          else leafRemaining.drop(commonLen + 1)
        existingLeaf <- MerklePatriciaNode.Leaf.fromCompact[F](existingLeafRemaining, leaf.dataDigest)

        // Fixed-length keys guarantee both paths have a nibble at commonLen
        // (if they were equal length and matched fully, line 176 would handle it)
        existingNibble = leafRemaining.get(commonLen)
        newNibble = keyRemaining.get(commonLen)

        branch <- MerklePatriciaNode.Branch.fromByteKeys(
          Map(
            existingNibble -> existingLeaf,
            newNibble -> newLeaf
          )
        )

        result <-
          if (commonLen > 0) {
            val commonPath = leafRemaining.take(commonLen)
            MerklePatriciaNode.Extension.fromCompact(commonPath, branch).widen
          } else {
            branch.pure[F].widen
          }
      } yield result
    }
  }

  private def removeAt[F[_]: Async: Hasher](
    node: MerklePatriciaNode,
    path: CompactNibblePath,
    depth: Int
  ): F[MerklePatriciaNode] =
    node match {
      case branch: MerklePatriciaNode.Branch =>
        removeFromBranch(branch, path, depth)
      case ext: MerklePatriciaNode.Extension =>
        removeFromExtension(ext, path, depth)
      case leaf: MerklePatriciaNode.Leaf =>
        val leafPath = leaf.remainingPath
        val keyRemaining = if (depth >= path.length) CompactNibblePath.empty else path.drop(depth)
        if (leafPath == keyRemaining) {
          // Found - return empty branch
          MerklePatriciaNode.Branch.empty[F].widen
        } else {
          // Not found - return unchanged
          leaf.pure[F].widen
        }
    }

  private def removeFromBranch[F[_]: Async: Hasher](
    branch: MerklePatriciaNode.Branch,
    path: CompactNibblePath,
    depth: Int
  ): F[MerklePatriciaNode] =
    if (depth >= path.length) {
      branch.pure[F].widen
    } else {
      val nibbleValue = path.get(depth)
      branch.getChild(nibbleValue) match {
        case Some(child) =>
          removeAt(child, path, depth + 1).flatMap { updatedChild =>
            updatedChild match {
              case b: MerklePatriciaNode.Branch if b.internalPaths.isEmpty =>
                // Child was deleted
                val newPaths = branch.internalPaths - nibbleValue
                collapseOrRebuildBranch(newPaths)
              case _ =>
                // Child was updated
                val newPaths = branch.internalPaths.updated(nibbleValue, updatedChild)
                collapseOrRebuildBranch(newPaths)
            }
          }
        case None =>
          // Key not found
          branch.pure[F].widen
      }
    }

  private def removeFromExtension[F[_]: Async: Hasher](
    ext: MerklePatriciaNode.Extension,
    path: CompactNibblePath,
    depth: Int
  ): F[MerklePatriciaNode] = {
    val shared = ext.sharedPath
    val keyRemaining = if (depth >= path.length) CompactNibblePath.empty else path.drop(depth)

    if (keyRemaining.startsWith(shared)) {
      removeAt(ext.child, path, depth + shared.length).flatMap {
        case b: MerklePatriciaNode.Branch if b.internalPaths.isEmpty =>
          MerklePatriciaNode.Branch.empty[F].widen
        case b: MerklePatriciaNode.Branch if b.internalPaths.size == 1 =>
          // Collapse extension with single-child branch
          collapseExtensionWithSingleChildBranch(shared, b)
        case b: MerklePatriciaNode.Branch =>
          MerklePatriciaNode.Extension.fromCompact(shared, b).widen
        case leaf: MerklePatriciaNode.Leaf =>
          // Child became leaf - merge paths
          MerklePatriciaNode.Leaf.fromCompact(shared ++ leaf.remainingPath, leaf.dataDigest).widen
        case childExt: MerklePatriciaNode.Extension =>
          // Child became extension - merge paths
          MerklePatriciaNode.Extension.fromCompact(shared ++ childExt.sharedPath, childExt.child).widen
      }
    } else {
      // Key doesn't match extension path
      ext.pure[F].widen
    }
  }

  private def collapseOrRebuildBranch[F[_]: Async: Hasher](
    paths: Map[Byte, MerklePatriciaNode]
  ): F[MerklePatriciaNode] =
    paths.size match {
      case 0 =>
        MerklePatriciaNode.Branch.empty[F].widen
      case 1 =>
        val (nibbleValue, child) = paths.head
        child match {
          case leaf: MerklePatriciaNode.Leaf =>
            MerklePatriciaNode.Leaf.fromCompact(CompactNibblePath.single(nibbleValue) ++ leaf.remainingPath, leaf.dataDigest).widen
          case ext: MerklePatriciaNode.Extension =>
            MerklePatriciaNode.Extension.fromCompact(CompactNibblePath.single(nibbleValue) ++ ext.sharedPath, ext.child).widen
          case b: MerklePatriciaNode.Branch =>
            MerklePatriciaNode.Extension.fromCompact(CompactNibblePath.single(nibbleValue), b).widen
        }
      case _ =>
        MerklePatriciaNode.Branch.fromByteKeys(paths).widen
    }

  private def collapseExtensionWithSingleChildBranch[F[_]: Async: Hasher](
    extPath: CompactNibblePath,
    branch: MerklePatriciaNode.Branch
  ): F[MerklePatriciaNode] = {
    val (nibbleValue, child) = branch.internalPaths.head
    val newPath = extPath ++ CompactNibblePath.single(nibbleValue)

    child match {
      case leaf: MerklePatriciaNode.Leaf =>
        MerklePatriciaNode.Leaf.fromCompact(newPath ++ leaf.remainingPath, leaf.dataDigest).widen
      case ext: MerklePatriciaNode.Extension =>
        MerklePatriciaNode.Extension.fromCompact(newPath ++ ext.sharedPath, ext.child).widen
      case b: MerklePatriciaNode.Branch =>
        MerklePatriciaNode.Extension.fromCompact(newPath, b).widen
    }
  }
}
