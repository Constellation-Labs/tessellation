package io.constellationnetwork.tools

import java.math.BigInteger
import java.security.{KeyFactory, KeyPair, SecureRandom}

import cats.effect._
import cats.effect.std.Semaphore
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
import io.circe.{Json, parser}
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
    l1BaseUrls: Option[List[String]] = None,
    // Stay on each endpoint for this many txs before rotating across the L1 cluster.
    rotateEveryNTxs: Option[Int] = None,
    // Strict ordering: poll the target node until it has accepted the parent before sending the child.
    parentWaitTimeoutSeconds: Option[Int] = None,
    // Pool mode: when > 1, fan out funds from the seed wallet to a pool of fresh addresses, then run
    // that many parallel ordered chains that circulate tokens among themselves (fan-in/out). Gives
    // aggregate throughput while every chain stays ordered so nothing drops.
    poolSize: Option[Int] = None,
    fundPerAddressDatum: Option[Long] = None,
    l0BaseUrl: Option[String] = None,
    // Block Explorer base URL (e.g. https://be-testnet.constellationnetwork.io). When set, every sent
    // tx hash is tracked against the explorer and the progress line reports finalized / pending /
    // dropped -- the authoritative network-aggregated "final" status (a hash there is in a global snapshot).
    blockExplorerUrl: Option[String] = None,
    // Optimistic chaining: skip the parent-wait and advance the local ref chain on SEND (assume
    // success; don't wait for the parent to land on the target node). Runs at the configured rate
    // instead of the cluster's gossip speed. Pair with a large rotateEveryNTxs / pool mode so the
    // receiving node already holds the chain. If `optimisticToggleFile` is set it wins at runtime:
    // optimistic exactly while that file exists (touch to go optimistic, rm to return to strict).
    optimistic: Option[Boolean] = None,
    optimisticToggleFile: Option[String] = None,
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

  private def shortHost(url: String): String =
    url.replaceFirst("^https?://", "").takeWhile(_ != '/')

  def run(configPath: String)(implicit hasher: Hasher[IO], sp: SecurityProvider[IO]): IO[Unit] =
    for {
      config <- IO.delay(ConfigSource.file(configPath).loadOrThrow[TxSenderConfig]).adaptError {
        case e => new Exception(s"Failed to load config from '$configPath': ${e.getMessage}", e)
      }
      endpoints = config.l1BaseUrls
        .map(_.map(_.trim).filter(_.nonEmpty))
        .filter(_.nonEmpty)
        .getOrElse(List(config.l1BaseUrl.trim))
        .distinct
      _ <- IO.raiseWhen(endpoints.isEmpty)(new Exception("No L1 endpoints configured (l1BaseUrl / l1BaseUrls)"))
      seedKeyPair <- hexToKeyPair[IO](config.privateKeyHex)
      seedAddress = seedKeyPair.getPublic.toAddress
      _ <- IO.println(s"Loaded config from: $configPath")
      _ <- IO.println(s"  L1 endpoints (${endpoints.size}): ${endpoints.map(shortHost).mkString(", ")}")
      _ <- IO.println(s"  Seed address: ${seedAddress.value.value}")
      _ <- IO.println(s"  Amount/tx: ${config.amountDatum} datum, Fee/tx: ${config.feeDatum} datum")
      _ <- EmberClientBuilder
        .default[IO]
        .withTimeout(30.seconds)
        .withIdleConnectionTime(60.seconds)
        .withMaxTotal(256)
        .build
        .use { client =>
          config.poolSize.filter(_ > 1) match {
            case Some(n) => runPool(client, config, endpoints, seedKeyPair, seedAddress, n)
            case _       => runSingle(client, config, endpoints, seedKeyPair, seedAddress)
          }
        }
    } yield ()

  // ---- single-wallet mode (legacy): one ordered chain, rotating across nodes ----
  private def runSingle(
    client: Client[IO],
    config: TxSenderConfig,
    endpoints: List[String],
    keyPair: KeyPair,
    source: Address
  )(implicit hasher: Hasher[IO], sp: SecurityProvider[IO]): IO[Unit] =
    for {
      recipients <- config.recipients.traverse { addr =>
        IO.fromEither(
          refineV[DAGAddressRefined](addr)
            .bimap(e => new Exception(s"Invalid recipient address '$addr': $e"), Address(_))
        )
      }
      _ <- IO.raiseWhen(recipients.isEmpty)(new Exception("No recipient addresses configured"))
      _ <- IO.raiseWhen(config.burstCount <= 0 && config.steadyCount <= 0)(
        new Exception("Both burstCount and steadyCount are <= 0 -- nothing to send.")
      )
      lastRef <- getLastReference(client, endpoints.head, source)
      _ <- IO.println(s"  Last ref ordinal: ${lastRef.ordinal.value}\nStarting (single wallet)...\n")
      shared <- SharedState.create
      _ <- {
        val rotateEvery = config.rotateEveryNTxs.filter(_ > 0).getOrElse(1)
        val timeout = config.parentWaitTimeoutSeconds.filter(_ > 0).getOrElse(120).seconds
        Ref.of[IO, TransactionReference](lastRef).flatMap { refRef =>
          Ref.of[IO, Int](0).flatMap { rIdx =>
            val pick = rIdx.getAndUpdate(_ + 1).map(i => recipients(Math.floorMod(i, recipients.size)))
            val sendOne = pick.flatMap(dest =>
              sendOneTx(client, config, endpoints, rotateEvery, timeout, shared, keyPair, source, refRef, dest, config.amountDatum).void
            )
            runChain(client, config, shared, sendOne, label = "single")
          }
        }
      }
    } yield ()

  // ---- pool mode: fan-out -> circulate among N parallel ordered chains ----
  private def runPool(
    client: Client[IO],
    config: TxSenderConfig,
    endpoints: List[String],
    seedKeyPair: KeyPair,
    seedAddress: Address,
    poolSize: Int
  )(implicit hasher: Hasher[IO], sp: SecurityProvider[IO]): IO[Unit] = {
    val rotateEvery = config.rotateEveryNTxs.filter(_ > 0).getOrElse(1)
    val timeout = config.parentWaitTimeoutSeconds.filter(_ > 0).getOrElse(120).seconds
    val fundPerAddress = config.fundPerAddressDatum.filter(_ > 0).getOrElse(5000000000L) // 50 DAG
    val l0Url = config.l0BaseUrl.map(_.trim).filter(_.nonEmpty).getOrElse("https://l0-lb-testnet.constellationnetwork.io")
    for {
      pool <- List.range(0, poolSize).traverse(_ => generateKeyPair[IO])
      poolAddrs = pool.map(_.getPublic.toAddress)
      _ <- IO.println(s"\n=== Pool mode: $poolSize addresses, fund ${fundPerAddress} datum each, L0=$l0Url ===")
      _ <- poolAddrs.zipWithIndex.traverse_ { case (a, i) => IO.println(f"  pool[$i%2d] ${a.value.value}") }
      shared <- SharedState.create
      // 1. FAN-OUT: seed funds each pool address (seed's own ordered chain, rotating nodes)
      seedRef0 <- getLastReference(client, endpoints.head, seedAddress)
      seedRefRef <- Ref.of[IO, TransactionReference](seedRef0)
      // Meter the fan-out: firing the funding txs back-to-back trips the L1 rate limiter so only the
      // first finalizes. Spacing them at the steady interval keeps them under the limit and they land.
      _ <- IO.println(s"\nFan-out: funding $poolSize addresses from seed, 1 every ${config.steadyIntervalSeconds.max(3)}s...")
      _ <- Stream
        .emits(poolAddrs)
        .covary[IO]
        .metered(config.steadyIntervalSeconds.max(3).seconds)
        .evalMap { addr =>
          sendOneTx(client, config, endpoints, rotateEvery, timeout, shared, seedKeyPair, seedAddress, seedRefRef, addr, fundPerAddress)
            .flatMap(ok => IO.println(s"  fund -> ${addr.value.value.take(10)}.. ${if (ok) "ok" else "FAILED"}"))
        }
        .compile
        .drain
      // 2. Each worker waits for ITS OWN funding to finalize before circulating (below), so a slow or
      //    sparse funding-finalization no longer drops the whole pool to fail-fast.
      _ <- IO.println("\nWorkers each wait for their own funding to finalize, then circulate...")
      // 3. CIRCULATION: N parallel ordered chains, each sending to the other pool addresses
      _ <- IO.println(s"\nCirculation: $poolSize parallel workers. Press Ctrl+C to stop.\n")
      start <- Clock[IO].monotonic
      progress = background(client, config, shared, start)
      workers = pool.zipWithIndex.parTraverse {
        case (kp, idx) =>
          val addr = kp.getPublic.toAddress
          val others = poolAddrs.zipWithIndex.filter(_._2 != idx).map(_._1)
          for {
            _ <- waitForBalances(client, l0Url, List(addr), fundPerAddress * 9 / 10, 1800.seconds)
            ref0 <- getLastReference(client, endpoints.head, addr)
            refRef <- Ref.of[IO, TransactionReference](ref0)
            kRef <- Ref.of[IO, Int](0)
            consec <- Ref.of[IO, Int](0)
            sendOne = for {
              k <- kRef.getAndUpdate(_ + 1)
              dest = others(Math.floorMod(k, others.size))
              ok <- sendOneTx(client, config, endpoints, rotateEvery, timeout, shared, kp, addr, refRef, dest, config.amountDatum)
              _ <-
                if (ok) consec.set(0)
                else
                  consec.updateAndGet(_ + 1).flatMap(c => IO.raiseWhen(c >= 8)(new Exception(s"worker $idx: 8 consecutive failures")))
            } yield ()
            _ <- cyclesStream(config, sendOne)
              .handleErrorWith(e => Stream.eval(IO.println(s"  worker $idx (${addr.value.value.take(8)}..) stopped: ${e.getMessage}")))
              .compile
              .drain
          } yield ()
      }.void
      _ <- IO.race(workers, progress).void
    } yield ()
  }

  // shared counters + per-node tally + global endpoint rotation
  final case class SharedState(
    counter: Ref[IO, Long],
    errors: Ref[IO, Long],
    stats: Ref[IO, Map[String, (Long, Long)]],
    endpointIdx: Ref[IO, Int],
    inflight: Semaphore[IO],
    pending: Ref[IO, Map[String, (Address, FiniteDuration)]],
    finalized: Ref[IO, Long],
    dropped: Ref[IO, Long]
  )
  object SharedState {
    def create: IO[SharedState] =
      (
        Ref.of[IO, Long](0L),
        Ref.of[IO, Long](0L),
        Ref.of[IO, Map[String, (Long, Long)]](Map.empty),
        Ref.of[IO, Int](0),
        Semaphore[IO](128L),
        Ref.of[IO, Map[String, (Address, FiniteDuration)]](Map.empty),
        Ref.of[IO, Long](0L),
        Ref.of[IO, Long](0L)
      ).mapN(SharedState.apply)
  }

  private def sendOneTx(
    client: Client[IO],
    config: TxSenderConfig,
    endpoints: List[String],
    rotateEvery: Int,
    timeout: FiniteDuration,
    shared: SharedState,
    senderKp: KeyPair,
    senderAddr: Address,
    refRef: Ref[IO, TransactionReference],
    dest: Address,
    amount: Long
  )(implicit hasher: Hasher[IO], sp: SecurityProvider[IO]): IO[Boolean] =
    for {
      opt <- isOptimistic(config)
      i <- shared.endpointIdx.getAndUpdate(_ + 1)
      endpoint = endpoints(Math.floorMod(i / rotateEvery, endpoints.size))
      host = shortHost(endpoint)
      parentRef <- refRef.get
      _ <- if (opt) IO.unit else waitForParent(client, endpoint, senderAddr, parentRef, timeout)
      salt <- TransactionSalt.generate[IO]
      tx = Transaction(
        source = senderAddr,
        destination = dest,
        amount = TransactionAmount(PosLong.unsafeFrom(amount)),
        fee = TransactionFee(NonNegLong.unsafeFrom(config.feeDatum)),
        parent = parentRef,
        salt = salt
      )
      signedTx <- Signed.forAsyncHasher[IO, Transaction](tx, senderKp)
      txHash <- signedTx.value.hash
      newRef = TransactionReference(parentRef.ordinal.next, txHash)
      // optimistic: advance the local chain now (assume success) so the next tx fires immediately
      _ <- if (opt) refRef.set(newRef) else IO.unit
      // submit + record the result. In optimistic mode this is fire-and-forget (a background fiber,
      // bounded by the `inflight` semaphore) so the sender runs at the configured rate instead of
      // blocking on the cluster's slow tx-acceptance; in strict mode it is awaited.
      tally = submitTransaction(client, endpoint, signedTx).attempt.timed.flatMap {
        case (postDur, result) =>
          val ms = postDur.toMillis
          result match {
            case Right(Right(h)) =>
              Clock[IO].monotonic.flatMap { now =>
                (if (opt) IO.unit else refRef.set(newRef)) *> shared.counter.update(_ + 1) *> bump(shared, host, ok = true) *>
                  (if (config.blockExplorerUrl.exists(_.trim.nonEmpty)) shared.pending.update(_ + (h -> (senderAddr, now)))
                   else IO.unit) *>
                  (if (ms > 800) IO.println(s"  [SLOW-tx] $host ord=${newRef.ordinal.value} post=${ms}ms") else IO.unit).as(true)
              }
            case Right(Left(err)) =>
              // Optimistic-chain resync: a HasNoMatchingParent means the node dropped its unfinalized
              // backlog (rolled the L1 tip back to the last finalized ref) and our optimistic local nonce
              // is now orphaned -- every further tx would 400 the same way. Re-read the node's last-reference
              // and reset the local chain to it so the next tx chains from the confirmed tip instead of
              // flooding rejections. No-op in strict mode (it never runs ahead of the node).
              val resync =
                if (opt && err.contains("HasNoMatchingParent"))
                  getLastReference(client, endpoint, senderAddr).attempt.flatMap {
                    case Right(nodeRef) =>
                      refRef.set(nodeRef) *>
                        IO.println(s"  [RESYNC] $host orphaned at ord ${newRef.ordinal.value} -> node last-ref ${nodeRef.ordinal.value}")
                    case Left(_) => IO.unit
                  }
                else IO.unit
              shared.errors.update(_ + 1) *> bump(shared, host, ok = false) *>
                IO.println(s"  [REJECTED] $host ord=${newRef.ordinal.value} post=${ms}ms: ${err.take(55)}") *>
                resync.as(false)
            case Left(e) =>
              shared.errors.update(_ + 1) *> bump(shared, host, ok = false) *>
                IO.println(s"  [ERROR] $host post=${ms}ms: ${Option(e.getMessage).getOrElse("").take(55)}").as(false)
          }
      }
      // Always AWAIT the submit (even optimistic). Optimistic still skips the parent-wait and advances
      // the chain on send for speed, but fire-and-forget drowned the client's connection pool; awaiting
      // keeps a single in-flight POST so the sender runs at the node's accept latency (~0.13s).
      ok <- tally
    } yield ok

  private def bump(shared: SharedState, host: String, ok: Boolean): IO[Unit] =
    shared.stats.update { m =>
      val (o, f) = m.getOrElse(host, (0L, 0L))
      m.updated(host, if (ok) (o + 1, f) else (o, f + 1))
    }

  // Chain optimistically? A configured toggle file wins at runtime (optimistic exactly while it
  // exists); otherwise the static `optimistic` flag (default false = strict parent-wait).
  private def isOptimistic(config: TxSenderConfig): IO[Boolean] =
    config.optimisticToggleFile.map(_.trim).filter(_.nonEmpty) match {
      case Some(f) => IO.delay(java.nio.file.Files.exists(java.nio.file.Paths.get(f)))
      case None    => IO.pure(config.optimistic.getOrElse(false))
    }

  private def cyclesStream(config: TxSenderConfig, sendOne: IO[Unit]): Stream[IO, Unit] = {
    val burstInterval = (1000.0 / config.burstTps.max(1)).millis
    val steadyInterval = config.steadyIntervalSeconds.max(1).seconds
    val burstPhase =
      if (config.burstCount <= 0) Stream.empty
      else Stream.repeatEval(sendOne).take(config.burstCount.toLong).metered(burstInterval)
    val steadyPhase =
      if (config.steadyCount <= 0) Stream.empty
      else Stream.repeatEval(sendOne).take(config.steadyCount.toLong).metered(steadyInterval)
    val oneCycle = steadyPhase ++ burstPhase
    if (config.cycles <= 0) oneCycle.repeat
    else Stream.range(0, config.cycles).flatMap(_ => oneCycle)
  }

  // single-wallet runner: drive one chain + a progress printer
  private def runChain(
    client: Client[IO],
    config: TxSenderConfig,
    shared: SharedState,
    sendOne: IO[Unit],
    label: String
  ): IO[Unit] =
    Clock[IO].monotonic.flatMap { start =>
      val stream = cyclesStream(config, sendOne)
        .handleErrorWith(e => Stream.eval(IO.println(s"\n  Stopped ($label): ${e.getMessage}")))
      IO.race(stream.compile.drain, background(client, config, shared, start)).void
    }

  private def progressLoop(shared: SharedState, start: FiniteDuration): IO[Unit] =
    Stream
      .awakeEvery[IO](10.seconds)
      .evalMap { _ =>
        (
          shared.counter.get,
          shared.errors.get,
          shared.finalized.get,
          shared.pending.get,
          shared.dropped.get,
          shared.stats.get,
          Clock[IO].monotonic
        ).tupled.flatMap {
          case (sent, errors, finalized, pending, dropped, stats, now) =>
            val elapsed = (now - start).toSeconds.max(1)
            val tps = sent.toDouble / elapsed
            val perNode = stats.toList.sortBy(_._1).map { case (h, (o, f)) => s"$h=$o/$f" }.mkString("  ")
            IO.println(
              s"  [progress] sent=$sent errors=$errors finalized=$finalized pending=${pending.size} dropped=$dropped elapsed=${elapsed}s tps=${f"$tps%.1f"}"
            ) *> IO.println(s"  [nodes ok/fail] $perNode")
        }
      }
      .compile
      .drain

  // Progress printer, plus (when a Block Explorer is configured) the finalization tracker, run together.
  private def background(client: Client[IO], config: TxSenderConfig, shared: SharedState, start: FiniteDuration): IO[Unit] =
    config.blockExplorerUrl.map(_.trim).filter(_.nonEmpty) match {
      case Some(be) => IO.race(progressLoop(shared, start), finalizationTracker(client, be, shared)).void
      case None     => progressLoop(shared, start)
    }

  // Poll the Block Explorer for each pending-tx source address. A tx hash that appears there is truly
  // FINALIZED (in a global snapshot). Hashes not seen within the TTL are counted as dropped (L1-accepted
  // but never finalized) -- the authoritative, network-aggregated view of what actually landed.
  private def finalizationTracker(client: Client[IO], beUrl: String, shared: SharedState): IO[Unit] =
    Stream
      .awakeEvery[IO](20.seconds)
      .evalMap { _ =>
        for {
          pend <- shared.pending.get
          now <- Clock[IO].monotonic
          addrs = pend.values.map(_._1).toSet.toList
          beHashes <- addrs.foldLeftM(Set.empty[String])((acc, a) => getBeFinalizedHashes(client, beUrl, a).map(acc ++ _))
          _ <- pend.toList.traverse_ {
            case (h, (_, sentAt)) =>
              if (beHashes.contains(h)) shared.pending.update(_ - h) *> shared.finalized.update(_ + 1)
              else if ((now - sentAt) > 8.minutes) shared.pending.update(_ - h) *> shared.dropped.update(_ + 1)
              else IO.unit
          }
        } yield ()
      }
      .compile
      .drain

  private def getBeFinalizedHashes(client: Client[IO], beUrl: String, address: Address): IO[Set[String]] =
    client
      .run(Request[IO](Method.GET, Uri.unsafeFromString(s"$beUrl/addresses/${address.value.value}/transactions?limit=50")))
      .use(_.bodyText.compile.string)
      .map { body =>
        parser
          .parse(body)
          .toOption
          .flatMap(_.hcursor.downField("data").as[List[Json]].toOption)
          .map(_.flatMap(_.hcursor.downField("hash").as[String].toOption).toSet)
          .getOrElse(Set.empty[String])
      }
      .handleError(_ => Set.empty[String])

  private def waitForBalances(
    client: Client[IO],
    l0BaseUrl: String,
    addrs: List[Address],
    minBalance: Long,
    timeout: FiniteDuration
  ): IO[Unit] = {
    def funded: IO[Int] = addrs.traverse(a => getBalance(client, l0BaseUrl, a)).map(_.count(_ >= minBalance))
    def loop(deadline: FiniteDuration): IO[Unit] =
      funded.flatMap { n =>
        if (n >= addrs.size) IO.println(s"  all ${addrs.size} pool addresses funded")
        else
          Clock[IO].monotonic.flatMap { now =>
            if (now >= deadline) IO.println(s"  timeout: $n/${addrs.size} funded, proceeding anyway")
            else IO.println(s"  $n/${addrs.size} funded, waiting...") >> IO.sleep(5.seconds) >> loop(deadline)
          }
      }
    Clock[IO].monotonic.flatMap(s => loop(s + timeout))
  }

  private def getBalance(client: Client[IO], l0BaseUrl: String, address: Address): IO[Long] =
    client
      .run(Request[IO](Method.GET, Uri.unsafeFromString(s"$l0BaseUrl/dag/${address.value.value}/balance")))
      .use(_.bodyText.compile.string)
      .map(body => parser.parse(body).toOption.flatMap(_.hcursor.downField("balance").as[Long].toOption).getOrElse(0L))
      .handleError(_ => 0L)

  private def getLastReference(
    client: Client[IO],
    l1BaseUrl: String,
    address: Address
  ): IO[TransactionReference] = {
    val uri = Uri.unsafeFromString(s"$l1BaseUrl/transactions/last-reference/${address.value.value}")
    client.expect[TransactionReference](uri)
  }

  private def waitForParent(
    client: Client[IO],
    l1BaseUrl: String,
    address: Address,
    parent: TransactionReference,
    timeout: FiniteDuration
  ): IO[Unit] = {
    def loop(deadline: FiniteDuration): IO[Unit] =
      getLastReference(client, l1BaseUrl, address).attempt.flatMap {
        case Right(ref) if ref.hash === parent.hash => IO.unit
        case _ =>
          Clock[IO].monotonic.flatMap(now => if (now >= deadline) IO.unit else IO.sleep(500.millis) >> loop(deadline))
      }
    Clock[IO].monotonic.flatMap(start => loop(start + timeout))
  }

  private def submitTransaction(
    client: Client[IO],
    l1BaseUrl: String,
    signedTx: Signed[Transaction]
  ): IO[Either[String, String]] = {
    val uri = Uri.unsafeFromString(s"$l1BaseUrl/transactions")
    val request = Request[IO](Method.POST, uri).withEntity(signedTx)
    client.run(request).use { response =>
      val code = response.status.code
      // Read the RAW body (NOT response.as[String] -- the CirceEntityCodec import hijacks that into
      // circe's Decoder[String], which fails on a JSON-object body). Success -> hash; reject -> errors[].
      response.bodyText.compile.string.map { body =>
        parser.parse(body) match {
          case Right(json) =>
            json.hcursor.downField("hash").as[String] match {
              case Right(h) if h.nonEmpty => Right(h)
              case _ =>
                val msg = json.hcursor
                  .downField("errors")
                  .as[List[Json]]
                  .toOption
                  .map(_.flatMap(_.hcursor.downField("message").as[String].toOption).mkString("; "))
                  .filter(_.nonEmpty)
                if (response.status == Status.Ok && msg.isEmpty) Right("unknown")
                else Left(s"$code ${msg.getOrElse(body).take(160)}")
            }
          case Left(_) =>
            if (response.status == Status.Ok) Right("unknown") else Left(s"$code: ${body.take(160)}")
        }
      }
    }
  }

  private def generateKeyPair[F[_]: Async: SecurityProvider]: F[KeyPair] = {
    val genHex: F[String] = Async[F].delay {
      val bytes = new Array[Byte](32)
      new SecureRandom().nextBytes(bytes)
      bytes.map(b => f"${b & 0xff}%02x").mkString
    }
    genHex.flatMap(hex => hexToKeyPair[F](hex)).handleErrorWith(_ => generateKeyPair[F])
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
    val privateKey = kf.generatePrivate(new ECPrivateKeySpec(privateKeyInt, curveParams))
    val pubPoint = curveParams.getG.multiply(privateKeyInt).normalize()
    val publicKey = kf.generatePublic(new ECPublicKeySpec(pubPoint, curveParams))
    new KeyPair(publicKey, privateKey)
  }
}
