package org.tessellation.dag.l1

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.storage.BlockStorage
import org.tessellation.infrastructure.gossip.RumorHandler
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.ReceivedRumor
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

object DAGBlockRumorHandler {

  def getHandler[F[_]: Async: SecurityProvider: KryoSerializer](blockStorage: BlockStorage[F]): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromReceivedRumorFn[F, Signed[DAGBlock]]() {
      case ReceivedRumor(_, signedBlock) =>
        for {
          maybeHashed <- signedBlock.hashWithSignatureCheck
          _ <- maybeHashed match {
            case Left(e) =>
              logger.warn(e)("Received invalid block!")
            case Right(hashedBlock) =>
              logger.debug(s"Storing received block with hash ${hashedBlock.proofsHash}") >>
                blockStorage.storeBlock(hashedBlock)
          }
        } yield ()
    }
  }
}
