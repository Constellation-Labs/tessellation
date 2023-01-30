package org.tessellation.dag.snapshot

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver._
import weaver.scalacheck.Checkers

object GlobalSnapshotTraverseSuite extends MutableIOSuite with Checkers {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sharedKryoRegistrar)).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  val balances: Map[Address, Balance] = Map(Address("DAG8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC") -> Balance(NonNegLong(10L)))

  def mkSnapshot(reference: IncrementalGlobalSnapshot, keyPair: KeyPair)(implicit K: KryoSerializer[IO], S: SecurityProvider[IO]) = {
    def snapshot =
      IncrementalGlobalSnapshot(
        reference.ordinal.next,
        Height.MinValue,
        SubHeight.MinValue,
        reference.hash.toOption.get,
        SortedSet.empty,
        SortedMap.empty,
        SortedSet.empty,
        reference.epochProgress,
        NonEmptyList.of(PeerId(Hex("peer1"))),
        reference.tips
      )

    Signed.forAsyncKryo[IO, IncrementalGlobalSnapshot](snapshot, keyPair).flatMap(_.toHashed)
  }

  def mkSnapshots(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Hashed[GlobalSnapshot], Hashed[IncrementalGlobalSnapshot], Hashed[IncrementalGlobalSnapshot])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed
        .forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(balances, EpochProgress.MinValue), keyPair)
        .flatMap(_.toHashed)
        .flatMap { genesis =>
          mkSnapshot(genesis.toIncrementalSnapshot, keyPair).flatMap { snap1 =>
            mkSnapshot(snap1.signed.value, keyPair).map((genesis, snap1, _))
          }
        }
    }

  def gst(snapshots: (Hashed[GlobalSnapshot], Hashed[IncrementalGlobalSnapshot], Hashed[IncrementalGlobalSnapshot])) = {
    def loadGlobalSnapshot(hash: Hash): IO[Either[GlobalSnapshot, IncrementalGlobalSnapshot]] =
      hash match {
        case h if h === snapshots._3.hash => Right(snapshots._3.signed.value).pure[IO]
        case h if h === snapshots._2.hash => Right(snapshots._2.signed.value).pure[IO]
        case _                            => Left(snapshots._1.signed.value).pure[IO]
      }

    GlobalSnapshotTraverse.make(loadGlobalSnapshot)
  }

  test("can compute state for given incremental global snapshot") { res =>
    implicit val (kryo, sp) = res

    mkSnapshots.flatMap { snapshots =>
      gst(snapshots).computeState(snapshots._3)
    }.map(state => expect.eql(state, GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.from(balances))))
  }

}
