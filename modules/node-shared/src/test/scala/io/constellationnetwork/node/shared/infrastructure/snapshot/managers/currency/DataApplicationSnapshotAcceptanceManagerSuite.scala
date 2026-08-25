package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.UUID

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap.{AllowSpend, CurrencyId}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import weaver.MutableIOSuite

object DataApplicationSnapshotAcceptanceManagerSuite extends MutableIOSuite {

  private case class TestOnChain(accepted: List[Int]) extends DataOnChainState
  private case class TestCalculated(accepted: List[Int]) extends DataCalculatedState
  private case class TestUpdate(value: Int) extends DataUpdate

  type Res = (Hasher[IO], JsonSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      securityProvider <- SecurityProvider.forAsync[IO]
    } yield (Hasher.forJson[IO], json, securityProvider)

  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal(NonNegLong.unsafeFrom(value))

  private def updateBytes(update: DataUpdate): Array[Byte] =
    update.asInstanceOf[TestUpdate].value.toString.getBytes(StandardCharsets.UTF_8)

  private def proof(keyPair: KeyPair): SignatureProof =
    SignatureProof(keyPair.getPublic.toId, Signature(Hex("")))

  private def block(
    value: Int,
    source: Address,
    destination: Address,
    dataUpdateRef: Hash,
    signatureProof: SignatureProof
  ): Signed[DataApplicationBlock] = {
    val update: Signed[DataTransaction] = Signed(TestUpdate(value), NonEmptySet.one(signatureProof))
    val fee: Signed[DataTransaction] = Signed(
      FeeTransaction(source, destination, Amount(NonNegLong.unsafeFrom(1L)), dataUpdateRef),
      NonEmptySet.one(signatureProof)
    )
    val transactions: DataTransactions = NonEmptyList.of(update, fee)

    Signed(
      DataApplicationBlock(
        RoundId(new UUID(0L, value.toLong)),
        NonEmptyList.one(transactions),
        NonEmptyList.one(NonEmptyList.one(Hash.empty))
      ),
      NonEmptySet.one(signatureProof)
    )
  }

  private def context(
    securityProviderInstance: SecurityProvider[IO],
    source: Address
  ): L0NodeContext[IO] = new L0NodeContext[IO] {
    private val snapshotInfo = CurrencySnapshotInfo(
      SortedMap.empty,
      SortedMap(source -> Balance(NonNegLong.unsafeFrom(100L))),
      None,
      None,
      None,
      None,
      None,
      None,
      None
    )

    def getLastSynchronizedGlobalSnapshot: IO[Option[GlobalIncrementalSnapshot]] = IO.raiseError(new NotImplementedError)
    def getLastSynchronizedGlobalSnapshotCombined: IO[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] =
      IO.raiseError(new NotImplementedError)
    def getLastSynchronizedAllowSpends: IO[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]] =
      IO.raiseError(new NotImplementedError)
    def getLastSynchronizedTokenLocks: IO[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]] =
      IO.raiseError(new NotImplementedError)
    def getLastCurrencySnapshot: IO[Option[Hashed[CurrencyIncrementalSnapshot]]] = IO.raiseError(new NotImplementedError)
    def getCurrencySnapshot(ordinal: SnapshotOrdinal): IO[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      IO.raiseError(new NotImplementedError)
    def getLastCurrencySnapshotCombined: IO[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
      IO.pure(Some((null.asInstanceOf[Hashed[CurrencyIncrementalSnapshot]], snapshotInfo)))
    def securityProvider: SecurityProvider[IO] = securityProviderInstance
    def getCurrencyId: IO[CurrencyId] = IO.raiseError(new NotImplementedError)
    def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]] = None
    def getSnapshotFeeTransactions: IO[Map[Hash, Signed[FeeTransaction]]] = IO.pure(Map.empty)
  }

  private def service(observedFeeMaps: Ref[IO, List[Set[Hash]]]): BaseDataApplicationL0Service[IO] =
    new BaseDataApplicationL0Service[IO] {
      def serializeState(state: DataOnChainState): IO[Array[Byte]] = IO.pure(Array.emptyByteArray)
      def deserializeState(bytes: Array[Byte]): IO[Either[Throwable, DataOnChainState]] = IO.pure(Right(TestOnChain(Nil)))
      def serializeUpdate(update: DataUpdate): IO[Array[Byte]] = IO.pure(updateBytes(update))
      def deserializeUpdate(bytes: Array[Byte]): IO[Either[Throwable, DataUpdate]] = IO.raiseError(new NotImplementedError)
      def serializeBlock(block: Signed[DataApplicationBlock]): IO[Array[Byte]] =
        IO.pure(block.value.roundId.toString.getBytes(StandardCharsets.UTF_8))
      def deserializeBlock(bytes: Array[Byte]): IO[Either[Throwable, Signed[DataApplicationBlock]]] =
        IO.raiseError(new NotImplementedError)
      def serializeCalculatedState(state: DataCalculatedState): IO[Array[Byte]] = IO.pure(Array.emptyByteArray)
      def deserializeCalculatedState(bytes: Array[Byte]): IO[Either[Throwable, DataCalculatedState]] =
        IO.raiseError(new NotImplementedError)
      def dataEncoder: io.circe.Encoder[DataUpdate] = throw new NotImplementedError
      def dataDecoder: io.circe.Decoder[DataUpdate] = throw new NotImplementedError
      def signedDataEntityEncoder: EntityEncoder[IO, Signed[DataUpdate]] = throw new NotImplementedError
      def signedDataEntityDecoder: EntityDecoder[IO, Signed[DataUpdate]] = throw new NotImplementedError
      def calculatedStateEncoder: io.circe.Encoder[DataCalculatedState] = throw new NotImplementedError
      def calculatedStateDecoder: io.circe.Decoder[DataCalculatedState] = throw new NotImplementedError
      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[IO]
      ): IO[DataApplicationValidationErrorOr[Unit]] = ().validNec.pure[IO]
      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L0NodeContext[IO]
      ): IO[DataState.Base] =
        context.getSnapshotFeeTransactions.flatMap { feeMap =>
          observedFeeMaps.update(_ :+ feeMap.keySet) >>
            (updates.collectFirst { case Signed(TestUpdate(2), _) => () } match {
              case Some(_) => IO.raiseError(new IllegalArgumentException("reject update 2"))
              case None =>
                val accepted = updates.collect { case Signed(TestUpdate(value), _) => value }
                IO.pure(DataState(TestOnChain(accepted), TestCalculated(accepted)))
            })
        }
      def getCalculatedState(implicit context: L0NodeContext[IO]): IO[(SnapshotOrdinal, DataCalculatedState)] =
        IO.pure(ordinal(0L) -> TestCalculated(Nil))
      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[IO]): IO[Boolean] =
        IO.pure(true)
      def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[IO]): IO[Hash] = IO.pure(Hash.empty)
      def routes(implicit context: L0NodeContext[IO]): HttpRoutes[IO] = HttpRoutes.empty
      def routesPrefix: ExternalUrlPrefix = "/data-application"
      def genesis: DataState.Base = DataState(TestOnChain(Nil), TestCalculated(Nil))
      def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): IO[Unit] = IO.unit
      def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): IO[Unit] = IO.unit
      def getTokenUnlocks(state: DataState.Base)(
        implicit context: L0NodeContext[IO],
        async: cats.effect.Async[IO],
        hasher: Hasher[IO]
      ): IO[SortedSet[TokenUnlock]] = IO.pure(SortedSet.empty)
    }

  test("recomputes combine with fees from only the blocks that remain accepted") {
    case (hasher, jsonSerializer, securityProvider) =>
      implicit val h: Hasher[IO] = hasher
      implicit val j: JsonSerializer[IO] = jsonSerializer
      implicit val sp: SecurityProvider[IO] = securityProvider

      for {
        sourceKey <- KeyPairGenerator.makeKeyPair[IO]
        destinationKey <- KeyPairGenerator.makeKeyPair[IO]
        source = sourceKey.getPublic.toAddress
        destination = destinationKey.getPublic.toAddress
        signatureProof = proof(sourceKey)
        firstRef <- Hash.fromBytesForSync[IO](updateBytes(TestUpdate(1)))
        rejectedRef <- Hash.fromBytesForSync[IO](updateBytes(TestUpdate(2)))
        firstBlock = block(1, source, destination, firstRef, signatureProof)
        rejectedBlock = block(2, source, destination, rejectedRef, signatureProof)
        observedFeeMaps <- Ref.of[IO, List[Set[Hash]]](Nil)
        manager = DataApplicationSnapshotAcceptanceManager.make[IO](
          service(observedFeeMaps),
          context(securityProvider, source),
          null.asInstanceOf[CalculatedStateLocalFileSystemStorage[IO]],
          ordinal(100L)
        )
        previous = DataApplicationPart(Array.emptyByteArray, Nil, Hash.empty, None)
        result <- manager.accept(Some(previous), List(firstBlock, rejectedBlock), ordinal(0L), ordinal(1L), ordinal(1L))
        seen <- observedFeeMaps.get
        accepted = result.get
      } yield
        expect
          .same(
            List(Set(firstRef, rejectedRef), Set(firstRef, rejectedRef), Set(firstRef)),
            seen
          )
          .and(expect.same(List(firstRef), accepted.feeTransactions.map(_.value.dataUpdateRef).toList))
          .and(
            expect.same(List(firstBlock), List(firstBlock, rejectedBlock).filterNot(block => accepted.notAccepted.exists(_._1 == block)))
          )
          .and(expect.same(List(rejectedBlock), accepted.notAccepted.map(_._1)))
          .and(expect.same(TestCalculated(List(1)), accepted.calculatedState))
  }
}
