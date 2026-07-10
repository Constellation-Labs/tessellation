package io.constellationnetwork.node.shared.logger.sink.clickhouse

import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.logger.{ConsensusLogger, LogContext}
import io.constellationnetwork.schema.peer.PeerId

import com.zaxxer.hikari.HikariDataSource
import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Specialized consensus logger that writes to its own ClickHouse table. Optimized schema for consensus events with ordinal-based indexing.
  */
object ClickHouseConsensusLogger {

  sealed trait ConsensusEvent {
    def eventType: String
    def ordinal: Option[Long]
    def facilitators: List[String]
  }

  private object ConsensusEvent {
    case class CollectingFacilities(ordinal: Option[Long], facilitators: List[String]) extends ConsensusEvent {
      val eventType = "COLLECTING_FACILITIES"
    }
    case class CollectingProposals(ordinal: Option[Long], facilitators: List[String]) extends ConsensusEvent {
      val eventType = "COLLECTING_PROPOSALS"
    }
    case class CollectingSignatures(ordinal: Option[Long], facilitators: List[String]) extends ConsensusEvent {
      val eventType = "COLLECTING_SIGNATURES"
    }
    case class RoundFinished(ordinal: Option[Long], facilitators: List[String]) extends ConsensusEvent {
      val eventType = "ROUND_FINISHED"
    }
  }

  private val FlushInterval = 5.seconds
  private val BatchSize = 100

  val createTableDDL: String => String = tableName => s"""CREATE TABLE IF NOT EXISTS $tableName (
       |    timestamp DateTime64(3),
       |    node_id LowCardinality(String),
       |    network_id LowCardinality(String),
       |    ordinal UInt64,
       |    event_type LowCardinality(String),
       |    facilitators Array(String),
       |    INDEX idx_ordinal ordinal TYPE minmax GRANULARITY 1,
       |    INDEX idx_event_type event_type TYPE set(20) GRANULARITY 1
       |) ENGINE = MergeTree()
       |PARTITION BY (network_id, toYYYYMM(timestamp))
       |ORDER BY (node_id, ordinal, timestamp)
       |TTL toDateTime(timestamp) + INTERVAL 90 DAY""".stripMargin

  def make[F[_]: Async](
    ds: HikariDataSource,
    tableName: String,
    nodeId: PeerId,
    networkId: AppEnvironment,
    logger: SelfAwareStructuredLogger[F],
    maxQueueSize: Int,
    ctxRef: Ref[F, LogContext]
  ): Resource[F, ConsensusLogger[F]] =
    for {
      queue <- Resource.eval(Queue.bounded[F, ConsensusEvent](maxQueueSize))
      writer = new BatchWriter[F](ds, tableName, nodeId, networkId, logger)
      _ <- startFlusher(queue, writer)
    } yield new Impl[F](queue, ctxRef)

  private def startFlusher[F[_]: Async](
    queue: Queue[F, ConsensusEvent],
    writer: BatchWriter[F]
  ): Resource[F, Unit] = {
    val flush: F[Unit] = for {
      _ <- Temporal[F].sleep(FlushInterval)
      entries <- queue.tryTakeN(Some(BatchSize))
      _ <- writer.write(entries).whenA(entries.nonEmpty)
    } yield ()

    Spawn[F].background(flush.foreverM).void
  }

  private class BatchWriter[F[_]](
    ds: HikariDataSource,
    tableName: String,
    nodeId: PeerId,
    networkId: AppEnvironment,
    logger: SelfAwareStructuredLogger[F]
  )(implicit F: Async[F]) {

    private val insertSql =
      s"INSERT INTO $tableName (timestamp, node_id, network_id, event_type, facilitators, ordinal) VALUES (now64(3), ?, ?, ?, ?, ?)"

    def write(entries: List[ConsensusEvent]): F[Unit] =
      Resource
        .make(F.blocking(ds.getConnection))(c => F.blocking(c.close()).handleError(_ => ()))
        .flatMap(conn => Resource.make(F.blocking(conn.prepareStatement(insertSql)))(s => F.blocking(s.close()).handleError(_ => ())))
        .use { stmt =>
          F.blocking {
            entries.foreach { e =>
              stmt.setString(1, nodeId.value.value)
              stmt.setString(2, networkId.toString)
              stmt.setString(3, e.eventType)
              stmt.setArray(4, stmt.getConnection.createArrayOf("String", e.facilitators.toArray))
              e.ordinal match {
                case Some(o) => stmt.setLong(5, o)
                case None    => stmt.setNull(5, java.sql.Types.BIGINT)
              }
              stmt.addBatch()
            }
            stmt.executeBatch()
          }
        }
        .void
        .handleErrorWith(e => logger.warn(s"Failed to write ${entries.size} consensus logs: ${e.getMessage}"))
  }

  private class Impl[F[_]: Async](
    queue: Queue[F, ConsensusEvent],
    ctxRef: Ref[F, LogContext]
  ) extends ConsensusLogger[F] {
    import ConsensusEvent._

    private def enqueue(mk: Option[Long] => ConsensusEvent): F[Unit] =
      for {
        ctx <- ctxRef.get
        ordinal = ctx.ordinal.map(_.value.value)
        _ <- queue.tryOffer(mk(ordinal))
      } yield ()

    private def ids(ps: List[PeerId]): List[String] = ps.map(_.value.value)

    def collectingFacilities(fs: List[PeerId]): F[Unit] = enqueue(o => CollectingFacilities(o, ids(fs)))
    def collectingProposals(fs: List[PeerId]): F[Unit] = enqueue(o => CollectingProposals(o, ids(fs)))
    def collectingSignatures(fs: List[PeerId]): F[Unit] = enqueue(o => CollectingSignatures(o, ids(fs)))
    def roundFinished(fs: List[PeerId]): F[Unit] = enqueue(o => RoundFinished(o, ids(fs)))
  }
}
