package org.tesselation.infrastructure.cluster

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tesselation.domain.cluster.SessionStorage
import org.tesselation.effects.GenUUID
import org.tesselation.schema.cluster.{SessionAlreadyExists, SessionToken}
import org.tesselation.schema.uid

object SessionStorage {

  def make[F[_]: Async: GenUUID]: F[SessionStorage[F]] =
    Ref.of[F, Option[SessionToken]](none).map(make(_))

  def make[F[_]: Async: GenUUID](sessionToken: Ref[F, Option[SessionToken]]): SessionStorage[F] =
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
        uid.make[F, SessionToken]
    }
}
