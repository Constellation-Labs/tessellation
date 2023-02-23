package org.tessellation.http.routes

import cats.effect.IO

import org.http4s.Method._
import org.http4s.Uri.Path
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import suite.HttpSuite
import weaver.SimpleMutableIOSuite

object DagRoutesSuite extends SimpleMutableIOSuite with HttpSuite {
  test("GET redirects to /currency") { _ =>
    val req = GET(uri"/dag/foo")
    val routes = DagRoutes[IO]().publicRoutes

    for {
      status <- expectHttpStatus(routes, req)(Status.PermanentRedirect)
      headers <- expectLocationHeader(routes, req)(Path.unsafeFromString("/currency/foo"))
    } yield status && headers
  }

  test("HEAD redirects to /currency") { _ =>
    val req = HEAD(uri"/dag/foo")
    val routes = DagRoutes[IO]().publicRoutes

    for {
      status <- expectHttpStatus(routes, req)(Status.PermanentRedirect)
      headers <- expectLocationHeader(routes, req)(Path.unsafeFromString("/currency/foo"))
    } yield status && headers
  }

  test("POST redirects to /currency") { _ =>
    val req = POST(uri"/dag/foo")
    val routes = DagRoutes[IO]().publicRoutes

    for {
      status <- expectHttpStatus(routes, req)(Status.PermanentRedirect)
      headers <- expectLocationHeader(routes, req)(Path.unsafeFromString("/currency/foo"))
    } yield status && headers
  }
}
