// This file is derived from [sbt-ci-release](https://github.com/sbt/sbt-ci-release)
//
// The original project is licensed under the Apache License, Version 2.0, a copy of which
// is included in the LICENSE file in this repository. You may also obtain a copy at:
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// This version has been significantly modified from the original, with portions removed or changed.

import com.github.sbt.git.GitPlugin
import com.jsuereth.sbtpgp.SbtPgp
import sbt.Def
import sbt.Keys.*
import sbt.*
import sbt.plugins.JvmPlugin
import java.io.ByteArrayInputStream
import scala.sys.process.{ProcessBuilder, ProcessLogger}
import scala.util.{Failure, Success, Try}
import scala.sys.process.*
import scala.util.control.NonFatal
import xerial.sbt.Sonatype
import xerial.sbt.Sonatype.autoImport.*

object TessellationCiRelease extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = JvmPlugin && SbtPgp && GitPlugin && Sonatype

  def isSecure: Boolean = System.getenv("PGP_SECRET") != null
  def isTag: Boolean = Option(System.getenv("RELEASE_TAG")).exists(_.nonEmpty) ||
    Option(System.getenv("GITHUB_REF")).exists(_.startsWith("refs/tags"))
  def currentBranch: String = Option(System.getenv("GITHUB_REF")).getOrElse("<unknown>")

  def setupGpg(): Unit = {
    val versionLine = List("gpg", "--version").!!.linesIterator.toList.head
    println(versionLine)
    val TaggedVersion = """(\d{1,14})([\.\d{1,14}]*)((?:-\w+)*)""".r
    val gpgVersion: Long = versionLine.split(" ").last match {
      case TaggedVersion(m, _, _) => m.toLong
      case _                      => 0L
    }
    // https://dev.gnupg.org/T2313
    val importCommand =
      if (gpgVersion < 2L) "--import"
      else "--batch --import"
    val secret = sys.env("PGP_SECRET")

    (Process(s"echo $secret") #|!
      Process("base64 --decode") #|!
      Process(s"gpg $importCommand")).!
  }

  private def gitHubScmInfo(user: String, repo: String) =
    ScmInfo(
      url(s"https://github.com/$user/$repo"),
      s"scm:git:https://github.com/$user/$repo.git",
      Some(s"scm:git:git@github.com:$user/$repo.git")
    )

  override lazy val buildSettings: Seq[Def.Setting[_]] = List(
    scmInfo ~= {
      case Some(info) => Some(info)
      case None =>
        import scala.sys.process._
        val identifier = """([^\/]+?)"""
        val GitHubHttps =
          s"https://github.com/$identifier/$identifier(?:\\.git)?".r
        val GitHubGit = s"git://github.com:$identifier/$identifier(?:\\.git)?".r
        val GitHubSsh = s"git@github.com:$identifier/$identifier(?:\\.git)?".r
        try {
          val remote = List("git", "ls-remote", "--get-url", "origin").!!.trim()
          remote match {
            case GitHubHttps(user, repo) => Some(gitHubScmInfo(user, repo))
            case GitHubGit(user, repo)   => Some(gitHubScmInfo(user, repo))
            case GitHubSsh(user, repo)   => Some(gitHubScmInfo(user, repo))
            case _                       => None
          }
        } catch {
          case NonFatal(_) => None
        }
    }
  )

  override lazy val globalSettings: Seq[Def.Setting[_]] = List(
    (Test / publishArtifact) := false,
    commands += Command.command("tessellation-ci-release") { currentState =>
      val version = getVersion(currentState)
      val isSnapshot = isSnapshotVersion(version)
      if (!isSecure) {
        println("No access to secret variables, doing nothing")
        currentState
      } else {
        println(
          s"Running ci-release.\n" +
            s"  branch=$currentBranch"
        )
        setupGpg()
        // https://github.com/olafurpg/sbt-ci-release/issues/64
        val reloadKeyFiles = "; set pgpSecretRing := pgpSecretRing.value; set pgpPublicRing := pgpPublicRing.value"
        val publishCommand = "+publishSigned"

        if (isSnapshot) {
          println(s"Sonatype Central does not accept snapshots, only official releases. Aborting release.")
          currentState
        } else if (!isTag) {
          println(
            s"No tag published. Cannot publish an official release without a tag and Sonatype Central does not accept snapshot releases. Aborting release."
          )
          currentState
        } else {
          println("Tag push detected, publishing a stable release")
          reloadKeyFiles ::
            sys.env.getOrElse("CI_CLEAN", "; clean ; sonatypeBundleClean") ::
            publishCommand ::
            sys.env.getOrElse("CI_SONATYPE_RELEASE", "sonatypeCentralRelease") ::
            currentState
        }
      }
    }
  )

  override lazy val projectSettings: Seq[Def.Setting[_]] = List(
    version := (ThisBuild / version).value,
    publishConfiguration := publishConfiguration.value.withOverwrite(true),
    publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true),
    publishTo := sonatypePublishToBundle.value
  )

  private def getVersion(state: State): String =
    (ThisBuild / version).get(Project.extract(state).structure.data) match {
      case Some(v) => v
      case None    => throw new NoSuchFieldError("version")
    }

  private def isSnapshotVersion(v: String): Boolean = v.contains("-SNAPSHOT")

  private implicit class PipeFailOps[R](p1: R) {
    @volatile
    private var error: Option[String] = None

    def #|!(p2: R)(implicit ev: R => ProcessBuilder): ProcessBuilder = {
      val logger = new ProcessLogger {
        override def out(s: => String): Unit = ()

        override def err(s: => String): Unit =
          error = Some(s)

        override def buffer[T](f: => T): T = f
      }

      Try(p1.!!(logger)).map(result => p2 #< new ByteArrayInputStream(result.getBytes)) match {
        case Failure(ex) =>
          error match {
            case Some(errorMessageFromPipe) =>
              throw new RuntimeException(errorMessageFromPipe, ex)
            case None => throw ex
          }
        case Success(value) => value
      }
    }
  }
}
