package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._

import weaver.SimpleIOSuite

object ProposalCertificateEnvelopeSuite extends SimpleIOSuite {

  test("retained proposal effect replays the exact pre-commit capped certificate envelope") {
    for {
      assembledEvictions <- Ref.of[IO, Set[String]](Set("ecs-c", "ecs-a", "ecs-b"))
      assembledAdmissions <- Ref.of[IO, Set[String]](Set("acs-c", "acs-a", "acs-b"))
      evictionReads <- Ref.of[IO, Int](0)
      admissionReads <- Ref.of[IO, Int](0)
      emitted <- Ref.of[IO, Vector[ProposalCertificateEnvelope.Captured[String, String]]](Vector.empty)
      retained <- ProposalCertificateEnvelope.captureRetainedEffect[IO, String, String](
        loadEvictionCertificates = evictionReads.update(_ + 1) >> assembledEvictions.get,
        selectEvictionCertificates = (assembled: Set[String]) => assembled.toList.sorted.take(2).pure[IO],
        loadAdmissionCertificates = admissionReads.update(_ + 1) >> assembledAdmissions.get,
        selectAdmissionCertificates = (assembled: Set[String]) => assembled.toList.sorted.take(1).pure[IO]
      )(captured => emitted.update(_ :+ captured))
      readsAfterConstruction <- (evictionReads.get, admissionReads.get).tupled
      _ <- assembledEvictions.set(Set("ecs-new"))
      _ <- assembledAdmissions.set(Set("acs-new"))
      _ <- retained >> retained
      readsAfterReplay <- (evictionReads.get, admissionReads.get).tupled
      proposals <- emitted.get
      expected = ProposalCertificateEnvelope.Captured(List("ecs-a", "ecs-b"), List("acs-a"))
    } yield expect.all(
      readsAfterConstruction == (1 -> 1),
      readsAfterReplay == readsAfterConstruction,
      proposals == Vector(expected, expected)
    )
  }

  test("a failed first emission retries the captured envelope without consulting changed assembly storage") {
    for {
      assembledEvictions <- Ref.of[IO, Set[String]](Set("ecs-original"))
      assembledAdmissions <- Ref.of[IO, Set[String]](Set("acs-original"))
      attempts <- Ref.of[IO, Int](0)
      observed <- Ref.of[IO, Vector[ProposalCertificateEnvelope.Captured[String, String]]](Vector.empty)
      retained <- ProposalCertificateEnvelope.captureRetainedEffect[IO, String, String](
        loadEvictionCertificates = assembledEvictions.get,
        selectEvictionCertificates = (assembled: Set[String]) => assembled.toList.pure[IO],
        loadAdmissionCertificates = assembledAdmissions.get,
        selectAdmissionCertificates = (assembled: Set[String]) => assembled.toList.pure[IO]
      ) { captured =>
        observed.update(_ :+ captured) >> attempts.getAndUpdate(_ + 1).flatMap {
          case 0 => new RuntimeException("first transport failed").raiseError[IO, Unit]
          case _ => IO.unit
        }
      }
      _ <- retained.attempt
      _ <- assembledEvictions.set(Set("ecs-late"))
      _ <- assembledAdmissions.set(Set("acs-late"))
      _ <- retained
      proposals <- observed.get
      expected = ProposalCertificateEnvelope.Captured(List("ecs-original"), List("acs-original"))
    } yield expect(proposals == Vector(expected, expected))
  }

  test("exact proposal delivery self-stores before transport and replays the same value") {
    for {
      steps <- Ref.of[IO, Vector[(String, String)]](Vector.empty)
      attempt <- Ref.of[IO, Int](0)
      effect = ProposalCertificateEnvelope.exactProposalEffect[IO, String, String](
        proposal = "proposal-a",
        declaration = "declaration-a"
      )(
        proposal => steps.update(_ :+ ("store" -> proposal)),
        declaration =>
          steps.update(_ :+ ("deliver" -> declaration)) >> attempt.getAndUpdate(_ + 1).flatMap {
            case 0 => new RuntimeException("transport failed").raiseError[IO, Unit]
            case _ => IO.unit
          }
      )
      _ <- effect.attempt
      _ <- effect
      observed <- steps.get
    } yield expect(
      observed == Vector(
        "store" -> "proposal-a",
        "deliver" -> "declaration-a",
        "store" -> "proposal-a",
        "deliver" -> "declaration-a"
      )
    )
  }
}
