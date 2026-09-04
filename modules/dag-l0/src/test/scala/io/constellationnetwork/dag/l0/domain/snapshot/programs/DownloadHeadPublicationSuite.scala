package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.{IO, Ref}

import weaver.SimpleIOSuite

object DownloadHeadPublicationSuite extends SimpleIOSuite {

  private final case class TerminalArtifact(id: String)
  private final case class TerminalContext(id: String)
  private final case class Step(name: String, artifact: TerminalArtifact, context: TerminalContext)

  test("validated terminal publication precedes metrics and consensus initialization with one exact pair") {
    val terminalArtifact = TerminalArtifact("observed-T")
    val terminalContext = TerminalContext("context-T")

    for {
      steps <- Ref.of[IO, List[Step]](List.empty)
      _ <- Download.publishValidatedHeadBeforeConsensus[IO, TerminalArtifact, TerminalContext](
        terminalArtifact,
        terminalContext,
        (artifact, context) => steps.update(_ :+ Step("publish", artifact, context)),
        (artifact, context) => steps.update(_ :+ Step("published-metric", artifact, context)),
        (artifact, context) => steps.update(_ :+ Step("consensus-init", artifact, context))
      )
      observed <- steps.get
    } yield
      expect.same(
        List(
          Step("publish", terminalArtifact, terminalContext),
          Step("published-metric", terminalArtifact, terminalContext),
          Step("consensus-init", terminalArtifact, terminalContext)
        ),
        observed
      )
  }

  test("terminal publication failure never evaluates metrics or consensus initialization") {
    val terminalArtifact = TerminalArtifact("observed-T")
    val terminalContext = TerminalContext("context-T")

    for {
      steps <- Ref.of[IO, List[Step]](List.empty)
      result <- Download
        .publishValidatedHeadBeforeConsensus[IO, TerminalArtifact, TerminalContext](
          terminalArtifact,
          terminalContext,
          (artifact, context) =>
            steps.update(_ :+ Step("publish", artifact, context)) >>
              IO.raiseError(new RuntimeException("injected publication failure")),
          (artifact, context) => steps.update(_ :+ Step("published-metric", artifact, context)),
          (artifact, context) => steps.update(_ :+ Step("consensus-init", artifact, context))
        )
        .attempt
      observed <- steps.get
    } yield
      expect(result.isLeft) &&
        expect.same(List(Step("publish", terminalArtifact, terminalContext)), observed)
  }
}
