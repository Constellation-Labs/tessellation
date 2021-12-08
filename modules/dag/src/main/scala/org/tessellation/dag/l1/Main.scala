package org.tessellation.dag.l1

import cats.effect.std.{Queue, Random}
import cats.effect.{IO, Resource}

import org.tessellation.ConstellationApp
import org.tessellation.dag.l1.storage.BlockStorage.PulledTips
import org.tessellation.dag.l1.storage.{BlockStorage, ConsensusStorage, TransactionStorage}

import DAGStateChannel.RoundId

object Main extends ConstellationApp[DAGStateChannelContext[IO]] {
  override val stateChannelKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[L1PeerDAGBlockData] -> 1001,
    classOf[Proposal] -> 1002,
    classOf[BlockProposal] -> 1003,
    classOf[CancelledBlockCreationRound] -> 1004,
    classOf[RoundId] -> 1005,
    classOf[PulledTips] -> 1006,
    classOf[BlockReference] -> 1007,
    classOf[DAGBlock] -> 1008,
    classOf[CancellationReason] -> 1009,
    MissingRoundPeers.getClass -> 1010,
    CreatedBlockInvalid.getClass -> 1011,
    MergedBlockHasIncorrectSignature.getClass -> 1012,
    PeerCancelled.getClass -> 1013
  )

  override def initializeStateChannel(coreContext: CoreContext): Resource[IO, StateChannelInit] = {
    implicit val kryo = coreContext.kryoSerializer
    implicit val securityProvider = coreContext.securityProvider

    for {
      blockStorage <- Resource.eval(BlockStorage.make[IO])
      // TODO: FOR TESTING
//      blockStorage <- Resource.eval(
//        BlockStorage.make[IO](
//          Seq(
//            Hashed(
//              Signed(
//                DAGBlock(Set.empty, NonEmptyList.one(BlockReference(Hash("GENESIS1"), Height(0L)))),
//                NonEmptyList.one(SignatureProof(Id(Hex("GENESIS")), Signature(Hex(""))))
//              ),
//              Hash("GENESISBLOCK1"),
//              Hash("GENESISBLOCKPROOFS1")
//            ),
//            Hashed(
//              Signed(
//                DAGBlock(Set.empty, NonEmptyList.one(BlockReference(Hash("GENESIS2"), Height(0L)))),
//                NonEmptyList.one(SignatureProof(Id(Hex("GENESIS")), Signature(Hex(""))))
//              ),
//              Hash("GENESISBLOCK2"),
//              Hash("GENESISBLOCKPROOFS2")
//            )
//          )
//        )
//      )
      transactionStorage = TransactionStorage.make[IO]
      transactionValidator = new TransactionValidator[IO](transactionStorage, coreContext.storages.address)
      blockValidator = new DAGBlockValidator(
        transactionStorage,
        blockStorage,
        coreContext.storages.address,
        transactionValidator
      )
      consensusConfig = ConsensusConfig()
      consensusStorage <- Resource.eval(ConsensusStorage.make[IO])
      l1PeerDagBlockDataQueue <- Resource.eval(Queue.unbounded[IO, L1PeerDAGBlockData])
      l1PeerDagBlockQueue <- Resource.eval(Queue.unbounded[IO, FinalBlock])
      context = DAGStateChannelContext(
        coreContext.nodeId,
        coreContext.keyPair,
        blockStorage,
        blockValidator,
        coreContext.appResources.client,
        coreContext.storages.cluster,
        coreContext.services.gossip,
        consensusConfig,
        consensusStorage,
        coreContext.storages.node,
        transactionStorage,
        l1PeerDagBlockDataQueue,
        l1PeerDagBlockQueue,
        coreContext.kryoSerializer,
        coreContext.securityProvider
      )
      routes = new L1Routes[IO](
        transactionValidator,
        transactionStorage,
        l1PeerDagBlockDataQueue,
        l1PeerDagBlockQueue
      )
      handler = DAGBlockRumorHandler.getHandler(blockStorage)
      stateChannelInit = StateChannelInit(handler, routes.routes, context)
    } yield stateChannelInit
  }

  override def runStateChannel(stateChannelContext: DAGStateChannelContext[IO]): Resource[IO, Unit] =
    for {
      random <- Resource.eval(Random.scalaUtilRandom[IO])
      stateChannel = {
        implicit val r = random
        implicit val kryo = stateChannelContext.kryoSerializer
        implicit val securityProvider = stateChannelContext.securityProvider

        new DAGStateChannel[IO](stateChannelContext)
      }
      _ <- Resource.eval(stateChannel.runtimePipeline.compile.drain)
    } yield ()
}
