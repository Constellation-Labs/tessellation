package com.my.project_template.l0

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.my.project_template.l0.custom_routes.CustomRoutes
import com.my.project_template.shared_data.LifecycleSharedFunctions
import com.my.project_template.shared_data.calculated_state.CalculatedStateService
import com.my.project_template.shared_data.deserializers.Deserializers
import com.my.project_template.shared_data.serializers.Serializers
import com.my.project_template.shared_data.types.Types.{UsageUpdate, UsageUpdateCalculatedState, UsageUpdateState}
import io.circe.{Decoder, Encoder}
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.{EntityDecoder, HttpRoutes}
import io.constellationnetwork.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.l0.CurrencyL0App
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotStateProof}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionAmount}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import eu.timepit.refined.auto._
import cats.syntax.applicative._

import java.util.UUID
import scala.collection.immutable.{SortedMap, SortedSet}

object Main
    extends CurrencyL0App(
      "currency-l0",
      "currency L0 node",
      ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      tessellationVersion = TessellationVersion.unsafeFrom("1.0.0"),
      metagraphVersion = MetagraphVersion.unsafeFrom("1.0.0")
    ) {

  private def makeBaseDataApplicationL0Service(
    calculatedStateService: CalculatedStateService[IO]
  ): BaseDataApplicationL0Service[IO] =
    BaseDataApplicationL0Service(new DataApplicationL0Service[IO, UsageUpdate, UsageUpdateState, UsageUpdateCalculatedState] {
      override def genesis: DataState[UsageUpdateState, UsageUpdateCalculatedState] =
        DataState(UsageUpdateState(List.empty), UsageUpdateCalculatedState(Map.empty))

      override def validateData(
        state: DataState[UsageUpdateState, UsageUpdateCalculatedState],
        updates: NonEmptyList[Signed[UsageUpdate]]
      )(implicit context: L0NodeContext[IO]): IO[DataApplicationValidationErrorOr[Unit]] =
        IO.pure(().validNec)

      override def combine(
        state: DataState[UsageUpdateState, UsageUpdateCalculatedState],
        updates: List[Signed[UsageUpdate]]
      )(implicit context: L0NodeContext[IO]): IO[DataState[UsageUpdateState, UsageUpdateCalculatedState]] =
        LifecycleSharedFunctions.combine[IO](state, updates)

      override def dataEncoder: Encoder[UsageUpdate] =
        implicitly[Encoder[UsageUpdate]]

      override def calculatedStateEncoder: Encoder[UsageUpdateCalculatedState] =
        implicitly[Encoder[UsageUpdateCalculatedState]]

      override def dataDecoder: Decoder[UsageUpdate] =
        implicitly[Decoder[UsageUpdate]]

      override def calculatedStateDecoder: Decoder[UsageUpdateCalculatedState] =
        implicitly[Decoder[UsageUpdateCalculatedState]]

      override def signedDataEntityDecoder: EntityDecoder[IO, Signed[UsageUpdate]] =
        circeEntityDecoder

      override def serializeBlock(
        block: Signed[DataApplicationBlock]
      ): IO[Array[Byte]] =
        IO(Serializers.serializeBlock(block)(dataEncoder.asInstanceOf[Encoder[DataUpdate]]))

      override def deserializeBlock(
        bytes: Array[Byte]
      ): IO[Either[Throwable, Signed[DataApplicationBlock]]] =
        IO(Deserializers.deserializeBlock(bytes)(dataDecoder.asInstanceOf[Decoder[DataUpdate]]))

      override def serializeState(
        state: UsageUpdateState
      ): IO[Array[Byte]] =
        IO(Serializers.serializeState(state))

      override def deserializeState(
        bytes: Array[Byte]
      ): IO[Either[Throwable, UsageUpdateState]] =
        IO(Deserializers.deserializeState(bytes))

      override def serializeUpdate(
        update: UsageUpdate
      ): IO[Array[Byte]] =
        IO(Serializers.serializeUpdate(update))

      override def deserializeUpdate(
        bytes: Array[Byte]
      ): IO[Either[Throwable, UsageUpdate]] =
        IO(Deserializers.deserializeUpdate(bytes))

      override def getCalculatedState(implicit context: L0NodeContext[IO]): IO[(SnapshotOrdinal, UsageUpdateCalculatedState)] =
        calculatedStateService.getCalculatedState.map(calculatedState => (calculatedState.ordinal, calculatedState.state))

      override def setCalculatedState(
        ordinal: SnapshotOrdinal,
        state: UsageUpdateCalculatedState
      )(implicit context: L0NodeContext[IO]): IO[Boolean] =
        calculatedStateService.setCalculatedState(ordinal, state)

      override def hashCalculatedState(
        state: UsageUpdateCalculatedState
      )(implicit context: L0NodeContext[IO]): IO[Hash] =
        calculatedStateService.hashCalculatedState(state)

      override def routes(implicit context: L0NodeContext[IO]): HttpRoutes[IO] =
        CustomRoutes[IO](calculatedStateService, context).public

      override def serializeCalculatedState(
        state: UsageUpdateCalculatedState
      ): IO[Array[Byte]] =
        IO(Serializers.serializeCalculatedState(state))

      override def deserializeCalculatedState(
        bytes: Array[Byte]
      ): IO[Either[Throwable, UsageUpdateCalculatedState]] =
        IO(Deserializers.deserializeCalculatedState(bytes))
    })

  private def makeL0Service: IO[BaseDataApplicationL0Service[IO]] =
    for {
      calculatedStateService <- CalculatedStateService.make[IO]
      dataApplicationL0Service = makeBaseDataApplicationL0Service(calculatedStateService)
    } yield dataApplicationL0Service

  override def dataApplication: Option[Resource[IO, BaseDataApplicationL0Service[IO]]] =
    makeL0Service.asResource.some

  override def rewards(
    implicit sp: SecurityProvider[IO]
  ): Some[Rewards[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]] =
    Some {
      (
        _: Signed[CurrencyIncrementalSnapshot],
        _: SortedMap[Address, Balance],
        _: SortedSet[Signed[Transaction]],
        _: ConsensusTrigger,
        _: Set[CurrencySnapshotEvent],
        _: Option[DataCalculatedState]
      ) =>
        val allRewards = SortedSet(
          Address("DAG8pkb7EhCkT3yU87B2yPBunSCPnEdmX2Wv24sZ"),
          Address("DAG4o41NzhfX6DyYBTTXu6sJa6awm36abJpv89jB")
        ).map(RewardTransaction(_, TransactionAmount(55_500_0000L)))

        IO.pure(allRewards)
    }
}
