package org.tessellation.rosetta.http.routes

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Async
import cats.syntax.either._

import org.tessellation.rosetta.domain.api.construction._
import org.tessellation.rosetta.domain.construction.ConstructionService
import org.tessellation.rosetta.domain.error.{ConstructionError, UnsupportedOperation}
import org.tessellation.rosetta.ext.http4s.refined._
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.security.hex.Hex

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final class ConstructionRoutes[F[_]: Async](
  constructionService: ConstructionService[F],
  appEnvironment: AppEnvironment
) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/construction"

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "derive" =>
      req.decodeRosettaWithNetworkValidation[ConstructionDerive.Request](appEnvironment, _.networkIdentifier) { deriveReq =>
        constructionService
          .derive(deriveReq.publicKey)
          .bimap(_.toRosettaError, ConstructionDerive.Response(_))
          .asRosettaResponse
      }

    case req @ POST -> Root / "hash" =>
      req.decodeRosettaWithNetworkValidation[ConstructionHash.Request](appEnvironment, _.networkIdentifier) { hashReq =>
        constructionService
          .getTransactionIdentifier(hashReq.signedTransaction)
          .bimap(_.toRosettaError, ConstructionHash.Response(_))
          .asRosettaResponse
          .handleUnknownError
      }

    case req @ POST -> Root / "preprocess" =>
      req.decodeRosettaWithNetworkValidation[ConstructionPreprocess.Request](appEnvironment, _.networkIdentifier) { preprocessReq =>
        val accountIdentifiers = constructionService
          .getAccountIdentifiers(preprocessReq.operations)

        Ok(ConstructionPreprocess.Response(accountIdentifiers)).handleUnknownError
      }

    case req @ POST -> Root / "parse" =>
      req
        .decodeRosettaWithNetworkValidation[ConstructionParse.Request](appEnvironment, _.networkIdentifier) { parseReq =>
          constructionService
            .parseTransaction(parseReq.transaction, parseReq.signed)
            .bimap(
              _.toRosettaError,
              ConstructionParse.Response.fromParseResult
            )
            .asRosettaResponse
        }
        .handleUnknownError

    case req @ POST -> Root / "combine" =>
      req
        .decodeRosettaWithNetworkValidation[ConstructionCombine.Request](appEnvironment, _.networkIdentifier) { combineReq =>
          val combinedHex = combineReq.signatures match {
            case NonEmptyList(head, Nil) =>
              constructionService
                .combineTransaction(combineReq.unsignedTransaction, head)
            case _ => EitherT.fromEither(UnsupportedOperation.asInstanceOf[ConstructionError].asLeft[Hex])
          }

          combinedHex.bimap(_.toRosettaError, ConstructionCombine.Response(_)).asRosettaResponse.handleUnknownError
        }

  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> public
  )
}
