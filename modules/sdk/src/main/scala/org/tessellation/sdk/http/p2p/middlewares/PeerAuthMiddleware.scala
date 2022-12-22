package org.tessellation.sdk.http.p2p.middleware

import java.security.{PrivateKey, PublicKey}

import cats.data.{Kleisli, OptionT}
import cats.effect.{Async, Ref, Resource}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.schema.cluster.TokenValid
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.signature.Signing
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.cluster.storage.SessionStorage
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.http.p2p.headers.{`X-Id`, `X-Session-Token`}

import fs2.{Chunk, Stream}
import org.http4s.Status.Unauthorized
import org.http4s._
import org.http4s.client.Client
import org.typelevel.ci._
import pl.abankowski.httpsigner.http4s._
import pl.abankowski.httpsigner.signature.generic.{GenericGenerator, GenericVerifier}
import pl.abankowski.httpsigner.signature.{Generator, Verifier}
import pl.abankowski.httpsigner.{HttpCryptoConfig, SignatureValid}

object PeerAuthMiddleware {

  private def unauthorized[F[_]] = Response[F](status = Unauthorized)

  private def getOwnTokenHeader[F[_]: Async](sessionStorage: SessionStorage[F]): F[Option[`X-Session-Token`]] =
    sessionStorage.getToken.map(_.map(t => `X-Session-Token`(t)))

  private def getPeerId[F[_]](req: Request[F]): Option[PeerId] =
    req.headers.get[`X-Id`].map(_.id)

  private def getPeerId[F[_]](res: Response[F]): Option[PeerId] =
    res.headers.get[`X-Id`].map(_.id)

  def responseSignerMiddleware[F[_]: Async: SecurityProvider](
    privateKey: PrivateKey,
    sessionStorage: SessionStorage[F],
    selfId: PeerId
  )(http: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli { req: Request[F] =>
      for {
        res <- http(req)
        headerToken <- getOwnTokenHeader(sessionStorage).attemptT.toOption
        newHeaders = headerToken.fold(res.headers)(h => res.headers.put(h)).put(`X-Id`(selfId))
        resWithHeader = res.copy(headers = newHeaders)
        signedResponse <- new Http4sResponseSigner[F](getGenerator(privateKey), new TessellationHttpCryptoConfig {})
          .sign(resWithHeader)
          .attemptT
          .toOption
      } yield signedResponse
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
  ): Client[F] = Client { req: Request[F] =>
    val signer = new Http4sRequestSigner(getGenerator(privateKey), new TessellationHttpCryptoConfig {})

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
