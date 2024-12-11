package io.constellationnetwork.node.shared.infrastructure.consensus

import java.time.Instant

import cats.effect.kernel.{Async, Clock, Ref}
import cats.syntax.all._

import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.all.PosInt

trait ValidationErrorStorage[F[_], ConsensusEvent, ValidationError] {
  def add(ConsensusEvent: ConsensusEvent, error: ValidationError): F[Unit]

  def add(errors: List[(ConsensusEvent, ValidationError)]): F[Unit]

  def get: F[Vector[ValidationErrorStorageEntry[ConsensusEvent, ValidationError]]]

  def getTransaction(hash: Hash): F[Option[ValidationError]]
}

case class ValidationErrorStorageEntry[ConsensusEvent, ValidationError](
  consensusEvent: ConsensusEvent,
  validationError: ValidationError,
  timestamp: Instant,
  hashes: List[Hash]
)

object ValidationErrorStorage {

  private case class ValidationErrorStorageData[ConsensusEvent, ValidationError](
    consensusEvents: Vector[ValidationErrorStorageEntry[ConsensusEvent, ValidationError]],
    transactions: Map[Hash, ValidationError]
  )

  def make[F[_]: Async, ConsensusEvent, ValidationError](
    maxSize: PosInt,
    extractTransactionHashes: ConsensusEvent => F[List[Hash]]
  ): F[ValidationErrorStorage[F, ConsensusEvent, ValidationError]] =
    Ref[F]
      .of(
        ValidationErrorStorageData(
          Vector.empty[ValidationErrorStorageEntry[ConsensusEvent, ValidationError]],
          Map.empty[Hash, ValidationError]
        )
      )
      .map { storedDataR =>
        new ValidationErrorStorage[F, ConsensusEvent, ValidationError] {
          def add(ConsensusEvent: ConsensusEvent, error: ValidationError): F[Unit] =
            for {
              timestamp <- Clock[F].realTimeInstant
              transactions <- extractTransactionHashes(ConsensusEvent)
              res <- storedDataR.update { storedData =>
                val consensusEventsToDrop =
                  if (storedData.consensusEvents.size >= maxSize)
                    storedData.consensusEvents.takeRight(storedData.consensusEvents.size - maxSize + 1)
                  else Vector.empty

                val transactionsToDrop = consensusEventsToDrop.flatMap(_.hashes)
                val boundedConsensusEvents = storedData.consensusEvents.dropRight(consensusEventsToDrop.size)
                val newConsensusEvents =
                  ValidationErrorStorageEntry(ConsensusEvent, error, timestamp, transactions) +: boundedConsensusEvents
                val newTransactions =
                  storedData.transactions.removedAll(transactionsToDrop) ++ transactions.map(hash => (hash, error)).toMap
                ValidationErrorStorageData(newConsensusEvents, newTransactions)
              }
            } yield res

          def add(errors: List[(ConsensusEvent, ValidationError)]): F[Unit] =
            errors.traverse {
              case (consensusEvent, error) =>
                add(consensusEvent, error)
            }.void

          def get: F[Vector[ValidationErrorStorageEntry[ConsensusEvent, ValidationError]]] = storedDataR.get.map(_.consensusEvents)

          def getTransaction(hash: Hash): F[Option[ValidationError]] = storedDataR.get.map(_.transactions.get(hash))
        }
      }
}
