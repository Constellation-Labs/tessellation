package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator.{SpendActionValidationError, SpendActionValidationErrorOr}
import io.constellationnetwork.node.shared.infrastructure.snapshot.AllowSpendBlockAcceptanceMode
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.Mocks._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendAction
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelValidationType

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object SpendActionAggregateBalanceGateSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp)

  private val activationOrdinal = 10L
  private val activation = SnapshotOrdinal.unsafeApply(activationOrdinal)

  private def recordingValidator(flags: Ref[IO, List[Boolean]]): SpendActionValidator[IO] = new SpendActionValidator[IO] {
    def validate(
      spendAction: SpendAction,
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      currencyId: Address
    ): IO[SpendActionValidationErrorOr[SpendAction]] = spendAction.validNec[SpendActionValidationError].pure[IO]

    def validateReturningAcceptedAndRejected(
      spendActions: Map[Address, List[SpendAction]],
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      enforceAggregateBalance: Boolean
    ): IO[(Map[Address, List[SpendAction]], Map[Address, List[(SpendAction, List[SpendActionValidationError])]])] =
      flags
        .update(_ :+ enforceAggregateBalance)
        .as((Map.empty[Address, List[SpendAction]], Map.empty[Address, List[(SpendAction, List[SpendActionValidationError])]]))
  }

  private def enforcedAt(ordinals: List[SnapshotOrdinal])(implicit h: Hasher[IO], sp: SecurityProvider[IO]): IO[List[Boolean]] =
    for {
      flags <- Ref.of[IO, List[Boolean]](List.empty)
      lastSnapshotContext = mkGlobalSnapshotInfo()
      manager <- mkManager(
        lastSnapshotContext.some,
        fixingSpendActionAggregateBalanceOrdinal = activation,
        spendActionValidatorOverride = recordingValidator(flags).some
      )
      _ <- ordinals.traverse_ { ordinal =>
        manager.accept(
          ordinal = ordinal,
          epochProgress = EpochProgress(10L),
          blocksForAcceptance = List.empty,
          allowSpendBlocksForAcceptance = List.empty,
          tokenLockBlocksForAcceptance = List.empty,
          scEvents = List.empty,
          unpEvents = List.empty,
          cdsEvents = List.empty,
          wdsEvents = List.empty,
          cncEvents = List.empty,
          wncEvents = List.empty,
          lastSnapshotContext = lastSnapshotContext,
          lastActiveTips = SortedSet.empty,
          lastDeprecatedTips = SortedSet.empty,
          calculateRewardsFn = delegatedRewardsFunction(lastSnapshotContext),
          validationType = StateChannelValidationType.Full,
          getGlobalSnapshotByOrdinal = _ => None.pure[IO],
          allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
        )
      }
      recorded <- flags.get
    } yield recorded

  test("the aggregate balance check is off before the activation ordinal and on from it") { res =>
    implicit val (h, sp) = res

    val ordinals = List(activationOrdinal - 1L, activationOrdinal, activationOrdinal + 1L).map(SnapshotOrdinal.unsafeApply(_))

    enforcedAt(ordinals).map { recorded =>
      expect.same(List(false, true, true), recorded)
    }
  }
}
