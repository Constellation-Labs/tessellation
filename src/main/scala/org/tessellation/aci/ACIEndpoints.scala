package org.tessellation.aci

import cats.effect._
import cats.implicits._
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.tessellation.aci.endpoint.{
  AddACITypeEndpoint,
  ValidateACIObjectEndpoint
}

class ACIEndpoints[F[_]: Concurrent: ContextShift](
  validateACIObjectEndpoint: ValidateACIObjectEndpoint[F],
  addACITypeEndpoint: AddACITypeEndpoint[F]
) extends Http4sDsl[F] {

  protected val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  def routes(): HttpRoutes[F] =
    validateACIObjectEndpoint.routes <+> addACITypeEndpoint.routes

}
