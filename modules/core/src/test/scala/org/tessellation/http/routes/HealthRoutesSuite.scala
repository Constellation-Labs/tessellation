package org.tessellation.http.routes

import cats.effect._
import cats.syntax.applicative._

import org.tessellation.domain.healthcheck.HealthCheck
import org.tessellation.schema.healthcheck.Status.Okay
import org.tessellation.schema.healthcheck.{AppStatus, FooStatus}

import org.http4s.Method._
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import suite.HttpSuite

object HealthRoutesSuite extends HttpSuite {
  test("GET healthecheck succeeds") {
    val req = GET(uri"/healthcheck")
    val routes = HealthRoutes[IO](new TestHealthCheck()).routes
    expectHttpStatus(routes, req)(Status.Ok)
  }
}

protected class TestHealthCheck() extends HealthCheck[IO] {
  def status: IO[AppStatus] = AppStatus(FooStatus(Okay)).pure[IO]
}
