package org.tessellation.currency.l0.snapshot

import cats._
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.snapshot.SnapshotContextFunctions
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import higherkindness.droste._
import higherkindness.droste.data._
import higherkindness.droste.util.DefaultTraverse

sealed trait StackF[A]

case class More[A](a: A, step: Signed[CurrencyIncrementalSnapshot]) extends StackF[A]
case class Done[A](result: CurrencySnapshot) extends StackF[A]

object StackF {

  implicit val traverse: Traverse[StackF] = new DefaultTraverse[StackF] {
    override def traverse[G[_]: Applicative, A, B](fa: StackF[A])(f: A => G[B]): G[StackF[B]] =
      fa match {
        case More(a, step) => f(a).map(More(_, step))
        case Done(r)       => (Done(r): StackF[B]).pure[G]
      }
  }
}

trait CurrencySnapshotTraverse[F[_]] {
  def computeState(latest: Signed[CurrencyIncrementalSnapshot]): F[CurrencySnapshotInfo]
}

object CurrencySnapshotTraverse {

  def loadGlobalSnapshotCoalgebra[F[_]: Monad](
    loadSnapshotFn: Hash => F[Either[Signed[CurrencySnapshot], Signed[CurrencyIncrementalSnapshot]]]
  ): CoalgebraM[F, StackF, Either[Signed[CurrencySnapshot], Signed[CurrencyIncrementalSnapshot]]] = CoalgebraM {
    case Left(snapshot) => Applicative[F].pure(Done(snapshot))
    case Right(incrementalSnapshot) =>
      def prevHash = incrementalSnapshot.lastSnapshotHash

      loadSnapshotFn(prevHash).map(More(_, incrementalSnapshot))
  }

  def computeStateAlgebra[F[_]: MonadThrow: KryoSerializer](
    applyGlobalSnapshotFn: (
      CurrencySnapshotInfo,
      CurrencyIncrementalSnapshot,
      Signed[CurrencyIncrementalSnapshot]
    ) => F[CurrencySnapshotInfo]
  ): GAlgebraM[F, StackF, Attr[StackF, CurrencySnapshotInfo], CurrencySnapshotInfo] = GAlgebraM {
    case Done(snapshot) => snapshot.info.pure[F]
    case More(info :< Done(snapshot), incrementalSnapshot) =>
      CurrencyIncrementalSnapshot.fromCurrencySnapshot[F](snapshot).flatMap {
        applyGlobalSnapshotFn(info, _, incrementalSnapshot)
      }
    case More(info :< More(_ :< _, previousIncrementalSnapshot), incrementalSnapshot) =>
      applyGlobalSnapshotFn(info, previousIncrementalSnapshot, incrementalSnapshot)
  }

  def make[F[_]: MonadThrow: KryoSerializer](
    loadSnapshotFn: Hash => F[Either[Signed[CurrencySnapshot], Signed[CurrencyIncrementalSnapshot]]],
    snapshotInfoFunctions: SnapshotContextFunctions[F, CurrencySnapshotArtifact, CurrencySnapshotContext]
  ): CurrencySnapshotTraverse[F] =
    new CurrencySnapshotTraverse[F] {

      def computeState(latest: Signed[CurrencyIncrementalSnapshot]): F[CurrencySnapshotInfo] =
        scheme
          .ghyloM(
            computeStateAlgebra(snapshotInfoFunctions.createContext).gather(Gather.histo),
            loadGlobalSnapshotCoalgebra(loadSnapshotFn).scatter(Scatter.ana)
          )
          .apply(latest.asRight[Signed[CurrencySnapshot]])
    }

}
