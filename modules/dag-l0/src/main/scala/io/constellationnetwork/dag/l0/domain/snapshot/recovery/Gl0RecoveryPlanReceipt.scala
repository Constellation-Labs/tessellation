package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import java.nio.file.FileAlreadyExistsException

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Mutex
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.storage.CrashSafeAtomicFileWriter
import io.constellationnetwork.security.signature.Signed

import fs2.io.file.Path

/** Initialization-local authorization backed by a durable, exclusive consumed-plan receipt.
  *
  * A successfully consumed signed plan may be retried idempotently by the same receipt instance. A fresh initialization, including an
  * in-process application restart, starts with an empty in-memory authorization and therefore encounters the durable `CREATE_NEW` receipt,
  * failing closed. The receipt directory must be outside every rollback-pruned snapshot subdirectory.
  */
trait Gl0RecoveryPlanReceipt[F[_]] {
  def consume(signed: Signed[Gl0RecoveryPlan]): F[Unit]
}

object Gl0RecoveryPlanReceipt {

  final case class AlreadyConsumed(planId: String)
      extends IllegalStateException(
        s"GL0 recovery plan=$planId was already consumed by an earlier process; generate and sign a new planId"
      )

  final case class PlanIdReusedInProcess(planId: String)
      extends IllegalStateException(
        s"GL0 recovery planId=$planId was reused for different signed content in the same receipt initialization"
      )

  def make[F[_]: Async: JsonSerializer](base: Path): F[Gl0RecoveryPlanReceipt[F]] =
    for {
      writer <- CrashSafeAtomicFileWriter.make[F](base)
      authorized <- Ref.of[F, Option[Signed[Gl0RecoveryPlan]]](None)
      mutex <- Mutex[F]
    } yield
      new Gl0RecoveryPlanReceipt[F] {
        def consume(signed: Signed[Gl0RecoveryPlan]): F[Unit] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              authorized.get.flatMap {
                case Some(existing) if existing === signed => Async[F].unit
                case Some(existing) if existing.value.planId === signed.value.planId =>
                  PlanIdReusedInProcess(signed.value.planId.value).raiseError[F, Unit]
                case Some(existing) =>
                  // One application invocation authorizes exactly one recovery operation. This prevents
                  // a second plan from changing the recovery boundary after initialization has begun.
                  PlanIdReusedInProcess(existing.value.planId.value).raiseError[F, Unit]
                case None =>
                  JsonSerializer[F]
                    .serialize(signed)
                    .flatMap(writer.writeNew(s"${signed.value.planId.value}.consumed", _))
                    .adaptError { case _: FileAlreadyExistsException => AlreadyConsumed(signed.value.planId.value) } >>
                    authorized.set(signed.some)
              }
            }
          }
      }
}
