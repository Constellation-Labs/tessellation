package io.constellationnetwork.dag.l1.http.p2p

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait L0BlockOutputClient[F[_]] {
  def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean]
  def sendDataApplicationBlock(block: Signed[DataApplicationBlock])(
    implicit encoder: Encoder[DataTransaction]
  ): PeerResponse[F, Boolean]
  def sendAllowSpendBlock(block: Signed[AllowSpendBlock]): PeerResponse[F, Boolean]
  def sendTokenLockBlock(block: Signed[TokenLockBlock]): PeerResponse[F, Boolean]
}

object L0BlockOutputClient {

  def make[F[_]: Async](pathPrefix: String, client: Client[F]): L0BlockOutputClient[F] =
    new L0BlockOutputClient[F] {

      private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

      def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-output", POST)(client) { (req, c) =>
          // Instrumentation (Part 0): the block-submission path is NOT behind the snapshot 503
          // concurrency limiter, so when `Sending block to L0 failed.` fires it is a non-2xx
          // RESPONSE from the /dag/l1-output route -- expected to be a 4xx (e.g. the route's
          // ParentOrdinalGapTooLarge guard), whose body carries parentOrdinal/gap diagnostics.
          // Previously `c.successful(...)` collapsed this to a bare Boolean and discarded it.
          // Surface the actual status + body so the real failure mode is diagnosable.
          c.run(req.withEntity(output)).use { resp =>
            if (resp.status.isSuccess)
              Async[F].pure(true)
            else
              resp.bodyText.compile.string.flatMap { body =>
                logger
                  .warn(
                    s"[L1-OUTPUT] L0 rejected block: status=${resp.status.code} ${resp.status.reason} " +
                      s"body=${body.take(800)}"
                  )
                  .as(false)
              }
          }
        }

      def sendDataApplicationBlock(
        block: Signed[DataApplicationBlock]
      )(implicit encoder: Encoder[DataTransaction]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-data-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(block))
        }

      def sendAllowSpendBlock(block: Signed[AllowSpendBlock]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-allow-spend-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(block))
        }

      def sendTokenLockBlock(block: Signed[TokenLockBlock]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-token-lock-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(block))
        }

    }
}
