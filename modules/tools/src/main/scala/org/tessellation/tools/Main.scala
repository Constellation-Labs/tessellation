package org.tessellation.tools

import java.nio.file.Path
import java.security.KeyPair

import cats.Parallel
import cats.effect.std.Console
import cats.effect.{Async, ExitCode, IO}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.concurrent.duration._

import org.tessellation.BuildInfo
import org.tessellation.ext.crypto._
import org.tessellation.infrastructure.genesis.types.GenesisCSVAccount
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction._
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed
import org.tessellation.security.{SecureRandom, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.tools.cli.method._

import com.monovore.decline._
import com.monovore.decline.effect._
import eu.timepit.refined.types.numeric._
import fs2.data.csv._
import fs2.data.csv.generic.semiauto.deriveRowEncoder
import fs2.io.file.Files
import fs2.{Stream, text}
import org.http4s.Uri.{Authority, RegName, Scheme}
import org.http4s._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

object Main
    extends CommandIOApp(
      name = "",
      header = "Constellation Tools",
      version = BuildInfo.version
    ) {

  override def main: Opts[IO[ExitCode]] =
    cli.method.opts.map { method =>
      SecurityProvider.forAsync[IO].use { implicit sp =>
        KryoSerializer.forAsync[IO](sharedKryoRegistrar).use { implicit kryo =>
          EmberClientBuilder.default[IO].build.use { client =>
            method match {
              case TestTransactions(genesisPath, wallets, txs, ip, port) =>
                testTxs[IO](genesisPath, wallets, txs, client, ip, port)
                  .as(ExitCode.Success)
            }
          }
        }
      }
    }

  def testTxs[F[_]: Async: Parallel: KryoSerializer: SecurityProvider: Console](
    genesisPath: Path,
    wallets: Int,
    txs: Int,
    client: Client[F],
    ip: String,
    port: Int
  ): F[Unit] =
    console.green(s"Configuration: ") >>
      console.yellow(s"Wallets: ${wallets.show}") >>
      console.yellow(s"Genesis path: ${genesisPath.toString.show}") >>
      generateKeys[F](wallets).flatMap { keys =>
        createGenesis(genesisPath, keys) >>
          Async[F]
            .start(createTransactions(txs, keys))
            .flatMap { txsFiber =>
              console.cyan(
                "Genesis created. Creating transactions in progress. Please start the network and continue... [ENTER]"
              ) >>
                Console[F].readLine >>
                txsFiber.joinWithNever
            }
            .flatMap(sendTransactions(client, ip, port))
            .flatMap(_ => Async[F].sleep(10.seconds))
            .flatMap(_ => checkLastReferences(client, ip, port)(keys))
      }

  def generateKeys[F[_]: Async: SecurityProvider](wallets: Int): F[List[KeyPair]] =
    (1 to wallets).toList.traverse { _ =>
      KeyPairGenerator.makeKeyPair[F]
    }

  def createGenesis[F[_]: Async](genesisPath: Path, keys: List[KeyPair]): F[Unit] = {
    implicit val encoder: RowEncoder[GenesisCSVAccount] = deriveRowEncoder

    Stream
      .emits[F, KeyPair](keys.toSeq)
      .map(_.getPublic.toAddress)
      .map(_.value.toString)
      .map(GenesisCSVAccount(_, 100000000L))
      .through(encodeWithoutHeaders[GenesisCSVAccount]())
      .through(text.utf8.encode)
      .through(Files[F].writeAll(fs2.io.file.Path.fromNioPath(genesisPath)))
      .compile
      .drain
  }

  def createTransactions[F[_]: Async: KryoSerializer: SecurityProvider](
    txs: Int,
    keys: List[KeyPair]
  ): F[List[Signed[Transaction]]] =
    keys.traverse { key =>
      (1 to txs).toList
        .foldM((List.empty[Signed[Transaction]], TransactionReference.empty)) {
          case (acc, _) =>
            val (txs, parent) = acc

            for {
              source <- key.getPublic.toAddress.pure[F]
              destination <- SecureRandom
                .get[F]
                .map(_.nextInt(keys.size - 1))
                .map(keys.filterNot(_ == key)(_))
                .map(_.getPublic.toAddress)
              amount = TransactionAmount(PosLong.MinValue)
              fee = TransactionFee(NonNegLong.MinValue)
              salt <- SecureRandom.get[F].map(_.nextLong()).map(TransactionSalt.apply)

              tx = Transaction(source, destination, amount, fee, parent, salt)
              signedTx <- tx.sign(key)

              reference <- signedTx.value.hashF.map(TransactionReference(tx.ordinal, _))
            } yield (txs.appended(signedTx), reference)
        }
        .map(_._1)
    }.map(_.flatten)

  def sendTransactions[F[_]: Async: Parallel: Console](client: Client[F], ip: String, port: Int)(
    txs: List[Signed[Transaction]]
  ): F[Unit] =
    txs.toList.traverse(sendTransaction(client, ip, port)).void

  def sendTransaction[F[_]: Async: Console](client: Client[F], ip: String, port: Int)(
    tx: Signed[Transaction]
  ): F[Unit] = {
    val target = Uri(scheme = Scheme.http.some, authority = Authority(host = RegName(ip), port = port.some).some)
      .addPath("transaction")
    val req = Request[F](method = Method.POST, uri = target).withEntity(tx)

    console.cyan(s"Transaction ${tx.ordinal} for key=${tx.proofs.head.id} ${target.toString()}") >>
      client.successful(req).void
  }

  def checkLastReferences[F[_]: Async: Console](client: Client[F], ip: String, port: Int)(
    keys: List[KeyPair]
  ): F[Unit] =
    keys.map(_.getPublic.toAddress.value.value).traverse(checkLastReference(client, ip, port)).void

  def checkLastReference[F[_]: Async: Console](client: Client[F], ip: String, port: Int)(address: String): F[Unit] = {
    val target = Uri(scheme = Scheme.http.some, authority = Authority(host = RegName(ip), port = port.some).some)
      .addPath(s"transaction/last-ref/${address}")
    val req = Request[F](method = Method.GET, uri = target)

    client.expect[TransactionReference](req).flatMap { reference =>
      console.green(s"Reference for address: ${address} is ${reference.show}")
    }
  }

  object console {
    def red[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.RED}${t}${scala.Console.RESET}")
    def cyan[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.CYAN}${t}${scala.Console.RESET}")
    def green[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.GREEN}${t}${scala.Console.RESET}")
    def yellow[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.YELLOW}${t}${scala.Console.RESET}")
  }
}
