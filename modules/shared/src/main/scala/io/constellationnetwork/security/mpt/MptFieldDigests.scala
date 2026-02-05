package io.constellationnetwork.security.mpt

import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.schema.mpt.{GlobalStateFieldId, GlobalStateKey}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import io.circe.Json
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Utilities for extracting per-field digests from a Merkle Patricia Trie.
  *
  * This allows populating individual field hashes in GlobalSnapshotStateProof even when using MPT format, providing visibility into which
  * state components changed.
  *
  * Use for debugging non-determinism between consensus (incremental sync) and re-traversal (full sync) by comparing per-field hashes.
  */
object MptFieldDigests {

  /** Get the subtrie root hash for a given prefix.
    *
    * Traverses the trie following the prefix nibbles. Returns the digest of:
    *   - The first node that is fully contained within the prefix
    *   - Or Hash.empty if no data exists under that prefix
    */
  def getSubtrieDigest[F[_]: Sync](
    trie: MerklePatriciaTrie,
    prefix: Hex
  ): F[Hash] = {
    val prefixNibbles = Nibble(prefix)

    def traverse(node: MerklePatriciaNode, remainingPrefix: Seq[Nibble]): Hash =
      if (remainingPrefix.isEmpty)
        // We've consumed the entire prefix - return this node's digest
        node.digest
      else
        node match {
          case _: MerklePatriciaNode.Leaf =>
            // Leaf before consuming prefix - the leaf IS under this prefix
            node.digest

          case ext: MerklePatriciaNode.Extension =>
            val shared = ext.shared
            if (remainingPrefix.startsWith(shared))
              // Extension is within our prefix path
              traverse(ext.child, remainingPrefix.drop(shared.length))
            else if (shared.startsWith(remainingPrefix))
              // Extension extends beyond our prefix - return child's digest
              ext.child.digest
            else
              // Divergent path - no data under this prefix
              Hash.empty

          case branch: MerklePatriciaNode.Branch =>
            val nextNibble = remainingPrefix.head
            branch.paths.get(nextNibble) match {
              case Some(child) => traverse(child, remainingPrefix.tail)
              case None        => Hash.empty // No data under this prefix
            }
        }

    traverse(trie.rootNode, prefixNibbles).pure[F]
  }

  /** Build field prefix for hypergraph state.
    *
    * Key format: [networkNamespace][fieldId (8 hex)][contractNamespace][userNamespace] For hypergraph: 00 (HypergraphNamespace) + fieldId
    * as 8 hex chars
    */
  def hypergraphFieldPrefix(fieldId: GlobalStateFieldId): Hex =
    Hex(f"00${fieldId.toInt}%08x")

  /** Extract all hypergraph field digests from a trie */
  def extractAllFieldDigests[F[_]: Sync](
    trie: MerklePatriciaTrie
  ): F[Map[GlobalStateFieldId, Hash]] = {
    val allFieldIds = (0 to 18).flatMap(GlobalStateFieldId.fromInt)

    allFieldIds.toList.traverse { fieldId =>
      val prefix = hypergraphFieldPrefix(fieldId)
      getSubtrieDigest[F](trie, prefix).map(fieldId -> _)
    }.map(_.toMap)
  }

  /** Extract field digests and map to GlobalSnapshotStateProof field structure */
  def extractFieldDigestsForProof[F[_]: Sync](
    trie: MerklePatriciaTrie
  ): F[FieldDigests] =
    extractAllFieldDigests[F](trie).map { digests =>
      import GlobalStateFieldId._

      FieldDigests(
        lastStateChannelSnapshotHashes = digests.getOrElse(LastStateChannelSnapshotHashes, Hash.empty),
        lastTxRefs = digests.getOrElse(LastTxRefs, Hash.empty),
        balances = digests.getOrElse(Balances, Hash.empty),
        lastCurrencySnapshots = digests.get(LastCurrencySnapshots).filterNot(_ == Hash.empty),
        activeAllowSpends = digests.get(ActiveAllowSpends).filterNot(_ == Hash.empty),
        activeTokenLocks = digests.get(ActiveTokenLocks).filterNot(_ == Hash.empty),
        tokenLockBalances = digests.get(TokenLockBalances).filterNot(_ == Hash.empty),
        lastAllowSpendRefs = digests.get(LastAllowSpendRefs).filterNot(_ == Hash.empty),
        lastTokenLockRefs = digests.get(LastTokenLockRefs).filterNot(_ == Hash.empty),
        updateNodeParameters = digests.get(UpdateNodeParameters).filterNot(_ == Hash.empty),
        activeDelegatedStakes = digests.get(ActiveDelegatedStakes).filterNot(_ == Hash.empty),
        delegatedStakesWithdrawals = digests.get(DelegatedStakesWithdrawals).filterNot(_ == Hash.empty),
        activeNodeCollaterals = digests.get(ActiveNodeCollaterals).filterNot(_ == Hash.empty),
        nodeCollateralWithdrawals = digests.get(NodeCollateralWithdrawals).filterNot(_ == Hash.empty),
        priceState = digests.get(PriceState).filterNot(_ == Hash.empty),
        metagraphSyncData = digests.get(MetagraphSyncData).filterNot(_ == Hash.empty)
      )
    }

  /** Structured field digests matching GlobalSnapshotStateProof */
  case class FieldDigests(
    lastStateChannelSnapshotHashes: Hash,
    lastTxRefs: Hash,
    balances: Hash,
    lastCurrencySnapshots: Option[Hash],
    activeAllowSpends: Option[Hash],
    activeTokenLocks: Option[Hash],
    tokenLockBalances: Option[Hash],
    lastAllowSpendRefs: Option[Hash],
    lastTokenLockRefs: Option[Hash],
    updateNodeParameters: Option[Hash],
    activeDelegatedStakes: Option[Hash],
    delegatedStakesWithdrawals: Option[Hash],
    activeNodeCollaterals: Option[Hash],
    nodeCollateralWithdrawals: Option[Hash],
    priceState: Option[Hash],
    metagraphSyncData: Option[Hash]
  )

  /** Compare two sets of field digests and log any differences.
    *
    * Useful for debugging non-determinism between consensus and re-traversal.
    *
    * @param expected
    *   Field digests from consensus/original
    * @param actual
    *   Field digests from re-traversal/rebuilt
    * @param context
    *   Additional context for logging (e.g., ordinal)
    * @return
    *   List of (fieldName, expected, actual) for differing fields
    */
  def compareAndLogDifferences[F[_]: Sync](
    expected: Map[GlobalStateFieldId, Hash],
    actual: Map[GlobalStateFieldId, Hash],
    context: String
  ): F[List[(String, Hash, Hash)]] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("MPT.FieldDigests")

    val allFieldIds = (0 to 18).flatMap(GlobalStateFieldId.fromInt)
    val differences = allFieldIds.flatMap { fieldId =>
      val exp = expected.getOrElse(fieldId, Hash.empty)
      val act = actual.getOrElse(fieldId, Hash.empty)
      if (exp != act) Some((fieldIdToName(fieldId), exp, act)) else None
    }.toList

    if (differences.isEmpty) {
      logger.debug(s"[$context] All field digests match").as(differences)
    } else {
      differences.traverse_ {
        case (name, exp, act) =>
          logger.warn(s"[$context] FIELD MISMATCH: $name - expected=${exp.value.take(16)}..., actual=${act.value.take(16)}...")
      }.as(differences)
    }
  }

  /** Log all field digests for a trie.
    *
    * @param trie
    *   The trie to extract digests from
    * @param context
    *   Additional context for logging (e.g., ordinal, "consensus" or "retraversal")
    */
  def logFieldDigests[F[_]: Sync](
    trie: MerklePatriciaTrie,
    context: String
  ): F[Map[GlobalStateFieldId, Hash]] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("MPT.FieldDigests")

    for {
      digests <- extractAllFieldDigests[F](trie)
      _ <- logger.info(s"[$context] MPT root: ${trie.rootHash.value.value.take(16)}...")
      _ <- digests.toList.sortBy(_._1.toInt).traverse_ {
        case (fieldId, hash) =>
          if (hash != Hash.empty)
            logger.info(s"[$context] Field ${fieldIdToName(fieldId)}: ${hash.value.take(16)}...")
          else
            Sync[F].unit
      }
    } yield digests
  }

  /** Map field ID to human-readable name */
  def fieldIdToName(fieldId: GlobalStateFieldId): String = {
    import GlobalStateFieldId._
    fieldId match {
      case LastStateChannelSnapshotHashes   => "LastStateChannelSnapshotHashes"
      case LastTxRefs                       => "LastTxRefs"
      case Balances                         => "Balances"
      case LastCurrencySnapshots            => "LastCurrencySnapshots"
      case LastCurrencySnapshotsProofs      => "LastCurrencySnapshotsProofs"
      case LastIncrementalCurrencySnapshots => "LastIncrementalCurrencySnapshots"
      case LastCurrencySnapshotInfo         => "LastCurrencySnapshotInfo"
      case ActiveAllowSpends                => "ActiveAllowSpends"
      case ActiveTokenLocks                 => "ActiveTokenLocks"
      case TokenLockBalances                => "TokenLockBalances"
      case LastAllowSpendRefs               => "LastAllowSpendRefs"
      case LastTokenLockRefs                => "LastTokenLockRefs"
      case UpdateNodeParameters             => "UpdateNodeParameters"
      case ActiveDelegatedStakes            => "ActiveDelegatedStakes"
      case DelegatedStakesWithdrawals       => "DelegatedStakesWithdrawals"
      case ActiveNodeCollaterals            => "ActiveNodeCollaterals"
      case NodeCollateralWithdrawals        => "NodeCollateralWithdrawals"
      case PriceState                       => "PriceState"
      case MetagraphSyncData                => "MetagraphSyncData"
    }
  }

  /** Compare two entry sets and log differences.
    *
    * This is the key debugging tool for non-determinism between incremental sync (StateChangesAccumulator.toStateEntries) and full sync
    * (GlobalSnapshotInfo.allStateEntries).
    *
    * @param entriesA
    *   First entry set (e.g., from incremental sync)
    * @param entriesB
    *   Second entry set (e.g., from full sync)
    * @param labelA
    *   Label for first set
    * @param labelB
    *   Label for second set
    * @param context
    *   Additional context (e.g., ordinal)
    */
  def compareEntrySets[F[_]: Sync: Hasher](
    entriesA: Map[GlobalStateKey, Json],
    entriesB: Map[GlobalStateKey, Json],
    labelA: String,
    labelB: String,
    context: String
  ): F[EntrySetComparison] = {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("MPT.EntryComparison")

    val keysA = entriesA.keySet
    val keysB = entriesB.keySet

    val onlyInA = keysA -- keysB
    val onlyInB = keysB -- keysA
    val common = keysA.intersect(keysB)

    // Find keys with different values
    val differentValues = common.filter { key =>
      entriesA(key) != entriesB(key)
    }

    for {
      _ <- logger.info(s"[$context] Entry set comparison: $labelA vs $labelB")
      _ <- logger.info(s"[$context]   $labelA entries: ${entriesA.size}")
      _ <- logger.info(s"[$context]   $labelB entries: ${entriesB.size}")
      _ <- logger.info(s"[$context]   Only in $labelA: ${onlyInA.size}")
      _ <- logger.info(s"[$context]   Only in $labelB: ${onlyInB.size}")
      _ <- logger.info(s"[$context]   Common keys with different values: ${differentValues.size}")

      // Log details for mismatches (limit to first 10)
      _ <- onlyInA.take(10).toList.traverse_ { key =>
        logger.warn(s"[$context] KEY ONLY IN $labelA: field=${fieldIdToName(key.fieldId)}, key=$key")
      }
      _ <- if (onlyInA.size > 10) logger.warn(s"[$context]   ... and ${onlyInA.size - 10} more") else Sync[F].unit

      _ <- onlyInB.take(10).toList.traverse_ { key =>
        logger.warn(s"[$context] KEY ONLY IN $labelB: field=${fieldIdToName(key.fieldId)}, key=$key")
      }
      _ <- if (onlyInB.size > 10) logger.warn(s"[$context]   ... and ${onlyInB.size - 10} more") else Sync[F].unit

      _ <- differentValues.take(10).toList.traverse_ { key =>
        val valA = entriesA(key).noSpaces.take(100)
        val valB = entriesB(key).noSpaces.take(100)
        logger.warn(s"[$context] VALUE DIFFERS: field=${fieldIdToName(key.fieldId)}, key=$key") >>
          logger.warn(s"[$context]   $labelA: $valA...") >>
          logger.warn(s"[$context]   $labelB: $valB...")
      }
      _ <- if (differentValues.size > 10) logger.warn(s"[$context]   ... and ${differentValues.size - 10} more") else Sync[F].unit

      // Group by field for summary
      _ <- {
        val fieldSummary = (onlyInA ++ onlyInB ++ differentValues)
          .groupBy(_.fieldId)
          .view
          .mapValues(_.size)
          .toList
          .sortBy(-_._2)

        fieldSummary.traverse_ {
          case (fieldId, count) =>
            logger.error(s"[$context] FIELD WITH ISSUES: ${fieldIdToName(fieldId)} ($count differences)")
        }
      }
    } yield
      EntrySetComparison(
        onlyInA = onlyInA,
        onlyInB = onlyInB,
        differentValues = differentValues
      )
  }

  /** Result of comparing two entry sets */
  case class EntrySetComparison(
    onlyInA: Set[GlobalStateKey],
    onlyInB: Set[GlobalStateKey],
    differentValues: Set[GlobalStateKey]
  ) {
    def hasDifferences: Boolean = onlyInA.nonEmpty || onlyInB.nonEmpty || differentValues.nonEmpty
  }
}
