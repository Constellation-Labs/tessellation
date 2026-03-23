package io.constellationnetwork.currency.schema

import io.constellationnetwork.schema.address.Address

/** State key for currency-l0 mempool conflict detection.
  *
  * Identifies the state partition that an event modifies. Events with overlapping keys conflict and must be serialized.
  *
  * Unlike GlobalStateKey (used in dag-l0 for global state), CurrencyStateKey is specific to a single metagraph and doesn't require
  * metagraph namespace scoping since each currency-l0 instance handles only one metagraph.
  *
  * @param fieldType
  *   The type of state field being modified
  * @param userAddress
  *   The user whose state is being modified (if applicable)
  */
case class CurrencyStateKey(
  fieldType: CurrencyStateFieldType,
  userAddress: Option[Address]
)

/** Types of state fields in a currency/metagraph.
  */
sealed trait CurrencyStateFieldType

object CurrencyStateFieldType {

  /** User balance state */
  case object Balances extends CurrencyStateFieldType

  /** Last transaction reference for a user */
  case object LastTxRefs extends CurrencyStateFieldType

  /** Active allow spends for a user */
  case object ActiveAllowSpends extends CurrencyStateFieldType

  /** Last allow spend reference for a user */
  case object LastAllowSpendRefs extends CurrencyStateFieldType

  /** Active token locks for a user */
  case object ActiveTokenLocks extends CurrencyStateFieldType

  /** Last token lock reference for a user */
  case object LastTokenLockRefs extends CurrencyStateFieldType

  /** Token lock balances for a user */
  case object TokenLockBalances extends CurrencyStateFieldType

  /** Data application state */
  case object DataApplicationState extends CurrencyStateFieldType

  /** Currency message state */
  case object CurrencyMessageState extends CurrencyStateFieldType

  /** Sync data from global layer */
  case object SyncData extends CurrencyStateFieldType
}
