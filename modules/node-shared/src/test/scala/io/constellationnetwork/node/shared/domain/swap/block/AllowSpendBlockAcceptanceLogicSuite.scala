package io.constellationnetwork.node.shared.domain.swap.block

import java.util.UUID

import cats.data.Validated.Valid
import cats.data.{EitherT, NonEmptySet, ValidatedNec}
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.swap.AllowSpendChainValidator.AllowSpendNel
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.MutableIOSuite

object AllowSpendBlockAcceptanceLogicSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp)

  private val source = Address("DAG011jH7FMDvKpdb7wewrMWwYtkwq56nHquAHdi")
  private val destination = Address("DAG06z64ifT2HzXoHfMexRfrcnpYFEwMqjFiPKze")
  private val onward = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")

  private def balance(value: Long): Balance = Balance(NonNegLong.unsafeFrom(value))

  private def proofOf(seed: Int): NonEmptySet[SignatureProof] = {
    val hex = (seed.toString * 128).take(128)
    NonEmptySet.one(SignatureProof(Id(Hex(hex)), Signature(Hex(hex))))
  }

  private def blockOf(from: Address, to: Address, amount: Long, fee: Long, seed: Int): Signed[AllowSpendBlock] = {
    val allowSpend = Signed(
      AllowSpend(
        from,
        to,
        None,
        SwapAmount(PosLong.unsafeFrom(amount)),
        AllowSpendFee(NonNegLong.unsafeFrom(fee)),
        AllowSpendReference.empty,
        EpochProgress(NonNegLong.unsafeFrom(100L)),
        List.empty
      ),
      proofOf(seed)
    )

    val roundId = RoundId(UUID.fromString(s"00000000-0000-0000-0000-00000000000$seed"))

    Signed(AllowSpendBlock(roundId, NonEmptySet.one(allowSpend)), proofOf(seed))
  }

  private def contextOf(entries: (Address, Long)*): AllowSpendBlockAcceptanceContext[IO] =
    AllowSpendBlockAcceptanceContext.fromStaticData[IO](
      entries.map { case (address, value) => address -> balance(value) }.toMap,
      Map.empty,
      Amount.empty,
      AllowSpendReference.empty
    )

  // txChains is empty so processLastTxRefs is a no-op, and collateral validation is skipped; what is
  // left is processBalances.
  private def accept(
    signedBlock: Signed[AllowSpendBlock],
    context: AllowSpendBlockAcceptanceContext[IO],
    contextUpdate: AllowSpendBlockAcceptanceContextUpdate,
    creditDestination: Boolean
  )(
    implicit h: Hasher[IO],
    sp: SecurityProvider[IO]
  ): IO[Either[AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate]] =
    AllowSpendBlockAcceptanceLogic
      .make[IO]
      .acceptBlock(signedBlock, Map.empty, context, contextUpdate, shouldPerformMetagraphSpecificValidations = false, creditDestination)
      .value

  test("escrows at the source without crediting the destination") { res =>
    implicit val (h, sp) = res

    accept(
      blockOf(source, destination, 10L, 1L, 1),
      contextOf(source -> 100L),
      AllowSpendBlockAcceptanceContextUpdate.empty,
      creditDestination = false
    ).map {
      case Right(update) =>
        expect.all(
          update.balances.get(source).contains(balance(89L)),
          update.balances.get(destination).isEmpty
        )
      case Left(reason) => failure(s"expected acceptance, got $reason")
    }
  }

  test("credits the destination only when legacy behavior is explicitly selected") { res =>
    implicit val (h, sp) = res

    accept(
      blockOf(source, destination, 10L, 1L, 1),
      contextOf(source -> 100L),
      AllowSpendBlockAcceptanceContextUpdate.empty,
      creditDestination = true
    ).map {
      case Right(update) =>
        expect.all(
          update.balances.get(source).contains(balance(89L)),
          update.balances.get(destination).contains(balance(10L))
        )
      case Left(reason) => failure(s"expected acceptance, got $reason")
    }
  }

  // AllowSpendBlockAcceptanceManager threads contextUpdate across blocks, so under the old behaviour a
  // destination credited by the first block funds an allow spend of its own in the second, out of a
  // balance no snapshot ever recorded for it.
  test("a destination cannot fund a second block once the credit is dropped") { res =>
    implicit val (h, sp) = res

    val context = contextOf(source -> 101L)

    for {
      first <- accept(blockOf(source, destination, 100L, 1L, 1), context, AllowSpendBlockAcceptanceContextUpdate.empty, false)
      firstUpdate = first.getOrElse(AllowSpendBlockAcceptanceContextUpdate.empty)
      second <- accept(blockOf(destination, onward, 100L, 0L, 2), context, firstUpdate, false)
    } yield
      expect.all(
        first.exists(_.balances.get(source).contains(balance(0L))),
        first.exists(_.balances.get(destination).isEmpty),
        second.swap.exists(_.isInstanceOf[AddressBalanceOutOfRange])
      )
  }

  test("a destination funds a second block under the old behaviour") { res =>
    implicit val (h, sp) = res

    val context = contextOf(source -> 101L)

    for {
      first <- accept(blockOf(source, destination, 100L, 1L, 1), context, AllowSpendBlockAcceptanceContextUpdate.empty, true)
      firstUpdate = first.getOrElse(AllowSpendBlockAcceptanceContextUpdate.empty)
      second <- accept(blockOf(destination, onward, 100L, 0L, 2), context, firstUpdate, true)
    } yield
      expect.all(
        first.exists(_.balances.get(destination).contains(balance(100L))),
        second.exists(_.balances.get(onward).contains(balance(100L)))
      )
  }

  test("iterative acceptance retries phantom-funded blocks only under legacy semantics") { res =>
    implicit val (h, sp) = res

    val first = blockOf(source, destination, 100L, 1L, 1)
    val second = blockOf(destination, onward, 100L, 0L, 2)
    val context = contextOf(source -> 101L)

    val validator = new AllowSpendBlockValidator[IO] {
      def validate(
        signedBlock: Signed[AllowSpendBlock],
        snapshotOrdinal: io.constellationnetwork.schema.SnapshotOrdinal,
        params: AllowSpendBlockValidationParams,
        lastGlobalSnapshotEpochProgress: Option[EpochProgress]
      )(implicit hasher: Hasher[IO]): IO[
        ValidatedNec[AllowSpendBlockValidationError, (Signed[AllowSpendBlock], Map[Address, AllowSpendNel])]
      ] = IO.pure(Valid((signedBlock, Map.empty)))
    }

    for {
      attempts <- Ref.of[IO, List[RoundId]](List.empty)
      underlying = AllowSpendBlockAcceptanceLogic.make[IO]
      recordingLogic = new AllowSpendBlockAcceptanceLogic[IO] {
        def acceptBlock(
          block: Signed[AllowSpendBlock],
          txChains: Map[Address, AllowSpendNel],
          context: AllowSpendBlockAcceptanceContext[IO],
          contextUpdate: AllowSpendBlockAcceptanceContextUpdate,
          shouldPerformMetagraphSpecificValidations: Boolean,
          creditDestination: Boolean
        )(implicit hasher: Hasher[IO]): EitherT[IO, AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate] =
          EitherT.liftF[IO, AllowSpendBlockNotAcceptedReason, Unit](attempts.update(block.value.roundId :: _)) >>
            underlying.acceptBlock(block, txChains, context, contextUpdate, shouldPerformMetagraphSpecificValidations, creditDestination)(
              hasher
            )
      }
      manager = AllowSpendBlockAcceptanceManager.make[IO](recordingLogic, validator)
      legacy <- manager.acceptBlocksIteratively(
        List(first, second),
        context,
        io.constellationnetwork.schema.SnapshotOrdinal.MinValue,
        shouldPerformMetagraphSpecificValidations = false,
        None,
        creditDestination = true
      )
      legacyAttempts <- attempts.get
      _ <- attempts.set(List.empty)
      escrow <- manager.acceptBlocksIteratively(
        List(first, second),
        context,
        io.constellationnetwork.schema.SnapshotOrdinal.MinValue,
        shouldPerformMetagraphSpecificValidations = false,
        None,
        creditDestination = false
      )
      escrowAttempts <- attempts.get
    } yield
      expect.all(
        legacy.accepted.toSet == Set(first, second),
        legacy.notAccepted.isEmpty,
        legacyAttempts.count(_ == second.value.roundId) == 2,
        escrow.accepted == List(first),
        escrow.notAccepted.map(_._1) == List(second),
        escrowAttempts.count(_ == second.value.roundId) == 2
      )
  }
}
