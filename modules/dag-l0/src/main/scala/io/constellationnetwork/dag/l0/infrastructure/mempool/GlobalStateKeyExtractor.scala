package io.constellationnetwork.dag.l0.infrastructure.mempool

import cats.Applicative
import cats.syntax.all._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.node.shared.infrastructure.mempool.StateKeyExtractor
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.mpt.GlobalStateFieldId._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, PartitionNamespace}
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendBlock}
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockBlock}
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security.signature.Signed

/** Extracts GlobalStateKey from GlobalSnapshotEvent.
  *
  * Used for conflict detection and parallel processing during consensus. Events with overlapping state keys conflict and must be processed
  * sequentially.
  */
class GlobalStateKeyExtractor[F[_]: Applicative] extends StateKeyExtractor[F, GlobalSnapshotEvent, GlobalStateKey] {

  override def extractKeys(event: GlobalSnapshotEvent): F[Set[GlobalStateKey]] =
    event match {
      case DAGEvent(signedBlock) =>
        extractDAGEventKeys(signedBlock).pure[F]

      case StateChannelEvent(output) =>
        Set(
          GlobalStateKey.metagraph(output.address, LastStateChannelSnapshotHashes)
        ).pure[F]

      case AllowSpendEvent(signedBlock) =>
        extractAllowSpendKeys(signedBlock).pure[F]

      case TokenLockEvent(signedBlock) =>
        extractTokenLockKeys(signedBlock).pure[F]

      case UpdateNodeParametersEvent(signedParams) =>
        Set(
          GlobalStateKey.hypergraph(UpdateNodeParameters, signedParams.value.source)
        ).pure[F]

      case CreateDelegatedStakeEvent(signed) =>
        Set(
          GlobalStateKey.hypergraph(ActiveDelegatedStakes, signed.value.source)
        ).pure[F]

      case WithdrawDelegatedStakeEvent(signed) =>
        Set(
          GlobalStateKey.hypergraph(DelegatedStakesWithdrawals, signed.value.source)
        ).pure[F]

      case CreateNodeCollateralEvent(signed) =>
        Set(
          GlobalStateKey.hypergraph(ActiveNodeCollaterals, signed.value.source)
        ).pure[F]

      case WithdrawNodeCollateralEvent(signed) =>
        Set(
          GlobalStateKey.hypergraph(NodeCollateralWithdrawals, signed.value.source)
        ).pure[F]
    }

  private def extractDAGEventKeys(block: Block): Set[GlobalStateKey] =
    block.transactions.toList.flatMap { signedTx: Signed[Transaction] =>
      Set(
        GlobalStateKey.hypergraph(Balances, signedTx.source),
        GlobalStateKey.hypergraph(Balances, signedTx.destination),
        GlobalStateKey.hypergraph(LastTxRefs, signedTx.source)
      )
    }.toSet

  /** Extract state keys for AllowSpend transactions.
    *
    * NOTE: Creates keys for all approvers, which is correct for conflict detection. However, AllowSpends with many approvers may create
    * pathological conflict patterns where unrelated transactions appear to conflict because they share an approver. Monitor in production.
    */
  private def extractAllowSpendKeys(block: AllowSpendBlock): Set[GlobalStateKey] =
    block.transactions.toList.flatMap { signedAs: Signed[AllowSpend] =>
      val baseKeys = Set(
        GlobalStateKey.hypergraph(ActiveAllowSpends, signedAs.source),
        GlobalStateKey.hypergraph(LastAllowSpendRefs, signedAs.source),
        GlobalStateKey.hypergraph(Balances, signedAs.source),
        GlobalStateKey.hypergraph(Balances, signedAs.destination)
      )
      val approverKeys = signedAs.approvers.map(addr => GlobalStateKey.hypergraph(ActiveAllowSpends, addr)).toSet
      baseKeys ++ approverKeys
    }.toSet

  private def extractTokenLockKeys(block: TokenLockBlock): Set[GlobalStateKey] =
    block.tokenLocks.toList.flatMap { signedTl: Signed[TokenLock] =>
      Set(
        GlobalStateKey.hypergraph(ActiveTokenLocks, signedTl.source),
        GlobalStateKey.hypergraph(LastTokenLockRefs, signedTl.source),
        GlobalStateKey.hypergraph(TokenLockBalances, signedTl.source)
      )
    }.toSet
}

object GlobalStateKeyExtractor {

  def make[F[_]: Applicative]: StateKeyExtractor[F, GlobalSnapshotEvent, GlobalStateKey] =
    new GlobalStateKeyExtractor[F]
}
