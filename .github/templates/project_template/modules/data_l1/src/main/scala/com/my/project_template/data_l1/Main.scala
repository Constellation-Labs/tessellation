package com.my.project_template.data_l1

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.my.project_template.shared_data.calculated_state.CalculatedStateService
import com.my.project_template.shared_data.deserializers.Deserializers
import com.my.project_template.shared_data.serializers.Serializers
import com.my.project_template.shared_data.types.Types.{UsageUpdate, UsageUpdateCalculatedState, UsageUpdateState}
import io.circe.{Decoder, Encoder}
import org.http4s.{EntityDecoder, _}
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import io.constellationnetwork.currency.l1.CurrencyL1App
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import java.util.UUID

object Main extends CurrencyL1App(
  "currency-data_l1",
  "currency data L1 node",
  ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
  tessellationVersion = TessellationVersion.unsafeFrom("1.0.0"),
  metagraphVersion = MetagraphVersion.unsafeFrom("1.0.0")
) {

  private def makeBaseDataApplicationL1Service(
    calculatedStateService: CalculatedStateService[IO]
  ): BaseDataApplicationL1Service[IO] = BaseDataApplicationL1Service(
    new DataApplicationL1Service[IO, UsageUpdate, UsageUpdateState, UsageUpdateCalculatedState] {
      override def validateUpdate(
        update: UsageUpdate
      )(implicit context: L1NodeContext[IO]): IO[DataApplicationValidationErrorOr[Unit]] =
        ().validNec.pure[IO]

      override def routes(implicit context: L1NodeContext[IO]): HttpRoutes[IO] =
        HttpRoutes.empty

      override def dataEncoder: Encoder[UsageUpdate] =
        implicitly[Encoder[UsageUpdate]]

      override def dataDecoder: Decoder[UsageUpdate] =
        implicitly[Decoder[UsageUpdate]]

      override def calculatedStateEncoder: Encoder[UsageUpdateCalculatedState] =
        implicitly[Encoder[UsageUpdateCalculatedState]]

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

      override def serializeCalculatedState(
        state: UsageUpdateCalculatedState
      ): IO[Array[Byte]] =
        IO(Serializers.serializeCalculatedState(state))

      override def deserializeCalculatedState(
        bytes: Array[Byte]
      ): IO[Either[Throwable, UsageUpdateCalculatedState]] =
        IO(Deserializers.deserializeCalculatedState(bytes))
    }
  )

  private def makeL1Service: IO[BaseDataApplicationL1Service[IO]] = {
    for {
      calculatedStateService <- CalculatedStateService.make[IO]
      dataApplicationL1Service = makeBaseDataApplicationL1Service(calculatedStateService)
    } yield dataApplicationL1Service
  }

  override def dataApplication: Option[Resource[IO, BaseDataApplicationL1Service[IO]]] =
    makeL1Service.asResource.some
}
