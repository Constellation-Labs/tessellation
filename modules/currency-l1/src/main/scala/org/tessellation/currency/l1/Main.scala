package org.tessellation.currency.l1

import java.nio.charset.StandardCharsets
import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._

import org.tessellation.BuildInfo
import org.tessellation.currency._
import org.tessellation.currency.dataApplication.DataApplicationValidationErrorOr
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.security.signature.Signed
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

object Acme {

  @derive(decoder, encoder)
  sealed trait Usage extends DataUpdate {
    val value: Long
  }

  @derive(decoder, encoder)
  case class EnergyUsage(value: Long) extends Usage

  @derive(decoder, encoder)
  case class WaterUsage(value: Long) extends Usage

  @derive(decoder, encoder)
  case class State(totalEnergyUsage: Long, totalWaterUsage: Long) extends DataState

  case object EnergyNotPositive extends DataApplicationValidationError {
    val message = "energy ussage must be positive"
  }
  case object WaterNotPositive extends DataApplicationValidationError {
    val message = "water ussage must be positive"
  }
  case object Noop extends DataApplicationValidationError {
    val message = "invalid update"
  }

  val energy: DataApplicationL1Service[IO, Usage, State] = new DataApplicationL1Service[IO, Usage, State] {
    def validateData(oldState: State, updates: NonEmptyList[Signed[Usage]]): IO[DataApplicationValidationErrorOr[Unit]] =
      IO {
        updates
          .map(_.value)
          .map {
            case update: EnergyUsage =>
              if (update.value > 0L) ().validNec else EnergyNotPositive.asInstanceOf[DataApplicationValidationError].invalidNec
            case update: WaterUsage =>
              if (update.value > 0L) ().validNec else WaterNotPositive.asInstanceOf[DataApplicationValidationError].invalidNec
          }
          .reduce
      }

    def validateUpdate(update: Usage): IO[DataApplicationValidationErrorOr[Unit]] = IO {
      update match {
        case u: EnergyUsage =>
          if (u.value > 0L) ().validNec else EnergyNotPositive.asInstanceOf[DataApplicationValidationError].invalidNec
        case u: WaterUsage =>
          if (u.value > 0L) ().validNec else WaterNotPositive.asInstanceOf[DataApplicationValidationError].invalidNec
      }
    }

    def enqueue(value: Usage): IO[Unit] = ???

    def combine(oldState: State, updates: NonEmptyList[Signed[Usage]]): IO[State] =
      IO {
        updates.foldLeft(oldState) { (acc, update) =>
          update match {
            case Signed(EnergyUsage(usage), _) => State(acc.totalEnergyUsage + usage, acc.totalWaterUsage)
            case Signed(WaterUsage(usage), _)  => State(acc.totalEnergyUsage, acc.totalWaterUsage + usage)
          }
        }
      }

    def serializeState(state: State): IO[Array[Byte]] = IO {
      state.asJson.toString.getBytes(StandardCharsets.UTF_8)
    }

    def deserializeState(bytes: Array[Byte]): IO[Either[Throwable, State]] = IO {
      parse(new String(bytes, StandardCharsets.UTF_8)).flatMap { json =>
        json.as[State]
      }
    }

    def serializeUpdate(update: Usage): IO[Array[Byte]] = IO {
      update.asJson.toString.getBytes(StandardCharsets.UTF_8)
    }

    def deserializeUpdate(bytes: Array[Byte]): IO[Either[Throwable, Usage]] = IO {
      parse(new String(bytes, StandardCharsets.UTF_8)).flatMap { json =>
        json.as[Usage]
      }
    }

    def dataEncoder: Encoder[Usage] = deriveEncoder

    def dataDecoder: Decoder[Usage] = deriveDecoder

    def sample(implicit S: SecurityProvider[IO]): IO[Signed[Usage]] = KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      implicit def su: Usage => IO[Array[Byte]] = serializeUpdate
      Signed.forAsync[IO, Usage](EnergyUsage(2L), keyPair)
    }
  }
}

object Main
    extends CurrencyL1App(
      s"Currency-l1",
      s"Currency L1 node",
      ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      version = BuildInfo.version
    ) {
  override def dataApplication: Option[BaseDataApplicationL1Service[IO]] = None // BaseDataApplicationL1Service(Acme.energy).some
}
