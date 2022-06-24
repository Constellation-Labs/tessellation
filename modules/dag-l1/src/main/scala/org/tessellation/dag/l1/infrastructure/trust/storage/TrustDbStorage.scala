package org.tessellation.dag.l1.infrastructure.trust.storage

import cats.effect.MonadCancelThrow
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l1.domain.trust.storage.TrustStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._

import doobie.implicits._
import doobie.quill.DoobieContext
import io.getquill.context.Context
import io.getquill.{Literal, SqliteDialect}

object TrustDbStorage {
  case class StoredTrust(
    peerId: PeerId,
    score: Option[Score],
    rating: Option[Rating],
    observationAdjustment: Option[ObservationAdjustment]
  )

  type Ctx = DoobieContext.SQLite[Literal] with Context[SqliteDialect, Literal]

  def make[F[_]: MonadCancelThrow: Database]: TrustStorage[F] =
    make[F](new DoobieContext.SQLite[Literal](Literal))

  def make[F[_]: MonadCancelThrow](ctx: Ctx)(implicit db: Database[F]): TrustStorage[F] =
    new TrustStorage[F] {
      val xa = db.xa

      import ctx._

      val getStoredTrusts = quote {
        querySchema[StoredTrust]("TrustInfo")
      }

      val getStoredTrustScore = quote { (peerId: PeerId) =>
        getStoredTrusts.filter(_.peerId == peerId).map(_.score).take(1)
      }

      val getStoredTrustRating = quote { (peerId: PeerId) =>
        getStoredTrusts.filter(_.peerId == peerId).map(_.rating).take(1)
      }

      val getStoredTrustObservationAdjustment = quote { (peerId: PeerId) =>
        getStoredTrusts.filter(_.peerId == peerId).map(_.observationAdjustment).take(1)
      }

      val insertStoredTrustValues = quote {
        (
          peerId: PeerId,
          score: Option[Score],
          rating: Option[Rating],
          observationAdjustment: Option[ObservationAdjustment]
        ) =>
          getStoredTrusts.insert(
            _.peerId -> peerId,
            _.score -> score,
            _.rating -> rating,
            _.observationAdjustment -> observationAdjustment
          )
      }

      val updateStoredTrustValues = quote {
        (
          peerId: PeerId,
          score: Option[Score],
          rating: Option[Rating],
          observationAmount: Option[ObservationAdjustment]
        ) =>
          getStoredTrusts
            .filter(_.peerId == peerId)
            .update(_.score -> score, _.rating -> rating, _.observationAdjustment -> observationAmount)
      }

      def getScore(peerId: PeerId): F[Option[Score]] =
        run(getStoredTrustScore(lift(peerId)))
          .map(_.headOption.flatten)
          .transact(xa)

      def getRating(peerId: PeerId): F[Option[Rating]] =
        run(getStoredTrustRating(lift(peerId)))
          .map(_.headOption.flatten)
          .transact(xa)

      def getObservationAdjustment(peerId: PeerId): F[Option[ObservationAdjustment]] =
        run(getStoredTrustObservationAdjustment(lift(peerId)))
          .map(_.headOption.flatten)
          .transact(xa)

      def updateTrustValues(trustValues: Map[PeerId, TrustDbValues]): F[Unit] =
        trustValues.toList.traverse {
          case (peerId, dbValues) =>
            run(getStoredTrustScore(lift(peerId)))
              .map(_.headOption)
              .flatMap {
                case Some(_) =>
                  run(
                    updateStoredTrustValues(
                      lift(peerId),
                      lift(dbValues.score),
                      lift(dbValues.rating),
                      lift(dbValues.observationAdjustment)
                    )
                  )
                case None =>
                  run(
                    insertStoredTrustValues(
                      lift(peerId),
                      lift(dbValues.score),
                      lift(dbValues.rating),
                      lift(dbValues.observationAdjustment)
                    )
                  )
              }
        }.transact(xa).as(())

      def clean: F[Unit] = run(getStoredTrusts.delete).transact(xa).as(())
    }
}
