package io.constellationnetwork.currency

import cats.data.ValidatedNec

package object dataApplication {

  // Re-export core types
  type DataApplicationValidationErrorOr[A] = ValidatedNec[DataApplicationValidationError, A]

  // Re-export services
  type BaseDataApplicationService[F[_]] = services.BaseDataApplicationService[F]
  val BaseDataApplicationService = services.BaseDataApplicationService

  type DataApplicationService[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState] =
    services.DataApplicationService[F, D, DON, DOF]

  type BaseDataApplicationL0Service[F[_]] = services.BaseDataApplicationL0Service[F]
  val BaseDataApplicationL0Service = services.BaseDataApplicationL0Service

  type DataApplicationL0Service[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState] =
    services.DataApplicationL0Service[F, D, DON, DOF]

  type BaseDataApplicationL1Service[F[_]] = services.BaseDataApplicationL1Service[F]
  val BaseDataApplicationL1Service = services.BaseDataApplicationL1Service

  type DataApplicationL1Service[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState] =
    services.DataApplicationL1Service[F, D, DON, DOF]

  // Re-export ops
  type BaseDataApplicationSharedContextualOps[F[_], Context] = ops.BaseDataApplicationSharedContextualOps[F, Context]

  type BaseDataApplicationL0ContextualOps[F[_]] = ops.BaseDataApplicationL0ContextualOps[F]
  val BaseDataApplicationL0ContextualOps = ops.BaseDataApplicationL0ContextualOps

  type DataApplicationL0ContextualOps[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState] =
    ops.DataApplicationL0ContextualOps[F, D, DON, DOF]

  type BaseDataApplicationL1ContextualOps[F[_]] = ops.BaseDataApplicationL1ContextualOps[F]
  val BaseDataApplicationL1ContextualOps = ops.BaseDataApplicationL1ContextualOps

  type DataApplicationL1ContextualOps[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState] =
    ops.DataApplicationL1ContextualOps[F, D, DON, DOF]

  type DataApplicationSharedContextualOps[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState, Context] =
    ops.DataApplicationSharedContextualOps[F, D, DON, DOF, Context]

  // Re-export contexts
  type L0NodeContext[F[_]] = context.L0NodeContext[F]
  type L1NodeContext[F[_]] = context.L1NodeContext[F]

  // Re-export block types
  type DataApplicationBlock = block.DataApplicationBlock
  val DataApplicationBlock = block.DataApplicationBlock

  val DataApplicationCustomRoutes = routes.DataApplicationCustomRoutes
}
