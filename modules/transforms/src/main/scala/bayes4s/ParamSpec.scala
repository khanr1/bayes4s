/*
 * Copyright 2026 bayes4s contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bayes4s

import cats.Id

/** One named block of parameters: what it is called, what shape it has, and what it is allowed to
  * be.
  *
  * A scalar is one row and one column, a vector is `n` rows and one column, a matrix is both. The
  * shape is carried rather than inferred because it is what decides the names: CmdStan writes
  * `a`, `eps.1`, `L.1.1`, and a model that wants its samples read back from a CmdStan file has to
  * agree with it.
  */
final case class Block(name: String, rows: Int, cols: Int, constraint: Constraint):

  /** How many constrained values the block holds. */
  def size: Int = rows * cols

  /** How many unconstrained reals stand for them. */
  def unconstrainedSize: Int = Transform.of(constraint).unconstrainedSize(size)

  /** The CmdStan names of the block's values, in the order the unconstrained vector holds them.
    *
    * **Column major** for a matrix, which is Stan's storage order and therefore the order of a
    * CmdStan CSV header: `L.1.1`, `L.2.1`, … `L.1.2`. Reading them in the other order would give a
    * transposed matrix on the way back from a file, with no error anywhere.
    */
  def names: List[String] =
    if rows == 1 && cols == 1 then List(name)
    else if cols == 1 then List.tabulate(rows)(i => s"$name.${i + 1}")
    else
      (for
        j <- 0 until cols
        i <- 0 until rows
      yield s"$name.${i + 1}.${j + 1}").toList

object Block:
  def scalar(name: String, constraint: Constraint): Block = Block(name, 1, 1, constraint)
  def vector(name: String, n: Int, constraint: Constraint): Block = Block(name, n, 1, constraint)

/** A description of a model's parameters that knows how to read them.
  *
  * A `ParamSpec[T]` says what the parameters are called, in what order and under what constraints,
  * and reads a `T[A]` out of the unconstrained vector together with the log Jacobian that reading
  * cost. It is a value: there is no registry, no mutable builder and no order of declaration to
  * get wrong, and two specs combine with [[ParamSpec.mapN]].
  *
  * The type is sealed. Every spec comes from the combinators below, which is what makes the layout
  * and the reading agree by construction: a hand-written instance could say one thing in `layout`
  * and read another.
  */
sealed trait ParamSpec[T[_]]:

  /** The blocks, in the order the unconstrained vector holds them. */
  def layout: List[Block]

  /** Reads from `u` starting at `from`, and says where it stopped. */
  private[bayes4s] def readAt[A: Real](u: IArray[A], from: Int): (T[A], A, Int)

  /** The constrained parameters and the total log Jacobian. */
  final def read[A: Real](u: IArray[A]): (T[A], A) =
    val (value, jacobian, _) = readAt(u, 0)
    (value, jacobian)

  final def unconstrainedSize: Int = layout.map(_.unconstrainedSize).sum

  /** Every value's name, in the order the unconstrained vector holds the values they came from. */
  final def names: List[String] = layout.flatMap(_.names)

  /** The same spec, once its names are known to be unique.
    *
    * Two blocks with one name would make `Model.unconstrain` ambiguous and a CSV unreadable, and
    * nothing downstream could tell. Checked here rather than at every combination, so that `mapN`
    * stays a plain applicative and the error is a value like every other.
    */
  final def validated: Either[BayesError, ParamSpec[T]] =
    val duplicates = layout.map(_.name).groupBy(identity).collect {
      case (name, repeats) if repeats.length > 1 => name
    }
    duplicates.toList.sorted match
      case Nil => Right(this)
      case first :: _ => Left(BayesError.DuplicateParameter(first))

object ParamSpec:

  /** A value that costs no parameter: the unit of [[mapN]]. */
  def pure[V](value: V): ParamSpec[[A] =>> V] =
    new ParamSpec[[A] =>> V]:
      def layout: List[Block] = Nil
      private[bayes4s] def readAt[A: Real](u: IArray[A], from: Int): (V, A, Int) =
        (value, Real[A].const(0.0), from)

  def real(name: String): ParamSpec[Id] = scalar(name, Constraint.Unconstrained)
  def positive(name: String): ParamSpec[Id] = scalar(name, Constraint.Positive)
  def unitInterval(name: String): ParamSpec[Id] = scalar(name, Constraint.UnitInterval)

  def bounded(name: String, lower: Double, upper: Double): ParamSpec[Id] =
    scalar(name, Constraint.Interval(lower, upper))

  /** A scalar under any constraint whose values are scalars. */
  def scalar(name: String, constraint: Constraint): ParamSpec[Id] =
    val block = Block.scalar(name, constraint)
    new ParamSpec[Id]:
      def layout: List[Block] = List(block)
      private[bayes4s] def readAt[A: Real](u: IArray[A], from: Int): (A, A, Int) =
        val size = block.unconstrainedSize
        val (values, jacobian) = Transform
          .of(constraint)
          .constrain(IArray.tabulate(size)(i => u(from + i))(using Real[A].tag))
        (values(0), jacobian, from + size)

  def vector(
      name: String,
      n: Int,
      constraint: Constraint = Constraint.Unconstrained
  ): ParamSpec[IArray] =
    val block = Block.vector(name, n, constraint)
    new ParamSpec[IArray]:
      def layout: List[Block] = List(block)
      private[bayes4s] def readAt[A: Real](u: IArray[A], from: Int): (IArray[A], A, Int) =
        val size = block.unconstrainedSize
        val (values, jacobian) = Transform
          .of(constraint)
          .constrain(IArray.tabulate(size)(i => u(from + i))(using Real[A].tag))
        (values, jacobian, from + size)

  /** An unconstrained matrix, read column by column as Stan stores it. */
  def matrix(name: String, rows: Int, cols: Int): ParamSpec[[A] =>> IArray[IArray[A]]] =
    val block = Block(name, rows, cols, Constraint.Unconstrained)
    new ParamSpec[[A] =>> IArray[IArray[A]]]:
      def layout: List[Block] = List(block)
      private[bayes4s] def readAt[A: Real](
          u: IArray[A],
          from: Int
      ): (IArray[IArray[A]], A, Int) =
        val r = Real[A]
        given scala.reflect.ClassTag[A] = r.tag
        val values = IArray.tabulate(rows)(i => IArray.tabulate(cols)(j => u(from + j * rows + i)))
        (values, r.const(0.0), from + block.unconstrainedSize)

  /** The Cholesky factor of a `k` by `k` correlation matrix.
    *
    * `k(k-1)/2` unconstrained reals, `k * k` named values — the zeros above the diagonal included,
    * because that is what CmdStan writes for a `cholesky_factor_corr`.
    */
  def choleskyCorr(name: String, k: Int): ParamSpec[[A] =>> IArray[IArray[A]]] =
    val block = Block(name, k, k, Constraint.CholeskyFactorCorr(k))
    new ParamSpec[[A] =>> IArray[IArray[A]]]:
      def layout: List[Block] = List(block)
      private[bayes4s] def readAt[A: Real](
          u: IArray[A],
          from: Int
      ): (IArray[IArray[A]], A, Int) =
        val r = Real[A]
        given scala.reflect.ClassTag[A] = r.tag
        val size = block.unconstrainedSize
        val (flat, jacobian) =
          Transform.of(block.constraint).constrain(IArray.tabulate(size)(i => u(from + i)))
        val rows = IArray.tabulate(k)(i => IArray.tabulate(k)(j => flat(j * k + i)))
        (rows, jacobian, from + size)

  extension [T[_]](self: ParamSpec[T])

    /** The same parameters, read as something else: a case class, usually. */
    def map[U[_]](f: [A] => T[A] => U[A]): ParamSpec[U] =
      new ParamSpec[U]:
        def layout: List[Block] = self.layout
        private[bayes4s] def readAt[A: Real](u: IArray[A], from: Int): (U[A], A, Int) =
          val (value, jacobian, next) = self.readAt(u, from)
          (f[A](value), jacobian, next)

    /** Both blocks, in this order, with their log Jacobians added. */
    def product[U[_]](that: ParamSpec[U]): ParamSpec[[A] =>> (T[A], U[A])] =
      new ParamSpec[[A] =>> (T[A], U[A])]:
        def layout: List[Block] = self.layout ++ that.layout
        private[bayes4s] def readAt[A: Real](u: IArray[A], from: Int): ((T[A], U[A]), A, Int) =
          val (left, leftJacobian, middle) = self.readAt(u, from)
          val (right, rightJacobian, next) = that.readAt(u, middle)
          ((left, right), Real[A].plus(leftJacobian, rightJacobian), next)

  // --- mapN, for the arities a model needs ----------------------------------------------------
  //
  // `mapN` is spelled out per arity rather than taken from cats: `ParamSpec` abstracts over a type
  // *constructor*, so `Semigroupal[ParamSpec]` cannot be written at all — cats' `Semigroupal[F[_]]`
  // wants an `F` of kind `* -> *`. The applicative structure is the one below, and the laws it
  // obeys are checked in `ParamSpecSuite` on the one shape cats can talk about (docs/design.md).

  extension [T1[_], T2[_]](specs: (ParamSpec[T1], ParamSpec[T2]))
    def mapN[U[_]](f: [A] => (T1[A], T2[A]) => U[A]): ParamSpec[U] =
      specs._1.product(specs._2).map([A] => (p: (T1[A], T2[A])) => f[A](p._1, p._2))

  extension [T1[_], T2[_], T3[_]](specs: (ParamSpec[T1], ParamSpec[T2], ParamSpec[T3]))
    def mapN[U[_]](f: [A] => (T1[A], T2[A], T3[A]) => U[A]): ParamSpec[U] =
      specs._1
        .product(specs._2)
        .product(specs._3)
        .map([A] => (p: ((T1[A], T2[A]), T3[A])) => f[A](p._1._1, p._1._2, p._2))

  extension [T1[_], T2[_], T3[_], T4[_]](
      specs: (ParamSpec[T1], ParamSpec[T2], ParamSpec[T3], ParamSpec[T4])
  )
    def mapN[U[_]](f: [A] => (T1[A], T2[A], T3[A], T4[A]) => U[A]): ParamSpec[U] =
      specs._1
        .product(specs._2)
        .product(specs._3)
        .product(specs._4)
        .map([A] =>
          (p: (((T1[A], T2[A]), T3[A]), T4[A])) => f[A](p._1._1._1, p._1._1._2, p._1._2, p._2)
        )

  extension [T1[_], T2[_], T3[_], T4[_], T5[_]](
      specs: (ParamSpec[T1], ParamSpec[T2], ParamSpec[T3], ParamSpec[T4], ParamSpec[T5])
  )
    def mapN[U[_]](f: [A] => (T1[A], T2[A], T3[A], T4[A], T5[A]) => U[A]): ParamSpec[U] =
      specs._1
        .product(specs._2)
        .product(specs._3)
        .product(specs._4)
        .product(specs._5)
        .map([A] =>
          (p: ((((T1[A], T2[A]), T3[A]), T4[A]), T5[A])) =>
            f[A](p._1._1._1._1, p._1._1._1._2, p._1._1._2, p._1._2, p._2)
        )

  extension [T1[_], T2[_], T3[_], T4[_], T5[_], T6[_]](
      specs: (
          ParamSpec[T1],
          ParamSpec[T2],
          ParamSpec[T3],
          ParamSpec[T4],
          ParamSpec[T5],
          ParamSpec[T6]
      )
  )
    def mapN[U[_]](f: [A] => (T1[A], T2[A], T3[A], T4[A], T5[A], T6[A]) => U[A]): ParamSpec[U] =
      specs._1
        .product(specs._2)
        .product(specs._3)
        .product(specs._4)
        .product(specs._5)
        .product(specs._6)
        .map([A] =>
          (p: (((((T1[A], T2[A]), T3[A]), T4[A]), T5[A]), T6[A])) =>
            f[A](
              p._1._1._1._1._1,
              p._1._1._1._1._2,
              p._1._1._1._2,
              p._1._1._2,
              p._1._2,
              p._2
            )
        )

  extension [T1[_], T2[_], T3[_], T4[_], T5[_], T6[_], T7[_]](
      specs: (
          ParamSpec[T1],
          ParamSpec[T2],
          ParamSpec[T3],
          ParamSpec[T4],
          ParamSpec[T5],
          ParamSpec[T6],
          ParamSpec[T7]
      )
  )
    def mapN[U[_]](
        f: [A] => (T1[A], T2[A], T3[A], T4[A], T5[A], T6[A], T7[A]) => U[A]
    ): ParamSpec[U] =
      specs._1
        .product(specs._2)
        .product(specs._3)
        .product(specs._4)
        .product(specs._5)
        .product(specs._6)
        .product(specs._7)
        .map([A] =>
          (p: ((((((T1[A], T2[A]), T3[A]), T4[A]), T5[A]), T6[A]), T7[A])) =>
            f[A](
              p._1._1._1._1._1._1,
              p._1._1._1._1._1._2,
              p._1._1._1._1._2,
              p._1._1._1._2,
              p._1._1._2,
              p._1._2,
              p._2
            )
        )

  extension [T1[_], T2[_], T3[_], T4[_], T5[_], T6[_], T7[_], T8[_]](
      specs: (
          ParamSpec[T1],
          ParamSpec[T2],
          ParamSpec[T3],
          ParamSpec[T4],
          ParamSpec[T5],
          ParamSpec[T6],
          ParamSpec[T7],
          ParamSpec[T8]
      )
  )
    def mapN[U[_]](
        f: [A] => (T1[A], T2[A], T3[A], T4[A], T5[A], T6[A], T7[A], T8[A]) => U[A]
    ): ParamSpec[U] =
      specs._1
        .product(specs._2)
        .product(specs._3)
        .product(specs._4)
        .product(specs._5)
        .product(specs._6)
        .product(specs._7)
        .product(specs._8)
        .map([A] =>
          (p: (((((((T1[A], T2[A]), T3[A]), T4[A]), T5[A]), T6[A]), T7[A]), T8[A])) =>
            f[A](
              p._1._1._1._1._1._1._1,
              p._1._1._1._1._1._1._2,
              p._1._1._1._1._1._2,
              p._1._1._1._1._2,
              p._1._1._1._2,
              p._1._1._2,
              p._1._2,
              p._2
            )
        )
