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

/** A dense immutable matrix, stored by rows.
  *
  * Deliberately minimal. The sizes this library meets are the covariance of a handful of group
  * effects and the design of a few dozen rows, which do not justify a native dependency; anything
  * larger belongs to a library that does nothing else.
  */
final case class Matrix private (rows: Int, cols: Int, values: IArray[Double]):
  def apply(row: Int, col: Int): Double = values(row * cols + col)
  def row(i: Int): IArray[Double] = IArray.tabulate(cols)(j => apply(i, j))
  def isSquare: Boolean = rows == cols

object Matrix:
  def of(rows: Int, cols: Int, values: IArray[Double]): Either[BayesError, Matrix] =
    if rows < 1 then
      Left(BayesError.InvalidArgument("Matrix.of", "rows", rows.toDouble, "at least one row"))
    else if cols < 1 then
      Left(BayesError.InvalidArgument("Matrix.of", "cols", cols.toDouble, "at least one column"))
    else if values.length != rows * cols then
      Left(BayesError.DimensionMismatch("Matrix.of", rows * cols, values.length))
    else Right(Matrix(rows, cols, values))

  /** From nested rows, which is how the reference fixtures and the models write a matrix. */
  def ofRows(rows: IArray[IArray[Double]]): Either[BayesError, Matrix] =
    if rows.length == 0 then
      Left(BayesError.InvalidArgument("Matrix.ofRows", "rows", 0.0, "at least one row"))
    else
      val cols = rows(0).length
      rows.toList.zipWithIndex.collectFirst {
        case (r, i) if r.length != cols =>
          BayesError.DimensionMismatch(s"Matrix.ofRows, row $i", cols, r.length)
      } match
        case Some(error) => Left(error)
        case None =>
          of(rows.length, cols, IArray.unsafeFromArray(rows.toList.flatMap(_.toList).toArray))

  /** The identity, which is a value and not a computation; a size below one has no identity. */
  def identity(n: Int): Either[BayesError, Matrix] =
    of(n, n, IArray.tabulate(math.max(0, n * n))(k => if k / n == k % n then 1.0 else 0.0))

/** A Cholesky factor: lower triangular with a positive diagonal, by construction.
  *
  * The type carries the invariant so that `lowerSolve` and `logDetFromCholesky` cannot be handed
  * something that is merely square, and so that a multivariate normal cannot be built on a matrix
  * that is not a factor.
  */
opaque type Cholesky = Matrix

object Cholesky:
  /** From a matrix that is already a factor, as the reference cases give it. */
  def ofLower(m: Matrix): Either[BayesError, Cholesky] =
    if !m.isSquare then Left(BayesError.DimensionMismatch("Cholesky.ofLower", m.rows, m.cols))
    else
      val offending = (0 until m.rows).view
        .flatMap(i => (0 until m.cols).view.map(j => (i, j)))
        .collectFirst {
          case (i, j) if j > i && m(i, j) != 0.0 =>
            BayesError.InvalidArgument(
              "Cholesky.ofLower",
              s"($i, $j)",
              m(i, j),
              "zero above the diagonal"
            )
          case (i, j) if i == j && !(m(i, j) > 0.0) =>
            BayesError.InvalidArgument(
              "Cholesky.ofLower",
              s"($i, $i)",
              m(i, j),
              "a positive diagonal"
            )
        }
      offending.toLeft(m)

  extension (l: Cholesky)
    def matrix: Matrix = l
    def dimension: Int = l.rows
    def diagonal(i: Int): Double = l(i, i)

/** The five operations the multilevel models need, and nothing else.
  *
  * All of them are written as folds rather than as loops over mutable arrays. The plan allowed the
  * encapsulated mutation of 2.1 here, and it turned out not to be needed: at the sizes this module
  * targets — a covariance of a few group effects — a `Vector` of rows costs nothing measurable,
  * and the register of mutation sites stays where it was.
  */
object Linalg:

  def matVec(a: Matrix, x: IArray[Double]): Either[BayesError, IArray[Double]] =
    if a.cols != x.length then Left(BayesError.DimensionMismatch("Linalg.matVec", a.cols, x.length))
    else
      Right(
        IArray.tabulate(a.rows)(i => (0 until a.cols).foldLeft(0.0)((s, j) => s + a(i, j) * x(j)))
      )

  /** The factor of a symmetric positive definite matrix; `Left` when it is not one.
    *
    * A non-positive pivot is the definition of "not positive definite", and it is reported rather
    * than turned into the `NaN` that a square root of a negative number would give. Asymmetry is
    * refused too: silently using one triangle of a matrix the caller thought was symmetric is how
    * a covariance quietly becomes something else.
    */
  def cholesky(s: Matrix): Either[BayesError, Cholesky] =
    if !s.isSquare then Left(BayesError.DimensionMismatch("Linalg.cholesky", s.rows, s.cols))
    else
      asymmetry(s) match
        case Some(error) => Left(error)
        case None =>
          val n = s.rows
          val built = (0 until n).foldLeft[Either[BayesError, Vector[Vector[Double]]]](
            Right(Vector.empty)
          ) { (accumulated, i) =>
            accumulated.flatMap(previous => choleskyRow(s, previous, i, n).map(previous :+ _))
          }
          built.flatMap { rows =>
            Matrix
              .of(n, n, IArray.unsafeFromArray(rows.flatten.toArray))
              .flatMap(Cholesky.ofLower)
          }

  /** Solves `L z = b` by forward substitution, each component from the ones before it. */
  def lowerSolve(l: Cholesky, b: IArray[Double]): Either[BayesError, IArray[Double]] =
    if l.dimension != b.length then
      Left(BayesError.DimensionMismatch("Linalg.lowerSolve", l.dimension, b.length))
    else
      val solved = (0 until l.dimension).foldLeft(Vector.empty[Double]) { (z, i) =>
        val known = (0 until i).foldLeft(0.0)((s, k) => s + l.matrix(i, k) * z(k))
        z :+ ((b(i) - known) / l.diagonal(i))
      }
      Right(IArray.unsafeFromArray(solved.toArray))

  /** log |det L|, which is the sum of the logs of its diagonal. */
  def logDetFromCholesky(l: Cholesky): Double =
    (0 until l.dimension).foldLeft(0.0)((s, i) => s + math.log(l.diagonal(i)))

  /** `values(index(i))` for each i: the primitive that indexes a parameter by group.
    *
    * The one operation here that is not arithmetic. A model writes `gather(alpha, group)` to give
    * every observation the parameter of its own group, and in phase 2 its adjoint is what lets ten
    * thousand observations cost a handful of nodes on the tape instead of ten thousand.
    */
  def gather(values: IArray[Double], index: IArray[Int]): Either[BayesError, IArray[Double]] =
    index.toList.zipWithIndex.collectFirst {
      case (k, position) if k < 0 || k >= values.length =>
        BayesError.InvalidArgument(
          "Linalg.gather",
          s"index($position)",
          k.toDouble,
          s"in [0, ${values.length})"
        )
    } match
      case Some(error) => Left(error)
      case None => Right(IArray.tabulate(index.length)(i => values(index(i))))

  // --- internals ----------------------------------------------------------------------------

  private def asymmetry(s: Matrix): Option[BayesError] =
    (0 until s.rows).view
      .flatMap(i => (0 until i).view.map(j => (i, j)))
      .collectFirst {
        case (i, j) if s(i, j) != s(j, i) =>
          BayesError.InvalidArgument("Linalg.cholesky", s"($i, $j)", s(i, j), "a symmetric matrix")
      }

  /** Row `i` of the factor: the off-diagonal entries from the rows already built, then the pivot. */
  private def choleskyRow(
      s: Matrix,
      previous: Vector[Vector[Double]],
      i: Int,
      n: Int
  ): Either[BayesError, Vector[Double]] =
    val lower = (0 until i).foldLeft(Vector.empty[Double]) { (row, j) =>
      val known = (0 until j).foldLeft(0.0)((acc, k) => acc + row(k) * previous(j)(k))
      row :+ ((s(i, j) - known) / previous(j)(j))
    }
    val pivot = s(i, i) - lower.foldLeft(0.0)((acc, x) => acc + x * x)
    if !(pivot > 0.0) then
      Left(
        BayesError.InvalidArgument(
          "Linalg.cholesky",
          s"pivot($i)",
          pivot,
          "a positive pivot, which is what positive definite means"
        )
      )
    else Right(lower ++ Vector(math.sqrt(pivot)) ++ Vector.fill(n - i - 1)(0.0))
