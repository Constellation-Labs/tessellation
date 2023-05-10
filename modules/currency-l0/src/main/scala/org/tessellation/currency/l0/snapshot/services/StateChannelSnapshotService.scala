package org.tessellation.currency.l0.snapshot.services

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.l0.snapshot.storages.LastBinaryHashStorage
import org.tessellation.currency.l0.snapshot.{CurrencySnapshotArtifact, CurrencySnapshotContext}
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.crypto._
import org.tessellation.json.JsonBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.storage.L0ClusterStorage
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelSnapshotBinary

import org.typelevel.log4cats.slf4j.Slf4jLogger

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
    identifierStorage: IdentifierStorage[F]
  ): StateChannelSnapshotService[F] =
    new StateChannelSnapshotService[F] {
      private val logger = Slf4jLogger.getLogger

      def createGenesisBinary(snapshot: Signed[CurrencySnapshot]): F[Signed[StateChannelSnapshotBinary]] = {
        val bytes = JsonBinarySerializer.serialize(snapshot)
        StateChannelSnapshotBinary(Hash.empty, bytes, SnapshotFee.MinValue).sign(keyPair)
      }

      def createBinary(snapshot: Signed[CurrencySnapshotArtifact]): F[Signed[StateChannelSnapshotBinary]] = for {
        lastSnapshotBinaryHash <- lastBinaryHashStorage.get
        bytes = JsonBinarySerializer.serialize(snapshot)
        binary <- StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes, SnapshotFee.MinValue).sign(keyPair)
      } yield binary

      def consume(signedArtifact: Signed[CurrencySnapshotArtifact], context: CurrencySnapshotContext): F[Unit] = for {
        binary <- createBinary(signedArtifact)
        binaryHashed <- binary.toHashed
        l0Peer <- globalL0ClusterStorage.getRandomPeer
        identifier <- identifierStorage.get
        _ <- stateChannelSnapshotClient
          .send(identifier, binary)(l0Peer)
          .ifM(
            logger.info(s"Sent ${binaryHashed.hash.show} to Global L0"),
            logger.error(s"Cannot send ${binaryHashed.hash.show} to Global L0 peer ${l0Peer.show}")
          )
        _ <- lastBinaryHashStorage.set(binaryHashed.hash)
        _ <- snapshotStorage
          .prepend(signedArtifact, context)
          .ifM(
            Applicative[F].unit,
            logger.error("Cannot save CurrencySnapshot into the storage")
          )
      } yield ()
    }
}
