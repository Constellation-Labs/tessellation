package org.tessellation.sdk.infrastructure.cluster.storage

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.schema.cluster.{SessionAlreadyExists, SessionToken}
import org.tessellation.schema.generation.Generation
import org.tessellation.sdk.domain.cluster.storage.SessionStorage

object SessionStorage {

  def make[F[_]: Async]: F[SessionStorage[F]] =
    Ref.of[F, Option[SessionToken]](none).map(make(_))

  def make[F[_]: Async](sessionToken: Ref[F, Option[SessionToken]]): SessionStorage[F] =
    new SessionStorage[F] {

      def createToken: F[SessionToken] =
        generateToken.flatMap { generatedToken =>
          sessionToken.modify {
            case None        => (generatedToken.some, generatedToken.pure[F])
            case Some(token) => (token.some, SessionAlreadyExists.raiseError[F, SessionToken])
          }.flatMap(identity)
        }

      def getToken: F[Option[SessionToken]] = sessionToken.get

      def clearToken: F[Unit] = sessionToken.set(none)

      private def generateToken: F[SessionToken] =
        Generation.make[F].map(SessionToken(_))
    }
}
