package org.tessellation.currency.l0.snapshot.services

import java.security.KeyPair

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._

import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.l0.snapshot.CurrencySnapshotArtifact
import org.tessellation.currency.l0.snapshot.storages.LastBinaryHashStorage
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.crypto._
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import org.tessellation.node.shared.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.node.shared.infrastructure.snapshot.DataApplicationSnapshotAcceptanceManager
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}
import org.tessellation.statechannel.StateChannelSnapshotBinary

import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry._

trait StateChannelSnapshotService[F[_]] {

  def consume(signedArtifact: Signed[CurrencySnapshotArtifact], context: CurrencySnapshotContext): F[Unit]
  def createGenesisBinary(snapshot: Signed[CurrencySnapshot]): F[Signed[StateChannelSnapshotBinary]]
  def createBinary(snapshot: Signed[CurrencySnapshotArtifact]): F[Signed[StateChannelSnapshotBinary]]
}

object StateChannelSnapshotService {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    lastBinaryHashStorage: LastBinaryHashStorage[F],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    identifierStorage: IdentifierStorage[F],
    jsonBrotliBinarySerializer: JsonBrotliBinarySerializer[F],
    dataApplicationSnapshotAcceptanceManager: Option[DataApplicationSnapshotAcceptanceManager[F]]
  ): StateChannelSnapshotService[F] =
    new StateChannelSnapshotService[F] {
      private val logger = Slf4jLogger.getLogger

      private val sendRetries = 5

      private val retryPolicy: RetryPolicy[F] = RetryPolicies.limitRetries(sendRetries)

      private def wasSuccessful: Either[NonEmptyList[StateChannelValidationError], Unit] => F[Boolean] =
        _.isRight.pure[F]

      private def onFailure(binaryHashed: Hashed[StateChannelSnapshotBinary]) =
        (_: Either[NonEmptyList[StateChannelValidationError], Unit], details: RetryDetails) =>
          logger.info(s"Retrying sending ${binaryHashed.hash.show} to Global L0 after rejection. Retries so far ${details.retriesSoFar}")

      private def onError(binaryHashed: Hashed[StateChannelSnapshotBinary]) = (_: Throwable, details: RetryDetails) =>
        logger.info(s"Retrying sending ${binaryHashed.hash.show} to Global L0 after error. Retries so far ${details.retriesSoFar}")

      def createGenesisBinary(snapshot: Signed[CurrencySnapshot]): F[Signed[StateChannelSnapshotBinary]] =
        jsonBrotliBinarySerializer
          .serialize(snapshot)
          .flatMap(StateChannelSnapshotBinary(Hash.empty, _, SnapshotFee.MinValue).sign(keyPair))

      def createBinary(snapshot: Signed[CurrencySnapshotArtifact]): F[Signed[StateChannelSnapshotBinary]] = for {
        lastSnapshotBinaryHash <- lastBinaryHashStorage.get
        bytes <- jsonBrotliBinarySerializer.serialize(snapshot)
        binary <- StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes, SnapshotFee.MinValue).sign(keyPair)
      } yield binary

      def consume(signedArtifact: Signed[CurrencySnapshotArtifact], context: CurrencySnapshotContext): F[Unit] = for {
        binary <- createBinary(signedArtifact)
        binaryHashed <- binary.toHashed
        identifier <- identifierStorage.get
        _ <- lastBinaryHashStorage.set(binaryHashed.hash)
        _ <- dataApplicationSnapshotAcceptanceManager.traverse { manager =>
          snapshotStorage.head.map { lastSnapshot =>
            lastSnapshot.flatMap { case (value, _) => value.dataApplication }
          }.flatMap(manager.consumeSignedMajorityArtifact(_, signedArtifact))
        }
        _ <- snapshotStorage
          .prepend(signedArtifact, context.snapshotInfo)
          .ifM(
            Applicative[F].unit,
            logger.error(
              s"Cannot save CurrencySnapshot ordinal=${signedArtifact.ordinal} for metagraph identifier=${context.address} into the storage."
            )
          )
        _ <- retryingOnFailuresAndAllErrors[Either[NonEmptyList[StateChannelValidationError], Unit]](
          retryPolicy,
          wasSuccessful,
          onFailure(binaryHashed),
          onError(binaryHashed)
        )(
          globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
            stateChannelSnapshotClient
              .send(identifier, binary)(l0Peer)
              .onError(e => logger.warn(e)(s"Sending ${binaryHashed.hash.show} snapshot to Global L0 peer ${l0Peer.show} failed!"))
              .flatTap {
                case Right(_) => logger.info(s"Sent ${binaryHashed.hash.show} to Global L0 peer ${l0Peer.show}")
                case Left(errors) =>
                  logger.error(s"Snapshot ${binaryHashed.hash.show} rejected by Global L0 peer ${l0Peer.show}. Reasons: ${errors.show}")
              }
          }
        )
      } yield ()
    }
}
