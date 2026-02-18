package io.constellationnetwork.tools

import java.math.BigInteger
import java.security.{KeyFactory, KeyPair}

import cats.effect._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{key => secKey, _}

import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import fs2.Stream
import io.circe.Json
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.{ECPrivateKeySpec, ECPublicKeySpec}
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import pureconfig._
import pureconfig.generic.ProductHint
import pureconfig.generic.auto._

object TransactionSender {

  case class TxSenderConfig(
    privateKeyHex: String,
    l1BaseUrl: String,
    recipients: List[String],
    burstCount: Int,
    burstTps: Int,
    steadyCount: Int,
    steadyIntervalSeconds: Int,
    cycles: Int,
    amountDatum: Long,
    feeDatum: Long
  )

  implicit val txSenderConfigHint: ProductHint[TxSenderConfig] =
    ProductHint[TxSenderConfig](ConfigFieldMapping(CamelCase, CamelCase))

  def run(configPath: String)(implicit hasher: Hasher[IO], sp: SecurityProvider[IO]): IO[Unit] =
    for {
      config <- IO.delay(ConfigSource.file(configPath).loadOrThrow[TxSenderConfig]).adaptError {
        case e => new Exception(s"Failed to load config from '$configPath': ${e.getMessage}", e)
      }
      _ <- IO.println(s"Loaded config from: $configPath")
      _ <- IO.println(s"  L1 URL: ${config.l1BaseUrl}")
      _ <- IO.println(s"  Recipients: ${config.recipients.size}")
      _ <- IO.println(s"  Burst: ${config.burstCount} txs at ${config.burstTps} TPS (0=skip)")
      _ <- IO.println(s"  Steady: ${config.steadyCount} txs every ${config.steadyIntervalSeconds}s (0=skip)")
      _ <- IO.println(s"  Cycles: ${if (config.cycles <= 0) "infinite" else config.cycles.toString}")
      _ <- IO.println(s"  Amount per tx: ${config.amountDatum} datum")
      _ <- IO.println(s"  Fee per tx: ${config.feeDatum} datum")
      keyPair <- hexToKeyPair[IO](config.privateKeyHex)
      senderAddress = keyPair.getPublic.toAddress
      _ <- IO.println(s"\n  Sender address: ${senderAddress.value.value}")
      _ <- IO.println(s"  Fund this address if not already funded.\n")
      recipients <- config.recipients.traverse { addr =>
        IO.fromEither(
          refineV[DAGAddressRefined](addr)
            .bimap(e => new Exception(s"Invalid recipient address '$addr': $e"), Address(_))
        )
      }
      _ <- IO.raiseWhen(recipients.isEmpty)(new Exception("No recipient addresses configured"))
      _ <- IO.raiseWhen(config.amountDatum <= 0)(
        new Exception(s"amountDatum must be > 0, got ${config.amountDatum}")
      )
      _ <- IO.raiseWhen(config.feeDatum < 0)(
        new Exception(s"feeDatum must be >= 0, got ${config.feeDatum}")
      )
      _ <- IO.raiseWhen(config.burstCount <= 0 && config.steadyCount <= 0)(
        new Exception("Both burstCount and steadyCount are <= 0 — nothing to send. Check your config.")
      )
      _ <- EmberClientBuilder
        .default[IO]
        .withTimeout(30.seconds)
        .withIdleConnectionTime(60.seconds)
        .build
        .use { client =>
          for {
            _ <- IO.println("Fetching last transaction reference...")
            lastRef <- getLastReference(client, config.l1BaseUrl, senderAddress).adaptError { e =>
              new Exception(s"Failed to fetch last reference for ${senderAddress.value.value}: ${e.getMessage}", e)
            }
            _ <- IO.println(s"  Last ref ordinal: ${lastRef.ordinal.value}")
            // Warn about silent clamping of interval values
            _ <- IO.whenA(config.burstTps <= 0 && config.burstCount > 0)(
              IO.println("  Warning: burstTps=0 clamped to 1 TPS (set burstCount=0 to skip burst phase)")
            )
            _ <- IO.whenA(config.steadyIntervalSeconds <= 0 && config.steadyCount > 0)(
              IO.println("  Warning: steadyIntervalSeconds=0 clamped to 1s (set steadyCount=0 to skip steady phase)")
            )
            _ <- IO.println(s"\nStarting transaction stream...")
            _ <- IO.println("Press Ctrl+C to stop.\n")
            _ <- runStream(client, config, keyPair, senderAddress, recipients, lastRef)
          } yield ()
        }
    } yield ()

  private def runStream(
    client: Client[IO],
    config: TxSenderConfig,
    keyPair: KeyPair,
    source: Address,
    recipients: List[Address],
    initialRef: TransactionReference
  )(implicit hasher: Hasher[IO], sp: SecurityProvider[IO]): IO[Unit] = {
    val burstInterval = (1000.0 / config.burstTps.max(1)).millis
    val steadyInterval = config.steadyIntervalSeconds.max(1).seconds

    Clock[IO].monotonic.flatMap { startTime =>
      Ref.of[IO, TransactionReference](initialRef).flatMap { refRef =>
        Ref.of[IO, Long](0L).flatMap { counterRef =>
          Ref.of[IO, Long](0L).flatMap { errorRef =>
            Ref.of[IO, Int](0).flatMap { recipientIdxRef =>
              val progressPrinter = Stream
                .awakeEvery[IO](5.seconds)
                .evalMap { _ =>
                  (counterRef.get, errorRef.get, Clock[IO].monotonic).tupled.flatMap {
                    case (sent, errors, now) =>
                      val elapsed = (now - startTime).toSeconds.max(1)
                      val actualTps = sent.toDouble / elapsed
                      IO.println(
                        s"  [progress] sent=$sent errors=$errors elapsed=${elapsed}s actual_tps=${f"$actualTps%.1f"}"
                      )
                  }
                }

              def nextRecipient: IO[Address] =
                recipientIdxRef.getAndUpdate(_ + 1).map(i => recipients(i % recipients.size))

              def sendOne: IO[Unit] =
                for {
                  destination <- nextRecipient
                  parentRef <- refRef.get
                  salt <- TransactionSalt.generate[IO]
                  tx = Transaction(
                    source = source,
                    destination = destination,
                    amount = TransactionAmount(PosLong.unsafeFrom(config.amountDatum)),
                    fee = TransactionFee(NonNegLong.unsafeFrom(config.feeDatum)),
                    parent = parentRef,
                    salt = salt
                  )
                  signedTx <- Signed.forAsyncHasher[IO, Transaction](tx, keyPair)
                  txHash <- signedTx.value.hash
                  newRef = TransactionReference(parentRef.ordinal.next, txHash)
                  result <- submitTransaction(client, config.l1BaseUrl, signedTx).attempt
                  continue <- result match {
                    case Right(Right(hash)) =>
                      refRef.set(newRef) *>
                        counterRef.update(_ + 1) *>
                        IO.println(
                          s"  [tx] ord=${newRef.ordinal.value} -> ${destination.value.value.take(12)}... hash=${hash.take(16)}..."
                        ).as(true)
                    case Right(Left(error)) =>
                      errorRef.update(_ + 1) *>
                        IO.println(s"  [REJECTED] ord=${newRef.ordinal.value}: $error").as(false)
                    case Left(e) =>
                      errorRef.update(_ + 1) *>
                        IO.println(s"  [ERROR] ord=${newRef.ordinal.value}: ${e.getMessage}").as(false)
                  }
                  _ <- IO.raiseWhen(!continue)(
                    new Exception("Transaction rejected - stopping to avoid broken chain. Fix the issue and restart.")
                  )
                } yield ()

              val burstPhase: Stream[IO, Unit] =
                if (config.burstCount <= 0) Stream.empty
                else
                  Stream.eval(IO.println(s"  [burst] Sending ${config.burstCount} txs at ~${config.burstTps} TPS")) ++
                    Stream.repeatEval(sendOne).take(config.burstCount.toLong).metered(burstInterval)

              val steadyPhase: Stream[IO, Unit] =
                if (config.steadyCount <= 0) Stream.empty
                else
                  Stream.eval(IO.println(s"  [steady] Sending ${config.steadyCount} txs, 1 every ${config.steadyIntervalSeconds}s")) ++
                    Stream.repeatEval(sendOne).take(config.steadyCount.toLong).metered(steadyInterval)

              val oneCycle: Stream[IO, Unit] = burstPhase ++ steadyPhase

              val allCycles: Stream[IO, Unit] =
                if (config.cycles <= 0) oneCycle.repeat
                else
                  Stream.range(0, config.cycles).flatMap { i =>
                    Stream.eval(IO.println(s"\n  === Cycle ${i + 1}/${config.cycles} ===")) ++ oneCycle
                  } ++ Stream.eval(IO.println("\n  All cycles complete."))

              val txStream = allCycles
                .handleErrorWith(e => Stream.eval(IO.println(s"\n  Stopped: ${e.getMessage}")))

              txStream
                .mergeHaltL(progressPrinter)
                .compile
                .drain
            }
          }
        }
      }
    }
  }

  private def getLastReference(
    client: Client[IO],
    l1BaseUrl: String,
    address: Address
  ): IO[TransactionReference] = {
    val uri = Uri.unsafeFromString(s"$l1BaseUrl/transactions/last-reference/${address.value.value}")
    client.expect[TransactionReference](uri)
  }

  private def submitTransaction(
    client: Client[IO],
    l1BaseUrl: String,
    signedTx: Signed[Transaction]
  ): IO[Either[String, String]] = {
    val uri = Uri.unsafeFromString(s"$l1BaseUrl/transactions")
    val request = Request[IO](Method.POST, uri).withEntity(signedTx)

    client.run(request).use { response =>
      response.status match {
        case Status.Ok =>
          response
            .as[Json]
            .map(json => Right(json.hcursor.downField("hash").as[String].getOrElse("unknown")))
        case _ =>
          response.as[String].map(body => Left(s"${response.status.code}: $body"))
      }
    }
  }

  private def hexToKeyPair[F[_]: Async: SecurityProvider](skHex: String): F[KeyPair] = Async[F].delay {
    val cleanHex = skHex.trim.toLowerCase
    require(cleanHex.nonEmpty, "Private key cannot be empty")
    require(cleanHex.matches("^[0-9a-f]+$"), s"Invalid hex: contains non-hex characters")
    require(cleanHex.length == 64, s"Invalid key length: expected 64 hex chars, got ${cleanHex.length}")

    val curveParams = ECNamedCurveTable.getParameterSpec(secKey.secp256k)
    val privateKeyInt = new BigInteger(cleanHex, 16)
    require(
      privateKeyInt.compareTo(BigInteger.ZERO) > 0 && privateKeyInt.compareTo(curveParams.getN) < 0,
      "Private key is out of valid range for secp256k1 curve"
    )

    val kf = KeyFactory.getInstance(secKey.ECDSA, SecurityProvider[F].provider)

    val privateKeySpec = new ECPrivateKeySpec(privateKeyInt, curveParams)
    val privateKey = kf.generatePrivate(privateKeySpec)

    val pubPoint = curveParams.getG.multiply(privateKeyInt).normalize()
    val publicKeySpec = new ECPublicKeySpec(pubPoint, curveParams)
    val publicKey = kf.generatePublic(publicKeySpec)

    new KeyPair(publicKey, privateKey)
  }
}
