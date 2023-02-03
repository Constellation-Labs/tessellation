package org.tessellation.tools

import java.nio.file.{Path => JPath}
import java.security.KeyPair

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect._
import cats.effect.std.{Console, Random}
import cats.syntax.all._

import scala.concurrent.duration._
import scala.math.Integral.Implicits._

import org.tessellation.BuildInfo
import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.infrastructure.genesis.types.GenesisCSVAccount
import org.tessellation.keytool.{KeyPairGenerator, KeyStoreUtils}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.StateChannelSnapshotBinary
import org.tessellation.tools.TransactionGenerator._
import org.tessellation.tools.cli.method._

import com.monovore.decline._
import com.monovore.decline.effect._
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric._
import fs2._
import fs2.data.csv._
import fs2.data.csv.generic.semiauto.deriveRowEncoder
import fs2.io.file.{Files, Path}
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

  /** Continuously sends transactions to a cluster
    *
    * @example
    *   {{{ send-transactions localhost:9100
    * --loadWallets kubernetes/data/genesis-keys/ }}}
    *
    * @example
    *   {{{ send-transactions localhost:9100
    * --generateWallets 100
    * --genesisPath genesis.csv }}}
    *
    * @return
    */
  override def main: Opts[IO[ExitCode]] =
    cli.method.opts.map { method =>
      SecurityProvider.forAsync[IO].use { implicit sp =>
        KryoSerializer.forAsync[IO](sharedKryoRegistrar ++ dagSharedKryoRegistrar).use { implicit kryo =>
          EmberClientBuilder.default[IO].build.use { client =>
            Random.scalaUtilRandom[IO].flatMap { implicit random =>
              (method match {
                case SendTransactionsCmd(basicOpts, walletsOpts) =>
                  walletsOpts match {
                    case w: GeneratedWallets => sendTxsUsingGeneratedWallets(client, basicOpts, w)
                    case l: LoadedWallets    => sendTxsUsingLoadedWallets(client, basicOpts, l)
                  }
                case SendStateChannelSnapshotCmd(baseUrl, verbose) =>
                  sendStateChannelSnapshot(client, baseUrl, verbose)
                case _ => IO.raiseError(new Throwable("Not implemented"))
              }).as(ExitCode.Success)
            }
          }
        }
      }
    }

  def sendStateChannelSnapshot[F[_]: Async: KryoSerializer: SecurityProvider: Console](
    client: Client[F],
    baseUrl: UrlString,
    verbose: Boolean
  ): F[Unit] =
    for {
      key <- generateKeys(1).map(_.head)
      address = key.getPublic.toAddress
      _ <- console.green(s"Generated address: $address")
      snapshot = StateChannelSnapshotBinary(Hash.empty, "test".getBytes)
      signedSnapshot <- Signed.forAsyncKryo(snapshot, key)
      hashed <- signedSnapshot.toHashed
      _ <- console.green(s"Snapshot hash: ${hashed.hash.show}, proofs hash: ${hashed.proofsHash.show}")
      _ <- postStateChannelSnapshot(client, baseUrl)(signedSnapshot, address)
    } yield ()

  def sendTxsUsingGeneratedWallets[F[_]: Async: Random: KryoSerializer: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    walletsOpts: GeneratedWallets
  ): F[Unit] =
    console.green(s"Configuration: ") >>
      console.yellow(s"Wallets: ${walletsOpts.count.show}") >>
      console.yellow(s"Genesis path: ${walletsOpts.genesisPath.toString.show}") >>
      generateKeys[F](PosInt.unsafeFrom(walletsOpts.count)).flatMap { keys =>
        createGenesis(walletsOpts.genesisPath, keys) >>
          console.cyan("Genesis created. Please start the network and continue... [ENTER]") >>
          Console[F].readLine >>
          sendTransactions(client, basicOpts, keys.map(AddressParams(_)))
            .flatMap(_ => Async[F].sleep(10.seconds))
            .flatMap(_ => checkLastReferences(client, basicOpts.baseUrl, keys.map(_.getPublic.toAddress)))
      }

  def sendTxsUsingLoadedWallets[F[_]: Async: Random: KryoSerializer: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    walletsOpts: LoadedWallets
  ): F[Unit] =
    for {
      keys <- loadKeys(walletsOpts).map(NonEmptyList.fromList).flatMap {
        Async[F].fromOption(_, new Throwable("Keys not found"))
      }
      _ <- console.green(s"Loaded ${keys.size} keys")
      addressParams <- keys.traverse { key =>
        getLastReference(client, basicOpts.baseUrl)(key.getPublic.toAddress)
          .map(lastTxRef => AddressParams(key, lastTxRef))
      }
      _ <- sendTransactions(client, basicOpts, addressParams)
      _ <- checkLastReferences(client, basicOpts.baseUrl, addressParams.map(_.address))
    } yield ()

  def sendTransactions[F[_]: Async: Random: KryoSerializer: SecurityProvider: Console](
    client: Client[F],
    basicOpts: BasicOpts,
    addressParams: NonEmptyList[AddressParams]
  ): F[Unit] =
    Clock[F].monotonic.flatMap { startTime =>
      Ref.of(0L).flatMap { counterR =>
        val printProgressApplied = counterR.get.flatMap(printProgress(startTime, _))
        val progressPrinter = Stream
          .awakeEvery(1.seconds)
          .evalMap(_ => printProgressApplied)

        infiniteTransactionStream(basicOpts.chunkSize, basicOpts.fee, addressParams)
          .flatTap(tx =>
            Stream.retry(
              postTransaction(client, basicOpts.baseUrl)(tx)
                .handleErrorWith(e => console.red(e.show) >> e.raiseError[F, Unit]),
              0.5.seconds,
              d => (d * 1.25).asInstanceOf[FiniteDuration],
              basicOpts.retryAttempts
            )
          )
          .through(applyLimit(basicOpts.take))
          .through(applyDelay(basicOpts.delay))
          .evalTap(printTx[F](basicOpts.verbose))
          .evalMap(_ => counterR.update(_ |+| 1L))
          .handleErrorWith(e => Stream.eval(console.red(e.show)))
          .mergeHaltL(progressPrinter)
          .append(Stream.eval(printProgressApplied))
          .compile
          .drain
      }
    }

  def applyLimit[F[_], A](maybeLimit: Option[PosLong]): Pipe[F, A, A] =
    in => maybeLimit.map(in.take(_)).getOrElse(in)

  def applyDelay[F[_]: Temporal, A](delay: Option[FiniteDuration]): Pipe[F, A, A] =
    in => delay.map(in.spaced(_)).getOrElse(in)

  def loadKeys[F[_]: Files: Async: SecurityProvider](opts: LoadedWallets): F[List[KeyPair]] =
    Files[F]
      .walk(Path.fromNioPath(opts.walletsPath), 1, followLinks = false)
      .filter(_.extName === ".p12")
      .evalMap { keyFile =>
        KeyStoreUtils.readKeyPairFromStore(
          keyFile.toString,
          opts.alias,
          opts.password.toCharArray,
          opts.password.toCharArray
        )
      }
      .compile
      .toList

  def generateKeys[F[_]: Async: SecurityProvider](wallets: PosInt): F[NonEmptyList[KeyPair]] =
    NonEmptyList.fromListUnsafe((1 to wallets).toList).traverse { _ =>
      KeyPairGenerator.makeKeyPair[F]
    }

  def createGenesis[F[_]: Async](genesisPath: JPath, keys: NonEmptyList[KeyPair]): F[Unit] = {
    implicit val encoder: RowEncoder[GenesisCSVAccount] = deriveRowEncoder

    Stream
      .emits[F, KeyPair](keys.toList)
      .map(_.getPublic.toAddress)
      .map(_.value.toString)
      .map(GenesisCSVAccount(_, 100000000L))
      .through(encodeWithoutHeaders[GenesisCSVAccount]())
      .through(text.utf8.encode)
      .through(Files[F].writeAll(Path.fromNioPath(genesisPath)))
      .compile
      .drain
  }

  def printProgress[F[_]: Async: Console](startTime: FiniteDuration, counter: Long): F[Unit] =
    Clock[F].monotonic.flatMap { currentTime =>
      val (minutes, seconds) = (currentTime - startTime).toSeconds /% 60
      console.green(s"$counter transactions sent in ${minutes}m ${seconds}s")
    }

  def printTx[F[_]: Applicative: Console](verbose: Boolean)(tx: Signed[Transaction]): F[Unit] =
    Applicative[F].whenA(verbose) {
      console.cyan(s"Transaction sent ordinal=${tx.ordinal} sourceAddress=${tx.source}")
    }

  def postStateChannelSnapshot[F[_]: Async](
    client: Client[F],
    baseUrl: UrlString
  )(snapshot: Signed[StateChannelSnapshotBinary], address: Address): F[Unit] = {
    val target = Uri.unsafeFromString(baseUrl.toString).addPath(s"state-channels/${address.value.value}/snapshot")
    val req = Request[F](method = Method.POST, uri = target).withEntity(snapshot)

    client.successful(req).void
  }

  def postTransaction[F[_]: Async](client: Client[F], baseUrl: UrlString)(
    tx: Signed[Transaction]
  ): F[Unit] = {
    val target = Uri.unsafeFromString(baseUrl.toString).addPath("transactions")
    val req = Request[F](method = Method.POST, uri = target).withEntity(tx)

    client
      .successful(req)
      .void
  }

  def checkLastReferences[F[_]: Async: Console](
    client: Client[F],
    baseUrl: UrlString,
    addresses: NonEmptyList[Address]
  ): F[Unit] =
    addresses.traverse(checkLastReference(client, baseUrl)).void

  def checkLastReference[F[_]: Async: Console](client: Client[F], baseUrl: UrlString)(address: Address): F[Unit] =
    getLastReference(client, baseUrl)(address).flatMap { reference =>
      console.green(s"Reference for address: ${address} is ${reference.show}")
    }

  def getLastReference[F[_]: Async](client: Client[F], baseUrl: UrlString)(
    address: Address
  ): F[TransactionReference] = {
    val target = Uri.unsafeFromString(baseUrl.toString).addPath(s"transactions/last-reference/${address.value.value}")
    val req = Request[F](method = Method.GET, uri = target)

    client.expect[TransactionReference](req)
  }

  object console {
    def red[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.RED}${t}${scala.Console.RESET}")
    def cyan[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.CYAN}${t}${scala.Console.RESET}")
    def green[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.GREEN}${t}${scala.Console.RESET}")
    def yellow[F[_]: Console](t: String) = Console[F].println(s"${scala.Console.YELLOW}${t}${scala.Console.RESET}")
  }
}
