# Concept

In order to implement a state channel, one has to use the following `Cell` class.

```scala
abstract class Cell[M[_]: Monad, F[_]: Traverse, A, B, C](
  data: A,
  algebra: AlgebraM[M, F, B],
  coalgebra: CoalgebraM[M, F, C]
) extends Topos[A, B] {
  
  protected def hyloM(implicit input: A => C) = scheme.hyloM(algebra, coalgebra).apply(input(data))
  
}
```

**There are following type parameters:**

The very first type `M[_]` is required to be a `Monad`. State channels can execute some side effects
and due to that fact it needs to encapsulate these effects inside a monad.
  
The second type `F[_]` is required to be a `Functor` (`Traverse`).
This is a structure used for executing state channel recursion.
For **L0 consensus** and **L1 consensus** implementations, it's a `StackF` case class which is explained below.
  
`A` is a data holder. To create a `Cell` one has to provide a data which will be consumed by the state channel.
These data are stored inside the Cell for later execution.
  
`B` is the algebra output (see: [Catamorpism and algebra](./recursion-schemes.md#Catamorphism and algebra))
  
`C` is the coalgebra input (see: [Anamorphism and coalgebra](./recursion-schemes.md#Anamorphism and coalgebra))

### StackF

The common stack is a well-known idea. In the context of an execution chain, the stack consists of two things:
either some work to do `More(work)` or a computed result `Done(result)`.

`StackF` is an implementation of that idea using recursion schemes: 

```scala
trait StackF[A]

case class More[A](a: A) extends StackF[A]
case class Done[A](result: Either[CellError, Ω]) extends StackF[A]
```

where the job to do is parametrized by type. The result has strict type `Either[CellError, Ω]`
as the output from the state channel is either an error of computation or a terminal object.

The following idea allows to write an algebra and coalgebra working at a single step of state channel and then
repetitively execute the Cell passing output of the first execution as input to the second execution, until `Done(result)` is returned.   

### Algebra and Coalgebra for StackF structure

It is pretty straightforward to write an implementation of `StackF` scheme.
Most of the parts are common between state channels so ideally this will be extracted and used as a plug-in code.

Algebra is used for proxying inner value `work` or `result` from types `More(work)`/`Done(result)` and passing it to the output.
Coalgebra is a termination condition for the execution chain - it reacts on a single or multiple classes to return `Done` class.
For every other type it executes repetitively the inner algebra and coalgebra for single steps.

### Inner Algebra and Coalgebra - for single execution step

This part is responsible for the core logic of a state channel.

Coalgebra consists of all steps used by a Cell and holds the execution order.
Algebra is a fold operation which reduces the ending type to the output terminal object. 