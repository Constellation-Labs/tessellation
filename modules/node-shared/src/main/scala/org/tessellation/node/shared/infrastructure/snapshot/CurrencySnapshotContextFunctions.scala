package org.tessellation.node.shared.infrastructure.snapshot

import cats.data.{NonEmptyChain, Validated}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotContext}
import org.tessellation.merkletree.StateProofValidator
import org.tessellation.node.shared.domain.snapshot.SnapshotContextFunctions
import org.tessellation.security.HasherSelector
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

abstract class CurrencySnapshotContextFunctions[F[_]]
    extends SnapshotContextFunctions[F, CurrencyIncrementalSnapshot, CurrencySnapshotContext]

object CurrencySnapshotContextFunctions {
  def make[F[_]: Async: HasherSelector](validator: CurrencySnapshotValidator[F]) =
    new CurrencySnapshotContextFunctions[F] {
      def createContext(
        context: CurrencySnapshotContext,
        lastArtifact: Signed[CurrencyIncrementalSnapshot],
        signedArtifact: Signed[CurrencyIncrementalSnapshot]
      ): F[CurrencySnapshotContext] = for {
        validatedS <- validator.validateSignedSnapshot(lastArtifact, context, signedArtifact)
        validatedContext <- validatedS match {
          case Validated.Valid((_, validatedContext)) => validatedContext.pure[F]
          case Validated.Invalid(e)                   => CannotCreateContext(e).raiseError[F, CurrencySnapshotContext]
        }
        _ <- HasherSelector[F]
          .forOrdinal(signedArtifact.ordinal) { implicit hasher =>
            StateProofValidator.validate(signedArtifact, validatedContext.snapshotInfo)
          }
          .flatMap {
            case Validated.Valid(_)   => Async[F].unit
            case Validated.Invalid(e) => e.raiseError[F, Unit]
          }
      } yield validatedContext

    }

  @derive(eqv, show)
  case class CannotCreateContext(reasons: NonEmptyChain[CurrencySnapshotValidationError]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build currency snapshot ${reasons.show}"
  }
}
