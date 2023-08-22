package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.list._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.{Block, _}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.consensus.trigger.EventTrigger
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.{GlobalSnapshotAcceptanceManager, GlobalSnapshotStateChannelEventsProcessor}
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotConsensusFunctionsSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], SecurityProvider[IO], Metrics[IO])

  override def sharedResource: Resource[IO, Res] =
    Supervisor[IO].flatMap { supervisor =>
      KryoSerializer.forAsync[IO](sdkKryoRegistrar).flatMap { ks =>
        SecurityProvider.forAsync[IO].flatMap { sp =>
          Metrics.forAsync[IO](Seq.empty).map((supervisor, ks, sp, _))
        }
      }
    }

  val gss: SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      override def prepend(snapshot: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Boolean] = ???

      override def head: IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???
      override def headSnapshot: IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def get(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def getHash(ordinal: SnapshotOrdinal): F[Option[Hash]] = ???

    }

  val bam: BlockAcceptanceManager[IO] = new BlockAcceptanceManager[IO] {

    override def acceptBlocksIteratively(
      blocks: List[Signed[Block]],
      context: BlockAcceptanceContext[IO]
    ): IO[BlockAcceptanceResult] =
      BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        List.empty,
        List.empty
      ).pure[IO]

    override def acceptBlock(
      block: Signed[Block],
      context: BlockAcceptanceContext[IO]
    ): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] = ???

  }

  val scProcessor = new GlobalSnapshotStateChannelEventsProcessor[IO] {
    def process(
      ordinal: SnapshotOrdinal,
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent],
      validationType: StateChannelValidationType
    ): IO[
      (
        SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        SortedMap[Address, CurrencySnapshotWithState],
        Set[StateChannelEvent]
      )
    ] = IO(
      (events.groupByNel(_.address).view.mapValues(_.map(_.snapshotBinary)).toSortedMap, SortedMap.empty, Set.empty)
    )

    def processCurrencySnapshots(
      lastGlobalSnapshotInfo: GlobalSnapshotContext,
      events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]]
    ): IO[SortedMap[Address, NonEmptyList[CurrencySnapshotWithState]]] = ???
  }

  val collateral: Amount = Amount.empty

  val rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot] =
    (_, _, _, _) => IO(SortedSet.empty)

  def mkGlobalSnapshotConsensusFunctions()(implicit ks: KryoSerializer[IO], sp: SecurityProvider[IO], m: Metrics[IO]) = {
    val snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make[IO](bam, scProcessor, collateral)
    GlobalSnapshotConsensusFunctions.make[IO](gss, snapshotAcceptanceManager, collateral, rewards, env)
  }

  val env: AppEnvironment = AppEnvironment.Testnet

  test("global snapshot hash value comparison") { res =>
    implicit val (_, ks, _, _) = res

    import org.tessellation.ext.crypto._
    import org.tessellation.ext.kryo._

    def printBytes(label: String, bytes: Either[Throwable, Array[Byte]]): Unit =
      println(s"\n$label:\n${Hex.fromBytes(bytes.getOrElse(Array(0: Byte)), Some("-"))}")

    val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
    val genesis_v2 = GlobalSnapshotV2.mkGenesis(Map.empty, EpochProgress.MinValue)

    val kryoBytes = genesis_v2.toBinary
    val encodeBytes = genesis_v2.toEncode.toBinary

    printBytes("v2 toBinary", genesis_v2.toBinary)

//    printBytes("snapshotKryoBytes", kryoBytes)
//    printBytes("snapshotEncodeBytes", encodeBytes)
//    printBytes("ordinalBytes", ks.serialize(genesis_v2.ordinal))
//    println("heightBytes: " + genesis_v2.height.value.value.toHexString)
//    println("subheightBytes: " + genesis_v2.subHeight.value.value.toHexString)
//    printBytes("lastSnapshotHashBytes", genesis_v2.lastSnapshotHash.getBytes.asRight[Throwable])
//    printBytes("blocksBytes", genesis_v2.blocks.toBinary)
//    printBytes("stateChannelSnapshotsBytes", ks.serialize(genesis_v2.stateChannelSnapshots))
//    printBytes("rewardsBytes", ks.serialize(genesis_v2.rewards))
//    println("epochProgressBytes: " + genesis_v2.epochProgress.value.value.toHexString)
//    printBytes("nextFacilitatorsBytes", ks.serialize(genesis_v2.nextFacilitators))
//    printBytes("infoBytes", ks.serialize(genesis_v2.info))
//    printBytes("tipsBytes", ks.serialize(genesis_v2.tips))
//    printBytes("optionIntBytes", ks.serialize(genesis_v2.optionInt))

    IO.pure(expect.same(kryoBytes.hash, encodeBytes.hash))

  }

  test("validateArtifact - returns artifact for correct data") { res =>
    implicit val (_, ks, sp, m) = res

    val gscf = mkGlobalSnapshotConsensusFunctions()

    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value).flatMap { lastArtifact =>
          Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair).flatMap { signedLastArtifact =>
            mkStateChannelEvent().flatMap { scEvent =>
              gscf
                .createProposalArtifact(
                  SnapshotOrdinal.MinValue,
                  signedLastArtifact,
                  signedGenesis.value.info,
                  EventTrigger,
                  Set(scEvent.asLeft[DAGEvent])
                )
                .flatMap {
                  case (artifact, _, _) =>
                    gscf.validateArtifact(signedLastArtifact, signedGenesis.value.info, EventTrigger, artifact).map { result =>
                      expect.same(result.isRight, true) && expect
                        .same(
                          result.map(_._1.stateChannelSnapshots(scEvent.address)),
                          Right(NonEmptyList.one(scEvent.snapshotBinary))
                        )
                    }
                }
            }
          }
        }
      }
    }
  }

  test("validateArtifact - returns invalid artifact error for incorrect data") { res =>
    implicit val (_, ks, sp, m) = res

    val gscf = mkGlobalSnapshotConsensusFunctions()

    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value).flatMap { lastArtifact =>
          Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair).flatMap { signedLastArtifact =>
            mkStateChannelEvent().flatMap { scEvent =>
              gscf
                .createProposalArtifact(
                  SnapshotOrdinal.MinValue,
                  signedLastArtifact,
                  signedGenesis.value.info,
                  EventTrigger,
                  Set(scEvent.asLeft[DAGEvent])
                )
                .flatMap { proposalArtifact =>
                  gscf
                    .validateArtifact(
                      signedLastArtifact,
                      signedGenesis.value.info,
                      EventTrigger,
                      proposalArtifact._1.copy(ordinal = proposalArtifact._1.ordinal.next)
                    )
                    .map { result =>
                      expect.same(result.isLeft, true)
                    }
                }
            }
          }
        }
      }
    }
  }

  def mkStateChannelEvent()(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)
    signedSC <- forAsyncKryo(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
