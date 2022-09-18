package org.tessellation.sdk.infrastructure.gossip

import cats.data.Chain
import cats.effect.{Async, Ref}
import cats.syntax.all._

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.RumorStorageConfig
import org.tessellation.sdk.infrastructure.gossip.RumorStorage.AddResult
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{order, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef

trait RumorStorage[F[_]] {

  def getPeerRumorHeadCounters: F[Map[(PeerId, Generation), Counter]]

  def getPeerRumors(peerIdAndGen: (PeerId, Generation))(from: Counter): F[Iterator[Signed[PeerRumorRaw]]]

  def addPeerRumorIfConsecutive(rumor: Signed[PeerRumorRaw]): F[AddResult]

  def setInitialPeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit]

  def getCommonRumorActiveHashes: F[Set[Hash]]

  def getCommonRumorSeenHashes: F[Set[Hash]]

  def getCommonRumors(hashes: Set[Hash]): F[Iterator[Signed[CommonRumorRaw]]]

  def addCommonRumorIfUnseen(rumor: Hashed[CommonRumorRaw]): F[Boolean]

  def setInitialCommonRumorSeenHashes(hashes: Set[Hash]): F[Unit]

}

object RumorStorage {

  @derive(order, show)
  sealed trait AddResult
  case object AddSuccess extends AddResult
  case object CounterTooHigh extends AddResult
  case object CounterTooLow extends AddResult

  case class ChainWrapper[A](chain: Chain[A], size: NonNegLong)

  object ChainWrapper {
    def one[A](a: A): ChainWrapper[A] = ChainWrapper(Chain.one(a), 1L)
    def empty[A]: ChainWrapper[A] = ChainWrapper(Chain.empty, 0L)
  }

  implicit class ChainWrapperOps[A](value: ChainWrapper[A]) {
    def prependInitLast(a: A, n: NonNegLong): (ChainWrapper[A], Option[A]) =
      if (value.size < n)
        (ChainWrapper(value.chain.prepend(a), value.size |+| 1L), none[A])
      else {
        value.chain.initLast match {
          case Some((init, last)) =>
            (ChainWrapper(init.prepend(a), value.size), last.some)
          case None =>
            if (n === 0L)
              (ChainWrapper.empty, none)
            else
              (ChainWrapper.one(a), none)
        }
      }

    def prependInit(a: A, n: NonNegLong): ChainWrapper[A] =
      prependInitLast(a, n)._1
  }

  case class CommonRumors(
    seenChain: ChainWrapper[Hash],
    seenSet: Set[Hash],
    activeChain: ChainWrapper[Hashed[CommonRumorRaw]],
    activeSet: Set[Hash]
  )

  object CommonRumors {
    val empty: CommonRumors = CommonRumors(ChainWrapper.empty, Set.empty, ChainWrapper.empty, Set.empty)
  }

  def make[F[_]: Async](cfg: RumorStorageConfig): F[RumorStorage[F]] =
    for {
      peerRumorsR <- MapRef.ofConcurrentHashMap[F, (PeerId, Generation), ChainWrapper[Signed[PeerRumorRaw]]]()
      commonRumorsR <- Ref.of(CommonRumors.empty)
    } yield
      new RumorStorage[F] {
        def getPeerRumorHeadCounters: F[Map[(PeerId, Generation), Counter]] = peerRumorsR.keys.flatMap { keys =>
          keys.flatTraverse { key =>
            peerRumorsR(key).get.map(_.flatMap(_.chain.headOption).map(key -> _.ordinal.counter).toList)
          }.map(_.toMap)
        }

        def getPeerRumors(key: (PeerId, Generation))(from: Counter): F[Iterator[Signed[PeerRumorRaw]]] =
          peerRumorsR(key).get.map { maybeWrapper =>
            maybeWrapper.map { wrapper =>
              wrapper.chain.takeWhile(_.ordinal.counter >= from)
            }.filter(_.lastOption.exists(_.ordinal.counter === from))
              .map(_.reverseIterator)
              .getOrElse(Iterator.empty[Signed[PeerRumorRaw]])
          }

        def addPeerRumorIfConsecutive(rumor: Signed[PeerRumorRaw]): F[AddResult] =
          peerRumorsR((rumor.origin, rumor.ordinal.generation)).modify { maybeWrapper =>
            maybeWrapper.flatMap { wrapper =>
              wrapper.chain.headOption.map { head =>
                if (rumor.ordinal.counter === head.ordinal.counter.next)
                  (wrapper.prependInit(rumor, cfg.peerRumorsCapacity).some, AddSuccess)
                else if (rumor.ordinal.counter > head.ordinal.counter.next)
                  (wrapper.some, CounterTooHigh)
                else
                  (wrapper.some, CounterTooLow)
              }
            }.getOrElse {
              if (rumor.ordinal.counter === Counter.MinValue)
                (ChainWrapper.one(rumor).some, AddSuccess)
              else
                (none, CounterTooHigh)
            }
          }

        def setInitialPeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit] =
          peerRumorsR((rumor.origin, rumor.ordinal.generation)).set(ChainWrapper.one(rumor).some)

        def getCommonRumorActiveHashes: F[Set[Hash]] =
          commonRumorsR.get.map(_.activeSet)

        def getCommonRumorSeenHashes: F[Set[Hash]] =
          commonRumorsR.get.map(_.seenSet)

        def getCommonRumors(hashes: Set[Hash]): F[Iterator[Signed[CommonRumorRaw]]] =
          commonRumorsR.get.map { commonRumors =>
            commonRumors.activeChain.chain.filter(h => hashes.contains(h.hash)).reverseIterator.map(_.signed)
          }

        override def addCommonRumorIfUnseen(rumor: Hashed[CommonRumorRaw]): F[Boolean] =
          commonRumorsR.modify {
            case o @ CommonRumors(seenChain, seenSet, activeChain, activeSet) =>
              if (seenSet.contains(rumor.hash))
                (o, false)
              else {
                val (seenChainUpdated, last) = seenChain.prependInitLast(rumor.hash, cfg.seenCommonRumorsCapacity)
                val seenSetUpdated = last.map(h => seenSet.excl(h)).getOrElse(seenSet).incl(rumor.hash)

                val (activeChainUpdated, removedRumor) = activeChain.prependInitLast(rumor, cfg.activeCommonRumorsCapacity)
                val activeSetUpdated = removedRumor.map(r => activeSet.excl(r.hash)).getOrElse(activeSet).incl(rumor.hash)
                (
                  CommonRumors(
                    seenChain = seenChainUpdated,
                    seenSet = seenSetUpdated,
                    activeChain = activeChainUpdated,
                    activeSet = activeSetUpdated
                  ),
                  true
                )
              }
          }

        def setInitialCommonRumorSeenHashes(hashes: Set[Hash]): F[Unit] =
          commonRumorsR.set(
            CommonRumors(
              seenChain = ChainWrapper(Chain.fromSeq(hashes.toSeq), NonNegLong.unsafeFrom(hashes.size.toLong)),
              seenSet = hashes,
              activeChain = ChainWrapper.empty,
              activeSet = Set.empty
            )
          )
      }

}
