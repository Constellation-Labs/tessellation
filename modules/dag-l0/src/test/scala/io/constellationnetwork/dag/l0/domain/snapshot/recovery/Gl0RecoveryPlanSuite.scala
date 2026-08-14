package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import java.nio.charset.StandardCharsets
import java.nio.file.{Files => JFiles}

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import fs2.io.file.{Files, Path}
import io.circe.syntax._
import weaver.MutableIOSuite

object Gl0RecoveryPlanSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    JsonSerializer.forAsync[IO].asResource.flatMap { implicit json =>
      SecurityProvider.forAsync[IO].map((Hasher.forJson[IO], _))
    }

  private val network = "Integrationnet"
  private val ordinal = SnapshotOrdinal.unsafeApply(5884500L)
  private val anchorHash = Hash("a" * 64)
  private val planId = Hash("b" * 64)

  private def temporaryFile(content: String): Resource[IO, Path] =
    Resource
      .make(IO.blocking(JFiles.createTempFile("gl0-recovery-plan-", ".json")))(path => IO.blocking(JFiles.deleteIfExists(path)).void)
      .evalTap(path => IO.blocking(JFiles.write(path, content.getBytes(StandardCharsets.UTF_8))).void)
      .map(Path.fromNioPath)

  private def plan(
    lead: PeerId,
    committee: SortedSet[PeerId],
    protocol: String = Gl0RecoveryPlan.CurrentProtocol,
    version: Int = Gl0RecoveryPlan.CurrentFormatVersion
  ) =
    Gl0RecoveryPlan(
      protocol,
      version,
      planId,
      RecoveryCheckpoint(network, ordinal, anchorHash),
      lead,
      committee
    )

  private def verify(
    signed: Signed[Gl0RecoveryPlan],
    lead: PeerId,
    seedlist: Set[PeerId],
    allowanceList: Option[Set[PeerId]] = None,
    maxFacilitators: Option[Int] = None,
    quorumThresholdFraction: Double = 2.0 / 3.0
  )(implicit hasher: Hasher[IO], securityProvider: SecurityProvider[IO]) =
    Gl0RecoveryPlan.verify(
      SignedValidator.make[IO],
      network,
      lead,
      anchorHash,
      seedlist,
      allowanceList,
      maxFacilitators,
      quorumThresholdFraction,
      signed
    )

  test("a canonical lead-signed plan is accepted") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers))
      signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head)
      result <- verify(signed, peers.head, peers.toSet, Some(peers.toSet), Some(3))
    } yield expect.same(Right(value), result)
  }

  test("committee ordering is canonical in both equality and hashing") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      first = plan(peers.head, SortedSet.from(peers))
      second = plan(peers.head, SortedSet.from(peers.reverse))
      firstHash <- hasher.hash(first)
      secondHash <- hasher.hash(second)
    } yield expect.same(first, second) && expect.same(firstHash, secondHash)
  }

  test("a plan signed by anyone other than its designated lead is rejected") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers))
      signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys(1))
      result <- verify(signed, peers.head, peers.toSet)
    } yield expect(result.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidSignatures]))
  }

  test("the signed domain protocol is checked exactly") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(2)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers), protocol = "some-other-signed-document-v1")
      signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head)
      result <- verify(signed, peers.head, peers.toSet)
    } yield expect(result.left.exists(_.isInstanceOf[Gl0RecoveryPlan.UnsupportedProtocol]))
  }

  test("format, network, lead, and rollback-anchor bindings fail closed") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(2)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      committee = SortedSet.from(peers)
      wrongFormat = plan(peers.head, committee, version = Gl0RecoveryPlan.CurrentFormatVersion + 1)
      wrongFormatSigned <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](wrongFormat, keys.head)
      wrongFormatResult <- verify(wrongFormatSigned, peers.head, peers.toSet)
      wrongNetwork = plan(peers.head, committee).copy(anchor = RecoveryCheckpoint("mainnet", ordinal, anchorHash))
      wrongNetworkSigned <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](wrongNetwork, keys.head)
      wrongNetworkResult <- verify(wrongNetworkSigned, peers.head, peers.toSet)
      valid = plan(peers.head, committee)
      validSigned <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](valid, keys.head)
      wrongLeadResult <- verify(validSigned, peers(1), peers.toSet)
      wrongAnchor = valid.copy(anchor = valid.anchor.copy(snapshotHash = Hash("c" * 64)))
      wrongAnchorSigned <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](wrongAnchor, keys.head)
      wrongAnchorResult <- verify(wrongAnchorSigned, peers.head, peers.toSet)
    } yield
      expect(wrongFormatResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.UnsupportedFormatVersion])) &&
        expect(wrongNetworkResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.NetworkMismatch])) &&
        expect(wrongLeadResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.LeadMismatch])) &&
        expect(wrongAnchorResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.RollbackHashMismatch]))
  }

  test("a plan with any signer in addition to the lead is rejected") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(2)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers))
      signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head).flatMap(_.signAlsoWith(keys(1)))
      result <- verify(signed, peers.head, peers.toSet)
    } yield expect(result.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidSignatures]))
  }

  test("a plan cannot introduce peers outside either configured membership boundary") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers))
      signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head)
      seedlistResult <- verify(signed, peers.head, peers.take(2).toSet)
      allowanceResult <- verify(signed, peers.head, peers.toSet, Some(peers.take(2).toSet))
    } yield
      expect(seedlistResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidCommittee])) &&
        expect(allowanceResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidCommittee]))
  }

  test("a singleton or selector-truncated recovery committee is rejected") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      singleton = plan(peers.head, SortedSet(peers.head))
      singletonSigned <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](singleton, keys.head)
      singletonResult <- verify(singletonSigned, peers.head, peers.toSet)
      full = plan(peers.head, SortedSet.from(peers))
      fullSigned <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](full, keys.head)
      truncatedResult <- verify(fullSigned, peers.head, peers.toSet, maxFacilitators = Some(2))
    } yield
      expect(singletonResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidCommittee])) &&
        expect(truncatedResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidCommittee]))
  }

  test("a recovery committee that cannot certify its next seat is rejected") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers))
      signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head)
      unanimous <- verify(
        signed,
        peers.head,
        peers.toSet,
        quorumThresholdFraction = 1.0
      )
      supermajority <- verify(
        signed,
        peers.head,
        peers.toSet,
        quorumThresholdFraction = 2.0 / 3.0
      )
    } yield
      expect(unanimous.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidCommittee])) &&
        expect(supermajority.isRight)
  }

  test("v1 accepts a six-member committee when every planned member participates in the all-member gate") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(6)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      six = plan(peers.head, SortedSet.from(peers))
      sixSigned <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](six, keys.head)
      sixResult <- verify(sixSigned, peers.head, peers.toSet, maxFacilitators = Some(6))
    } yield expect(sixResult.isRight)
  }

  test("planId must be canonical lowercase hex before it can become a receipt filename") { res =>
    implicit val (hasher, securityProvider) = res
    for {
      keys <- List.fill(2)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      malformedIds = List(Hash("../escape"), Hash("A" * 64), Hash("a" * 63))
      results <- malformedIds.traverse { malformedId =>
        val value = plan(peers.head, SortedSet.from(peers)).copy(planId = malformedId)
        Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head).flatMap(verify(_, peers.head, peers.toSet))
      }
    } yield expect(results.forall(_.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidCommittee])))
  }

  test("the loaded snapshot must match the exact plan anchor") { res =>
    implicit val securityProvider: SecurityProvider[IO] = res._2
    for {
      keys <- List.fill(2)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers))
    } yield
      expect(Gl0RecoveryPlan.validateLoadedAnchor(value, ordinal.value.value, anchorHash).isRight) &&
        expect(
          Gl0RecoveryPlan
            .validateLoadedAnchor(value, ordinal.value.value + 1L, anchorHash)
            .left
            .exists(_.isInstanceOf[Gl0RecoveryPlan.AnchorOrdinalMismatch])
        ) &&
        expect(
          Gl0RecoveryPlan
            .validateLoadedAnchor(value, ordinal.value.value, Hash("c" * 64))
            .left
            .exists(_.isInstanceOf[Gl0RecoveryPlan.AnchorHashMismatch])
        )
  }

  test("the actual JSON file loader round-trips and re-verifies the lead signature") { res =>
    implicit val (hasher, securityProvider) = res
    implicit val files: Files[IO] = Files.forAsync[IO]

    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers.reverse))
      signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head)
      loaded <- temporaryFile(signed.asJson.noSpaces).use { path =>
        Gl0RecoveryPlanLoader.load[IO](
          path.some,
          network,
          Gl0RecoveryPlanLoader.Role.RollbackLead(peers.head, anchorHash),
          peers.toSet.some,
          peers.toSet.some,
          3.some,
          2.0 / 3.0,
          SignedValidator.make[IO]
        )
      }
    } yield expect.same(value.some, loaded.map(_.plan))
  }

  test("a named non-lead validator accepts the same plan while the designated lead cannot use validator mode") { res =>
    implicit val (hasher, securityProvider) = res
    implicit val files: Files[IO] = Files.forAsync[IO]

    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      peers = keys.map(key => PeerId.fromPublic(key.getPublic))
      value = plan(peers.head, SortedSet.from(peers))
      signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head)
      results <- temporaryFile(signed.asJson.noSpaces).use { path =>
        (
          Gl0RecoveryPlanLoader.load[IO](
            path.some,
            network,
            Gl0RecoveryPlanLoader.Role.PlannedValidator(peers(1)),
            peers.toSet.some,
            peers.toSet.some,
            3.some,
            2.0 / 3.0,
            SignedValidator.make[IO]
          ),
          Gl0RecoveryPlanLoader
            .load[IO](
              path.some,
              network,
              Gl0RecoveryPlanLoader.Role.PlannedValidator(peers.head),
              peers.toSet.some,
              peers.toSet.some,
              3.some,
              2.0 / 3.0,
              SignedValidator.make[IO]
            )
            .attempt
        ).tupled
      }
      (memberResult, leadResult) = results
    } yield
      expect(memberResult.exists(_.plan === value)) &&
        expect(leadResult.left.exists(_.isInstanceOf[Gl0RecoveryPlan.InvalidCommittee]))
  }

  test("an absent plan path is inert and does not require a seedlist") { res =>
    implicit val (hasher, securityProvider) = res
    implicit val files: Files[IO] = Files.forAsync[IO]
    val unusedLead = PeerId(io.constellationnetwork.security.hex.Hex("01" * 64))

    Gl0RecoveryPlanLoader
      .load[IO](
        None,
        network,
        Gl0RecoveryPlanLoader.Role.PlannedValidator(unusedLead),
        None,
        None,
        None,
        2.0 / 3.0,
        SignedValidator.make[IO]
      )
      .map(result => expect(result.isEmpty))
  }

  test("a configured malformed file or missing seedlist fails closed") { res =>
    implicit val (hasher, securityProvider) = res
    implicit val files: Files[IO] = Files.forAsync[IO]

    for {
      key <- KeyPairGenerator.makeKeyPair[IO]
      peer = PeerId.fromPublic(key.getPublic)
      malformed <- temporaryFile("{not-json").use { path =>
        Gl0RecoveryPlanLoader
          .load[IO](
            path.some,
            network,
            Gl0RecoveryPlanLoader.Role.RollbackLead(peer, anchorHash),
            Set(peer).some,
            None,
            None,
            2.0 / 3.0,
            SignedValidator.make[IO]
          )
          .attempt
      }
      missingSeedlist <- temporaryFile("{}").use { path =>
        Gl0RecoveryPlanLoader
          .load[IO](
            path.some,
            network,
            Gl0RecoveryPlanLoader.Role.RollbackLead(peer, anchorHash),
            None,
            None,
            None,
            2.0 / 3.0,
            SignedValidator.make[IO]
          )
          .attempt
      }
    } yield
      expect(malformed.left.exists(_.isInstanceOf[Gl0RecoveryPlanLoader.RecoveryPlanFileUnreadable])) &&
        expect(missingSeedlist.left.exists(_ == Gl0RecoveryPlanLoader.RecoveryPlanConfiguredWithoutSeedlist))
  }

  test("a consumed plan retries in-process but a fresh process and same-id different content fail closed") { res =>
    implicit val (hasher, securityProvider) = res
    JsonSerializer.forAsync[IO].flatMap { implicit serializer =>
      implicit val files: Files[IO] = Files.forAsync[IO]
      Files[IO].tempDirectory(None, "gl0-plan-receipt-", None).use { base =>
        for {
          keys <- List.fill(2)(KeyPairGenerator.makeKeyPair[IO]).sequence
          peers = keys.map(key => PeerId.fromPublic(key.getPublic))
          value = plan(peers.head, SortedSet.from(peers))
          signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](value, keys.head)
          receipt <- Gl0RecoveryPlanReceipt.make[IO](base)
          first <- receipt.consume(signed).attempt
          sameProcessRetry <- receipt.consume(signed).attempt
          changed = value.copy(committee = SortedSet(peers.head, peers(1)), anchor = value.anchor.copy(network = "different"))
          changedSigned <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](changed, keys.head)
          sameIdDifferentContent <- receipt.consume(changedSigned).attempt
          restarted <- Gl0RecoveryPlanReceipt.make[IO](base)
          freshProcessRetry <- restarted.consume(signed).attempt
        } yield
          expect.all(
            first.isRight,
            sameProcessRetry.isRight,
            sameIdDifferentContent.left.exists(_.isInstanceOf[Gl0RecoveryPlanReceipt.PlanIdReusedInProcess]),
            freshProcessRetry.left.exists(_.isInstanceOf[Gl0RecoveryPlanReceipt.AlreadyConsumed])
          )
      }
    }
  }

  test("two fresh receipt instances racing on one plan grant authority exactly once") { res =>
    implicit val (hasher, securityProvider) = res
    JsonSerializer.forAsync[IO].flatMap { implicit serializer =>
      implicit val files: Files[IO] = Files.forAsync[IO]
      Files[IO].tempDirectory(None, "gl0-plan-receipt-race-", None).use { base =>
        for {
          keys <- List.fill(2)(KeyPairGenerator.makeKeyPair[IO]).sequence
          peers = keys.map(key => PeerId.fromPublic(key.getPublic))
          signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](plan(peers.head, SortedSet.from(peers)), keys.head)
          first <- Gl0RecoveryPlanReceipt.make[IO](base)
          second <- Gl0RecoveryPlanReceipt.make[IO](base)
          results <- (first.consume(signed).attempt, second.consume(signed).attempt).parTupled
          successes = List(results._1, results._2).count(_.isRight)
          consumed = List(results._1, results._2).count(_.left.exists(_.isInstanceOf[Gl0RecoveryPlanReceipt.AlreadyConsumed]))
        } yield expect.same(1, successes) && expect.same(1, consumed)
      }
    }
  }

  test("an empty partial receipt is conservative consumed authority, not a reusable plan") { res =>
    implicit val (hasher, securityProvider) = res
    JsonSerializer.forAsync[IO].flatMap { implicit serializer =>
      implicit val files: Files[IO] = Files.forAsync[IO]
      Files[IO].tempDirectory(None, "gl0-plan-partial-receipt-", None).use { base =>
        for {
          keys <- List.fill(2)(KeyPairGenerator.makeKeyPair[IO]).sequence
          peers = keys.map(key => PeerId.fromPublic(key.getPublic))
          signed <- Signed.forAsyncHasher[IO, Gl0RecoveryPlan](plan(peers.head, SortedSet.from(peers)), keys.head)
          _ <- Files[IO].createFile(base / s"${signed.value.planId.value}.consumed")
          receipt <- Gl0RecoveryPlanReceipt.make[IO](base)
          result <- receipt.consume(signed).attempt
        } yield expect(result.left.exists(_.isInstanceOf[Gl0RecoveryPlanReceipt.AlreadyConsumed]))
      }
    }
  }
}
