package org.tessellation.hypergraph

import cats.effect.Concurrent
import higherkindness.mu.rpc.protocol.{Protobuf, service}
import org.tessellation.schema.Ω

import scala.collection.mutable
import scala.collection.mutable.HashMap
import fs2.Stream

/**
 * Service definition for Edge creation over streams
 */
object EdgePartitioner {
  trait EdgePartitioner[F[_]] {
    //todo lock for cache
    val refCache = mutable.HashMap[HyperEdge, Seq[HyperEdge]]()

    /**
     * Server streaming RPC
     *
     * @param request Single client request.
     * @return Stream of server responses.
     */
    def sinkTo(request: Ω): F[Stream[F, Ω]]

    /**
     * Client streaming RPC
     *
     * @param request Stream of client requests.
     * @return Single server response.
     */
    def subscribeTo(request: Stream[F, Ω]): F[Ω]

    /**
     * Bidirectional streaming RPC
     *
     * @param request Stream of client requests.
     * @return Stream of server responses.
     */
    def biDirectional(request: Stream[F, Ω]): F[Stream[F, Ω]]

  }
}

