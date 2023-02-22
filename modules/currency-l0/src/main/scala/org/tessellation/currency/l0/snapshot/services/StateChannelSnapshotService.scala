package org.tessellation.currency.l0.snapshot.services

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.currency.l0.snapshot.{CurrencySnapshotArtifact, CurrencySnapshotContext}
import org.tessellation.currency.l0.snapshot.storages.LastSignedBinaryHashStorage
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}

trait StateChannelSnapshotService[F[_]] {

  def consume(signedArtifact: Signed[CurrencySnapshotArtifact], context: CurrencySnapshotContext): F[Unit]
  def createGenesisBinary(snapshot: Signed[CurrencySnapshot]): F[Signed[StateChannelSnapshotBinary]]
  def createBinary(snapshot: Signed[CurrencySnapshotArtifact]): F[Signed[StateChannelSnapshotBinary]]
}

object StateChannelSnapshotService {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    lastSignedBinaryHashStorage: LastSignedBinaryHashStorage[F],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): StateChannelSnapshotService[F] =
    new StateChannelSnapshotService[F] {
      private val logger = Slf4jLogger.getLogger

      def createGenesisBinary(snapshot: Signed[CurrencySnapshot]): F[Signed[StateChannelSnapshotBinary]] = for {
        lastSnapshotBinaryHash <- lastSignedBinaryHashStorage.get
        bytes <- snapshot.toBinaryF
        binary <- StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes).sign(keyPair)
      } yield binary

      def createBinary(snapshot: Signed[CurrencySnapshotArtifact]): F[Signed[StateChannelSnapshotBinary]] = for {
        lastSnapshotBinaryHash <- lastSignedBinaryHashStorage.get
        bytes <- snapshot.toBinaryF
        binary <- StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes).sign(keyPair)
      } yield binary

      def consume(signedArtifact: Signed[CurrencySnapshotArtifact], context: CurrencySnapshotContext): F[Unit] = for {
        binary <- createBinary(signedArtifact)
        binaryHash <- binary.hashF
        l0Peer <- globalL0ClusterStorage.getRandomPeer
        _ <- stateChannelSnapshotClient
          .send(binary)(l0Peer)
          .ifM(
            logger.info(s"Sent ${binaryHash.show} to Global L0"),
            logger.error(s"Cannot send ${binaryHash.show} to Global L0 peer ${l0Peer.show}")
          )
        _ <- lastSignedBinaryHashStorage.set(binaryHash)
        _ <- snapshotStorage
          .prepend(signedArtifact, context)
          .ifM(
            Applicative[F].unit,
            logger.error("Cannot save CurrencySnapshot into the storage")
          )
      } yield ()
    }
}
