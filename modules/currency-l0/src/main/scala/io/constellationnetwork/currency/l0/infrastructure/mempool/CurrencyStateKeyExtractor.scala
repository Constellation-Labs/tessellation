package io.constellationnetwork.currency.l0.infrastructure.mempool

import cats.Applicative
import cats.syntax.all._

import io.constellationnetwork.currency.schema.CurrencyStateFieldType._
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.node.shared.infrastructure.mempool.StateKeyExtractor
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendBlock}
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockBlock}
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security.signature.Signed

class CurrencyStateKeyExtractor[F[_]: Applicative] extends StateKeyExtractor[F, CurrencySnapshotEvent, CurrencyStateKey] {

  override def extractKeys(event: CurrencySnapshotEvent): F[Set[CurrencyStateKey]] =
    event match {
      case BlockEvent(signedBlock) =>
        extractBlockKeys(signedBlock.value).pure[F]

      case AllowSpendBlockEvent(signedBlock) =>
        extractAllowSpendKeys(signedBlock.value).pure[F]

      case TokenLockBlockEvent(signedBlock) =>
        extractTokenLockKeys(signedBlock.value).pure[F]

      case DataApplicationBlockEvent(_) =>
        Set(CurrencyStateKey(DataApplicationState, None)).pure[F]

      case CurrencyMessageEvent(_) =>
        Set(CurrencyStateKey(CurrencyMessageState, None)).pure[F]

      case GlobalSnapshotSyncEvent(_) =>
        Set(CurrencyStateKey(SyncData, None)).pure[F]

      case ForceEventTrigger() =>
        Set.empty[CurrencyStateKey].pure[F]
    }

  private def extractBlockKeys(block: Block): Set[CurrencyStateKey] =
    block.transactions.toList.flatMap { signedTx: Signed[Transaction] =>
      Set(
        CurrencyStateKey(Balances, Some(signedTx.source)),
        CurrencyStateKey(Balances, Some(signedTx.destination)),
        CurrencyStateKey(LastTxRefs, Some(signedTx.source))
      )
    }.toSet

  private def extractAllowSpendKeys(block: AllowSpendBlock): Set[CurrencyStateKey] =
    block.transactions.toList.flatMap { signedAs: Signed[AllowSpend] =>
      val baseKeys = Set(
        CurrencyStateKey(ActiveAllowSpends, Some(signedAs.source)),
        CurrencyStateKey(LastAllowSpendRefs, Some(signedAs.source)),
        CurrencyStateKey(Balances, Some(signedAs.source)),
        CurrencyStateKey(Balances, Some(signedAs.destination))
      )
      // NOTE: Approver-only keying may cause false conflicts if two AllowSpend
      // blocks share an approver. Acceptable: AcceptanceManager validates actual state.
      val approverKeys = signedAs.approvers.map(addr => CurrencyStateKey(ActiveAllowSpends, Some(addr))).toSet
      baseKeys ++ approverKeys
    }.toSet

  private def extractTokenLockKeys(block: TokenLockBlock): Set[CurrencyStateKey] =
    block.tokenLocks.toList.flatMap { signedTl: Signed[TokenLock] =>
      Set(
        CurrencyStateKey(ActiveTokenLocks, Some(signedTl.source)),
        CurrencyStateKey(LastTokenLockRefs, Some(signedTl.source)),
        CurrencyStateKey(TokenLockBalances, Some(signedTl.source))
      )
    }.toSet
}

object CurrencyStateKeyExtractor {

  def make[F[_]: Applicative]: StateKeyExtractor[F, CurrencySnapshotEvent, CurrencyStateKey] =
    new CurrencyStateKeyExtractor[F]
}
