package io.constellationnetwork.currency.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.getFeeAddresses
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyMessageValidator
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencyMessageEvent, CurrencySnapshotEvent}
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageType}
import io.constellationnetwork.schema.http.{ErrorCause, ErrorResponse}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless.HNil
import shapeless.syntax.singleton._

class CurrencyMessageRoutes[F[_]: Async: Hasher](
  mkCell: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
  validator: CurrencyMessageValidator[F],
  snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
  identifierStorage: IdentifierStorage[F],
  lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  private val logger = Slf4jLogger.getLoggerFromName("CurrencyMessageRoutes")

  protected val prefixPath: InternalUrlPrefix = "/currency"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "message" =>
      for {
        msg <- req.as[Signed[CurrencyMessage]]
        maybeLastMsgs <- snapshotStorage.head.map {
          _.map(_._2).map(_.lastMessages.getOrElse(SortedMap.empty[MessageType, Signed[CurrencyMessage]]))
        }
        metagraphId <- identifierStorage.get
        allFeesAddresses <- lastGlobalSnapshotStorage.getCombined.map {
          case Some((_, info)) =>
            getFeeAddresses(info)
          case None => SortedMap.empty[Address, Set[Address]]
        }

        maybeResult <- maybeLastMsgs.traverse(validator.validate(msg, _, metagraphId, allFeesAddresses))
        response <- maybeResult match {
          case Some(Invalid(errors)) =>
            logger
              .warn(s"Message is invalid, reason: ${errors.show}")
              .as(ErrorResponse(errors.map(e => ErrorCause(e.show)).toNonEmptyList))
              .flatMap(BadRequest(_))

          case Some(Valid(message)) =>
            mkCell(CurrencyMessageEvent(message)).run() >> NoContent()

          case None =>
            ServiceUnavailable(("message" ->> "Node not yet ready to accept messages.") :: HNil)
        }
      } yield response
  }
}
