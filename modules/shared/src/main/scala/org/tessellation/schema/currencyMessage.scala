package org.tessellation.schema

import cats.Order
import cats.kernel.{Next, PartialOrder, PartialPrevious}
import cats.syntax.semigroup._

import org.tessellation.currency.schema.currency.CurrencySnapshotInfo
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.derevo.ordering
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum.values.{StringCirceEnum, StringEnum, StringEnumEntry}
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.{Decoder, Encoder}

object currencyMessage {
  @derive(eqv, show, order, ordering)
  sealed abstract class MessageType(val value: String) extends StringEnumEntry

  object MessageType extends StringEnum[MessageType] with StringCirceEnum[MessageType] {
    val values = findValues

    case object Owner extends MessageType("Owner")
    case object Staking extends MessageType("Staking")
  }

  @derive(order, ordering, show)
  case class MessageOrdinal(value: NonNegLong)

  object MessageOrdinal {
    def apply(value: Long): Either[String, MessageOrdinal] =
      NonNegLong.from(value).map(MessageOrdinal(_))

    implicit val next: Next[MessageOrdinal] = new Next[MessageOrdinal] {
      def next(a: MessageOrdinal): MessageOrdinal = MessageOrdinal(a.value |+| NonNegLong(1L))
      def partialOrder: PartialOrder[MessageOrdinal] = Order[MessageOrdinal]
    }

    val MinValue: MessageOrdinal = MessageOrdinal(NonNegLong.MinValue)

    implicit val partialPrevious: PartialPrevious[MessageOrdinal] = new PartialPrevious[MessageOrdinal] {
      def partialOrder: PartialOrder[MessageOrdinal] = Order[MessageOrdinal]

      def partialPrevious(a: MessageOrdinal): Option[MessageOrdinal] =
        refineV[NonNegative].apply[Long](a.value.value |+| -1).toOption.map(r => MessageOrdinal(r))
    }

    implicit val encoder: Encoder[MessageOrdinal] = Encoder[NonNegLong].contramap(_.value)

    implicit val decoder: Decoder[MessageOrdinal] = Decoder[NonNegLong].map(MessageOrdinal(_))
  }

  @derive(eqv, show, encoder, decoder, order, ordering)
  case class CurrencyMessage(messageType: MessageType, address: Address, metagraphId: Address, parentOrdinal: MessageOrdinal) {
    def ordinal: MessageOrdinal = parentOrdinal.next
  }

  def fetchStakingAddress(state: CurrencySnapshotInfo): Option[Address] =
    state.lastMessages
      .flatMap(_.get(MessageType.Staking))
      .map(_.address)

  def fetchOwnerAddress(state: CurrencySnapshotInfo): Option[Address] =
    state.lastMessages
      .flatMap(_.get(MessageType.Owner))
      .map(_.address)

  def fetchStakingBalance(metagraphId: Address, state: GlobalSnapshotInfo): Balance =
    state.lastCurrencySnapshots
      .get(metagraphId)
      .map {
        case Left(_) => Balance.empty
        case Right((_, currencyState)) =>
          val maybeStakingAddress = fetchStakingAddress(currencyState)

          maybeStakingAddress.flatMap(state.balances.get).getOrElse(Balance.empty)
      }
      .getOrElse(Balance.empty)

  def fetchMetagraphFeesAddresses(metagraphId: Address, state: GlobalSnapshotInfo): (Option[Address], Option[Address]) =
    state.lastCurrencySnapshots
      .get(metagraphId)
      .map {
        case Left(_) => (None, None)
        case Right((_, currencyState)) =>
          val maybeOwnerAddress = fetchOwnerAddress(currencyState)
          val maybeStakingAddress = fetchStakingAddress(currencyState)
          (maybeOwnerAddress, maybeStakingAddress)
      }
      .getOrElse((None, None))
}
