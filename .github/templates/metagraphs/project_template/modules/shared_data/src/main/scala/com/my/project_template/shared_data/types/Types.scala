package com.my.project_template.shared_data.types

import io.constellationnetwork.currency.dataApplication.{DataCalculatedState, DataOnChainState, DataUpdate}
import io.constellationnetwork.ext.refined.{decoderOf, encoderOf}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendTransaction
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.tokenLock.TokenLockAmount
import io.constellationnetwork.security.hash.Hash

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.{Decoder, Encoder}

object Types {
  implicit val nonNegLongEncoder: Encoder[NonNegLong] =
    encoderOf[Long, NonNegative]

  implicit val nonNegLongDecoder: Decoder[NonNegLong] =
    decoderOf[Long, NonNegative]

  @derive(decoder, encoder)
  case class UsageUpdateInfo(
    deviceAddress: Address,
    deviceUsage: NonNegLong
  )

  @derive(decoder, encoder)
  case class DeviceCalculatedState(
    usages: UsageUpdateInfo
  )

  @derive(decoder, encoder)
  sealed trait UsageUpdate extends DataUpdate {
    val address: Address
    val usage: NonNegLong
  }

  @derive(decoder, encoder)
  case class UsageUpdateNoFee(
    address: Address,
    usage: NonNegLong
  ) extends UsageUpdate

  @derive(decoder, encoder)
  case class UsageUpdateWithFee(
    address: Address,
    usage: NonNegLong
  ) extends UsageUpdate

  @derive(decoder, encoder)
  case class UsageUpdateWithSpendTransaction(
    address: Address,
    usage: NonNegLong,
    spendTransactionA: SpendTransaction,
    spendTransactionB: SpendTransaction
  ) extends UsageUpdate

  @derive(decoder, encoder)
  case class UsageUpdateWithTokenUnlock(
    address: Address,
    currencyId: CurrencyId,
    tokenLockRef: Hash,
    unlockAmount: TokenLockAmount,
    usage: NonNegLong
  ) extends UsageUpdate

  /** Data update that triggers a token lock replacement unlock. When a token lock is replaced, the old lock's amount is unlocked. This
    * update type produces a TokenUnlock artifact for the replaced lock.
    *
    * @param address
    *   The address that owns the token lock
    * @param currencyId
    *   The currency of the token lock being replaced
    * @param originalTokenLockRef
    *   Hash of the original token lock being replaced
    * @param originalAmount
    *   Amount from the original lock to unlock
    * @param replacementTokenLockRef
    *   Hash of the new replacement token lock
    * @param usage
    *   Usage counter for testing
    */
  @derive(decoder, encoder)
  case class UsageUpdateWithTokenLockReplacement(
    address: Address,
    currencyId: CurrencyId,
    originalTokenLockRef: Hash,
    originalAmount: TokenLockAmount,
    replacementTokenLockRef: Hash,
    usage: NonNegLong
  ) extends UsageUpdate

  @derive(decoder, encoder)
  case class UsageUpdateState(
    updates: List[UsageUpdate]
  ) extends DataOnChainState

  @derive(decoder, encoder)
  case class UsageUpdateCalculatedState(
    devices: Map[Address, DeviceCalculatedState]
  ) extends DataCalculatedState
}
