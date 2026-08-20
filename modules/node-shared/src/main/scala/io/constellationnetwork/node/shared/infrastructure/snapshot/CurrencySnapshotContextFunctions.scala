package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Parallel
import cats.data.{NonEmptyChain, Validated}
import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotContext, CurrencySnapshotInfo}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.schema._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.validator.StateProofValidator

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

abstract class CurrencySnapshotContextFunctions[F[_]]
    extends SnapshotContextFunctions[F, CurrencyIncrementalSnapshot, CurrencySnapshotContext] {
  def createHistoricalContext(
    context: CurrencySnapshotContext,
    lastArtifact: Signed[CurrencyIncrementalSnapshot],
    signedArtifact: Signed[CurrencyIncrementalSnapshot],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotContext]
}

object CurrencySnapshotContextFunctions {
  def make[F[_]: Async: Parallel: JsonSerializer](validator: CurrencySnapshotValidator[F])(
    implicit currencyStateProofSelector: CurrencyStateProofSelector
  ) =
    new CurrencySnapshotContextFunctions[F] {
      private def create(
        context: CurrencySnapshotContext,
        lastArtifact: Signed[CurrencyIncrementalSnapshot],
        signedArtifact: Signed[CurrencyIncrementalSnapshot],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
        historicalDependencyResolution: Boolean
      )(implicit hasher: Hasher[F]): F[CurrencySnapshotContext] = for {
        validatedS <- validator.validateSignedSnapshot(
          lastArtifact,
          context,
          signedArtifact,
          getGlobalSnapshotByOrdinal,
          historicalDependencyResolution
        )
        validatedContext <- validatedS match {
          case Validated.Valid((_, validatedContext)) => validatedContext.pure[F]
          case Validated.Invalid(e)                   => CannotCreateContext(e).raiseError[F, CurrencySnapshotContext]
        }
        _ <- signedArtifact.toHashed.flatMap { hashed =>
          CurrencySnapshotInfo.stateProofBuilder[F].buildProof(validatedContext.snapshotInfo, hashed.ordinal).flatMap { proof =>
            StateProofValidator.validateProof(hashed, proof).flatMap {
              case Validated.Valid(_)   => Async[F].unit
              case Validated.Invalid(e) => e.raiseError[F, Unit]
            }
          }
        }
      } yield validatedContext

      def createContext(
        context: CurrencySnapshotContext,
        lastArtifact: Signed[CurrencyIncrementalSnapshot],
        signedArtifact: Signed[CurrencyIncrementalSnapshot],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[CurrencySnapshotContext] =
        create(context, lastArtifact, signedArtifact, getGlobalSnapshotByOrdinal, historicalDependencyResolution = false)

      def createHistoricalContext(
        context: CurrencySnapshotContext,
        lastArtifact: Signed[CurrencyIncrementalSnapshot],
        signedArtifact: Signed[CurrencyIncrementalSnapshot],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[CurrencySnapshotContext] =
        create(context, lastArtifact, signedArtifact, getGlobalSnapshotByOrdinal, historicalDependencyResolution = true)

    }

  @derive(eqv, show)
  case class CannotCreateContext(reasons: NonEmptyChain[CurrencySnapshotValidationError]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build currency snapshot ${reasons.show}"
  }
}
