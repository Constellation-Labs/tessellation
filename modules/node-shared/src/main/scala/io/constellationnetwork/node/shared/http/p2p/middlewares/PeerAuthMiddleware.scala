package io.constellationnetwork.node.shared.http.p2p.middlewares

import java.security.{PrivateKey, PublicKey}

import cats.data.{Kleisli, OptionT}
import cats.effect.{Async, Ref, Resource}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import io.constellationnetwork.httpsigner.http4s._
import io.constellationnetwork.httpsigner.signature.generic.{GenericGenerator, GenericVerifier}
import io.constellationnetwork.httpsigner.signature.{Generator, Verifier}
import io.constellationnetwork.httpsigner.{HttpCryptoConfig, SignatureValid}
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.domain.cluster.storage.SessionStorage
import io.constellationnetwork.node.shared.domain.collateral.Collateral
import io.constellationnetwork.node.shared.http.p2p.headers.{`X-Id`, `X-Session-Token`}
import io.constellationnetwork.schema.cluster.TokenValid
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signing

import fs2.{Chunk, Stream}
import org.http4s.Status.Unauthorized
import org.http4s._
import org.http4s.client.Client
import org.typelevel.ci._

object PeerAuthMiddleware {

  private def unauthorized[F[_]] = Response[F](status = Unauthorized)

  private def getOwnTokenHeader[F[_]: Async](sessionStorage: SessionStorage[F]): F[Option[`X-Session-Token`]] =
    sessionStorage.getToken.map(_.map(t => `X-Session-Token`(t)))

  private def getPeerId[F[_]](req: Request[F]): Option[PeerId] =
    req.headers.get[`X-Id`].map(_.id)

  private def getPeerId[F[_]](res: Response[F]): Option[PeerId] =
    res.headers.get[`X-Id`].map(_.id)

  /** Heavyweight snapshot stream routes whose response bodies must NOT be buffered for peer-auth body signing/verification.
    *
    * Body-level peer-auth verification (md5 over the entire response body) is redundant for the combined-snapshot stream and per-ordinal
    * checkpoint endpoints: the embedded `Signed[S]` snapshot carries its own deterministic cryptographic signature, which is what the
    * caller actually applies and trusts. The peer-auth wrapper only confirms transport-layer authenticity of the peer; that authenticity is
    * already established by the `X-Id` + `X-Session-Token` header pair (verified statelessly on the client by
    * `responseTokenVerifierMiddleware`).
    *
    * The buffering itself is what we are eliminating: server-side, `Http4sResponseSigner` accumulates the entire body into a
    * `ByteArrayOutputStream` to compute the signature md5; client-side, `responseVerifierMiddleware` keeps every chunk in a `Ref[F].of`
    * `Vector[Chunk[Byte]]` so the verifier and the downstream consumer can both re-read it. For a 100 MB combined checkpoint that is ~100
    * MB retained per concurrent download. Skipping for these routes avoids both copies.
    *
    * The predicate matches by URI suffix because the route prefix differs per layer (`global-snapshots` for GL0, `snapshots` for CL0).
    * Other routes (small JSON probes, cluster/peer routes, etc.) retain the existing per-message peer-auth signing/verification.
    */
  def isLargeStreamRoute[F[_]](req: Request[F]): Boolean = {
    val path = req.uri.path.segments.map(_.encoded).toList
    path.takeRight(3) match {
      case "latest" :: "combined" :: "stream" :: Nil                 => true
      case "combined" :: "checkpoint" :: ord :: Nil if ord != "info" => true
      case _                                                         => false
    }
  }

  def responseSignerMiddleware[F[_]: Async: SecurityProvider](
    privateKey: PrivateKey,
    sessionStorage: SessionStorage[F],
    selfId: PeerId
  )(http: HttpRoutes[F]): HttpRoutes[F] = {
    val signer = new Http4sResponseSigner[F](getGenerator(privateKey), new TessellationHttpCryptoConfig {})

    Kleisli { req: Request[F] =>
      for {
        res <- http(req)
        headerToken <- getOwnTokenHeader(sessionStorage).attemptT.toOption
        newHeaders = headerToken.fold(res.headers)(h => res.headers.put(h)).put(`X-Id`(selfId))
        resWithHeader = res.copy(headers = newHeaders)
        // Heavyweight stream routes (combined-snapshot stream + per-ordinal checkpoint) skip
        // body-level peer-auth signing: `Http4sResponseSigner` buffers the entire body to compute
        // the signature md5, which inflates heap by the full response size per concurrent serve.
        // The embedded `Signed[S]` payload's own signature is what the caller applies; transport
        // authenticity is still proven by the `X-Id` + `X-Session-Token` header pair attached above.
        signedResponse <-
          if (isLargeStreamRoute(req)) OptionT.pure[F](resWithHeader)
          else signer.sign(resWithHeader).attemptT.toOption
      } yield signedResponse
    }
  }

  def responseTokenVerifierMiddleware[F[_]: Async](
    client: Client[F],
    session: Session[F]
  ): Client[F] =
    Client { (req: Request[F]) =>
      client.run(req).flatMap { response =>
        Resource.liftK[F] {
          val token = response.headers
            .get[`X-Session-Token`]
            .map(_.token)
          getPeerId(response).map { peerId =>
            session.verifyToken(peerId, token).flatMap {
              case TokenValid => response.pure[F]
              case _          => unauthorized[F].pure[F]
            }
          }.getOrElse(unauthorized[F].pure[F])
        }
      }
    }

  def responseVerifierMiddleware[F[_]: Async: SecurityProvider](peerId: PeerId)(client: Client[F]): Client[F] =
    Client { (req: Request[F]) =>
      val verifier = peerId.value.toPublicKey.map { publicKey =>
        new Http4sResponseVerifier[F](getVerifier(publicKey), new TessellationHttpCryptoConfig {})
      }

      // Heavyweight stream routes bypass the body-buffering verifier. The verifier observes every
      // chunk into a `Ref[F].of(Vector[Chunk[Byte]])` so a single-shot md5 signature can be computed
      // over the entire body; for a 100 MB combined checkpoint that retains ~100 MB per concurrent
      // download in addition to whatever the caller decoder allocates. The server-side
      // `responseSignerMiddleware` symmetrically skips signing this same set of routes, so there is
      // no signature to check; the embedded `Signed[S]` snapshot's own signature is what the caller
      // applies. Transport authenticity remains established by the `X-Id` header on the response.
      if (isLargeStreamRoute(req)) client.run(req)
      else
        client.run(req).flatMap { response =>
          Resource.suspend {
            Ref[F].of(Vector.empty[Chunk[Byte]]).map { vec =>
              Resource.liftK {
                val copiedBody = Stream
                  .eval(vec.get)
                  .flatMap(v => Stream.emits(v).covary[F])
                  .flatMap(c => Stream.chunk(c).covary[F])

                response
                  .copy(body = response.body.observe(_.chunks.flatMap(s => Stream.exec(vec.update(_ :+ s)))))
                  .pure[F]
                  .flatMap { res =>
                    verifier.flatMap(_.verify(res))
                  }
                  .flatMap {
                    case SignatureValid => response.withBodyStream(copiedBody).pure[F]
                    case _              => unauthorized[F].pure[F]
                  }

              }
            }
          }
        }
    }

  def requestSignerMiddleware[F[_]: Async: SecurityProvider](
    client: Client[F],
    privateKey: PrivateKey,
    sessionStorage: SessionStorage[F],
    selfId: PeerId
  ): Client[F] = {
    val signer = new Http4sRequestSigner(getGenerator(privateKey), new TessellationHttpCryptoConfig {})

    Client { req: Request[F] =>
      Resource.suspend {
        Ref[F].of(Vector.empty[Chunk[Byte]]).map { vec =>
          Resource.liftK[F] {

            val copiedBody = Stream
              .eval(vec.get)
              .flatMap(v => Stream.emits(v).covary[F])
              .flatMap(c => Stream.chunk(c).covary[F])

            for {
              tokenHeader <- getOwnTokenHeader(sessionStorage)
              newHeaders = tokenHeader.fold(req.headers)(h => req.headers.put(h)).put(`X-Id`(selfId))
              newReq = req
                .withBodyStream(req.body.observe(_.chunks.flatMap(s => Stream.exec(vec.update(_ :+ s)))))
                .withHeaders(newHeaders)
              signedRequest <- signer.sign(newReq).map(r => req.withBodyStream(copiedBody).withHeaders(r.headers))
            } yield signedRequest

          }
        }
      } >>= client.run
    }
  }

  def requestCollateralVerifierMiddleware[F[_]: Async](collateral: Collateral[F])(http: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli { req: Request[F] =>
      getPeerId(req).map { peerId =>
        collateral.hasCollateral(peerId).attemptT.toOption.ifM(http(req), OptionT.pure[F](unauthorized[F]))
      }.getOrElse(OptionT.pure[F](unauthorized[F]))
    }

  def requestTokenVerifierMiddleware[F[_]: Async](session: Session[F])(http: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli { req: Request[F] =>
      val token = req.headers
        .get[`X-Session-Token`]
        .map(_.token)

      getPeerId(req).map { peerId =>
        session.verifyToken(peerId, token).attemptT.toOption.flatMap {
          case TokenValid => http(req)
          case _          => OptionT.pure[F](unauthorized[F])
        }
      }.getOrElse(OptionT.pure[F](unauthorized[F]))
    }

  def requestVerifierMiddleware[F[_]: Async: SecurityProvider](http: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli { req: Request[F] =>
      val verify: OptionT[F, Response[F]] = for {

        tuple <- Ref[F]
          .of(Vector.empty[Chunk[Byte]])
          .map { vec =>
            val newBody =
              Stream.eval(vec.get).flatMap(v => Stream.emits(v).covary[F]).flatMap(c => Stream.chunk(c).covary[F])
            val newReq = req.withBodyStream(req.body.observe(_.chunks.flatMap(s => Stream.exec(vec.update(_ :+ s)))))
            (newBody, newReq)
          }
          .attemptT
          .toOption

        id <- getPeerId(req).toOptionT[F]
        publicKey <- id.value.toPublicKey[F].attemptT.toOption

        crypto = getVerifier(publicKey)
        verifier = new Http4sRequestVerifier[F](crypto, new TessellationHttpCryptoConfig {})

        verifierResult <- verifier.verify(tuple._2).attemptT.toOption

        response <- verifierResult match {
          case SignatureValid => http(req.withBodyStream(tuple._1))
          case _              => OptionT.pure[F](unauthorized[F])
        }
      } yield response

      verify.orElse(OptionT.pure[F](unauthorized[F]))
    }

  private def getVerifier[F[_]: SecurityProvider](publicKey: PublicKey): Verifier =
    GenericVerifier(Signing.defaultSignFunc, SecurityProvider[F].provider, publicKey)

  private def getGenerator[F[_]: SecurityProvider](privateKey: PrivateKey): Generator =
    GenericGenerator(Signing.defaultSignFunc, SecurityProvider[F].provider, privateKey)

  trait TessellationHttpCryptoConfig extends HttpCryptoConfig {
    override val protectedHeaders = Set(
      ci"Content-Type",
      ci"Cookie",
      ci"Referer",
      `X-Session-Token`.headerInstance.name
    )
  }

}
