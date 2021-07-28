package org.tessellation.http

import cats.effect.{ContextShift, IO, Timer}
import io.circe.Json
import org.http4s.client._
import org.http4s.client.dsl.io._
import io.circe.syntax._
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.dsl.io._
import org.http4s.{Method, Request, Uri}
import org.tessellation.consensus.L1ConsensusStep.{BroadcastProposalPayload, BroadcastProposalResponse, RoundId}
import org.tessellation.consensus.L1Transaction
import org.tessellation.node.{Node, Peer}

import scala.concurrent.ExecutionContext.global

class HttpClient(node: Node, client: Client[IO]) {
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)

  def join(to: Peer): IO[Unit] =
    client.expect[Unit](POST(Uri.unsafeFromString(s"http://${to.host}:${to.port}/join")))
  //    for {

  //      joined <- client.successful(
  //        new Request[IO](method = Method.POST, Uri.unsafeFromString(s"http://${to.host}:${to.port}/join"))
  //      )
  //      _ <- if (joined) IO.unit else IO.raiseError(new Throwable(s"Couldn't join to peer $to"))
  //    } yield ()

  def sendConsensusProposal(
    roundId: RoundId,
    consensusOwnerId: String,
    proposal: Set[L1Transaction],
    facilitators: Set[Peer]
  )(
    to: Peer
  ): IO[BroadcastProposalResponse] = {
    implicit val encoder = jsonEncoderOf[IO, Json]
    implicit val decoder = jsonOf[IO, BroadcastProposalResponse]
    for {
      payload <- IO.pure(BroadcastProposalPayload(node.id, consensusOwnerId, roundId, proposal, facilitators))
      res <- client.expect[BroadcastProposalResponse](
        POST(payload.asJson, Uri.unsafeFromString(s"http://${to.host}:${to.port}/proposal"))
      )
    } yield res
  }
}

object HttpClient {
  def apply(node: Node, client: Client[IO]): HttpClient = new HttpClient(node, client)
}
