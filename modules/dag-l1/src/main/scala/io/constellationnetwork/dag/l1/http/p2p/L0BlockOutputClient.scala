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

import io.circe.parser.decode
import io.circe.{Decoder, Encoder}
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait L0BlockOutputClient[F[_]] {
  def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean]
  def sendL1OutputDetailed(output: Signed[Block]): PeerResponse[F, L0BlockOutputClient.L1OutputSubmissionResult]
  def sendDataApplicationBlock(block: Signed[DataApplicationBlock])(
    implicit encoder: Encoder[DataTransaction]
  ): PeerResponse[F, Boolean]
  def sendAllowSpendBlock(block: Signed[AllowSpendBlock]): PeerResponse[F, Boolean]
  def sendTokenLockBlock(block: Signed[TokenLockBlock]): PeerResponse[F, Boolean]
}

object L0BlockOutputClient {

  sealed trait L1OutputSubmissionResult {
    def accepted: Boolean
  }

  object L1OutputSubmissionResult {
    case object Accepted extends L1OutputSubmissionResult {
      val accepted: Boolean = true
    }

    /** L0 accepted the block into its awaiting-parent mempool (HTTP 202) but has NOT yet included it in a snapshot, because a parent tx
      * ordinal is not yet finalized. This is PROVISIONAL: L0 evicts awaiting-parent entries on a TTL/overflow, so a 202 must NOT be treated
      * as terminal -- the block stays buffered and is re-sent until L0 confirms inclusion with a 200. A contiguous stuck chain always gets
      * 202 (never the 4xx that triggers backfill), so dropping it here was a silent, unrecoverable loss.
      */
    case class AwaitingParent(statusCode: Int) extends L1OutputSubmissionResult {
      val accepted: Boolean = false
    }

    case class ParentOrdinalGapTooLarge(
      currentLastTxOrdinal: Long,
      parentOrdinal: Long,
      parentOrdinalGap: Long,
      maxAcceptedParentOrdinalGap: Long,
      awaitingParentTtlSeconds: Long
    ) extends L1OutputSubmissionResult {
      val accepted: Boolean = false
    }

    case class Rejected(statusCode: Int, reason: String, body: String) extends L1OutputSubmissionResult {
      val accepted: Boolean = false
    }

    private case class AwaitingParentResponse(
      status: String,
      currentLastTxOrdinal: Long,
      parentOrdinal: Long,
      parentOrdinalGap: Long,
      maxAcceptedParentOrdinalGap: Long,
      awaitingParentTtlSeconds: Long
    )

    private implicit val awaitingParentResponseDecoder: Decoder[AwaitingParentResponse] =
      Decoder.forProduct6(
        "status",
        "currentLastTxOrdinal",
        "parentOrdinal",
        "parentOrdinalGap",
        "maxAcceptedParentOrdinalGap",
        "awaitingParentTtlSeconds"
      )(AwaitingParentResponse.apply)

    def rejected(statusCode: Int, reason: String, body: String): L1OutputSubmissionResult =
      decode[AwaitingParentResponse](body).toOption.collect {
        case AwaitingParentResponse(
              "ParentOrdinalGapTooLarge",
              currentLastTxOrdinal,
              parentOrdinal,
              parentOrdinalGap,
              maxAcceptedParentOrdinalGap,
              awaitingParentTtlSeconds
            ) =>
          ParentOrdinalGapTooLarge(
            currentLastTxOrdinal,
            parentOrdinal,
            parentOrdinalGap,
            maxAcceptedParentOrdinalGap,
            awaitingParentTtlSeconds
          )
      }
        .getOrElse(Rejected(statusCode, reason, body))
  }

  def make[F[_]: Async](pathPrefix: String, client: Client[F]): L0BlockOutputClient[F] =
    new L0BlockOutputClient[F] {

      private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

      def sendL1Output(output: Signed[Block]): PeerResponse[F, Boolean] =
        sendL1OutputDetailed(output).map(_.accepted)

      def sendL1OutputDetailed(output: Signed[Block]): PeerResponse[F, L1OutputSubmissionResult] =
        PeerResponse(s"$pathPrefix/l1-output", POST)(client) { (req, c) =>
          // Instrumentation (Part 0): the block-submission path is NOT behind the snapshot 503
          // concurrency limiter, so when `Sending block to L0 failed.` fires it is a non-2xx
          // RESPONSE from the /dag/l1-output route -- expected to be a 4xx (e.g. the route's
          // ParentOrdinalGapTooLarge guard), whose body carries parentOrdinal/gap diagnostics.
          // Previously `c.successful(...)` collapsed this to a bare Boolean and discarded it.
          // Surface the actual status + body so the real failure mode is diagnosable.
          c.run(req.withEntity(output)).use { resp =>
            if (resp.status.isSuccess)
              // 202 Accepted == awaiting-parent (provisional, keep re-sending); 200 Ok == included (terminal).
              if (resp.status.code == 202)
                logger
                  .debug("[L1-OUTPUT] L0 holding block awaiting parent (202); retained for re-send until included")
                  .as(L1OutputSubmissionResult.AwaitingParent(resp.status.code): L1OutputSubmissionResult)
              else
                Async[F].pure(L1OutputSubmissionResult.Accepted: L1OutputSubmissionResult)
            else
              resp.bodyText.compile.string.flatMap { body =>
                val result = L1OutputSubmissionResult.rejected(resp.status.code, resp.status.reason, body)

                logger
                  .warn(
                    s"[L1-OUTPUT] L0 rejected block: status=${resp.status.code} ${resp.status.reason} " +
                      s"body=${body.take(800)}"
                  )
                  .as(result)
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
