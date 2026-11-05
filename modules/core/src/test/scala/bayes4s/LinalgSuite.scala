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

import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import weaver.SimpleIOSuite

/** The five linear-algebra operations, against ports of the Stan Math primitives.
  *
  * Five operations and no more: a matrix-vector product, a Cholesky factor, a triangular solve, a
  * log determinant and an indexing by group. That is what a varying-effects model needs, and a
  * general linear-algebra dependency would be a native library carried for the sake of matrices
  * with a dozen rows.
  */
object LinalgSuite extends SimpleIOSuite with Approx:

  private def matrix(rows: IArray[IArray[Double]]): Either[String, Matrix] =
    Matrix.ofRows(rows).left.map(_.message)

  private def factor(rows: IArray[IArray[Double]]): Either[String, Cholesky] =
    matrix(rows).flatMap(m => Cholesky.ofLower(m).left.map(_.message))

  pureTest("the matrix-vector products of multilevel.json") {
    Fixtures.matVecCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 4, s"${cases.length} cases, expected 4").and(
          forEach(cases) { c =>
            val got = matrix(c.a).flatMap(m => Linalg.matVec(m, c.x).left.map(_.message))
            got match
              case Left(message) => failure(message)
              case Right(values) =>
                forEach(c.value.toList.zipWithIndex) { (expected, i) =>
                  closeRel(values(i), expected, 1e-12, s"matVec component $i")
                }
          }
        )
  }

  pureTest("the Cholesky factors of multilevel.json") {
    Fixtures.choleskyCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 3, s"${cases.length} cases, expected 3").and(
          forEach(cases) { c =>
            val got = matrix(c.s).flatMap(m => Linalg.cholesky(m).left.map(_.message))
            got match
              case Left(message) => failure(message)
              case Right(l) =>
                val n = l.dimension
                forEach((0 until n).toList) { i =>
                  forEach((0 until n).toList) { j =>
                    closeRel(l.matrix(i, j), c.value(i)(j), 1e-12, s"L($i, $j)")
                  }
                }
          }
        )
  }

  pureTest("the triangular solves of multilevel.json") {
    Fixtures.lowerSolveCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 3, s"${cases.length} cases, expected 3").and(
          forEach(cases) { c =>
            val got = factor(c.l).flatMap(l => Linalg.lowerSolve(l, c.b).left.map(_.message))
            got match
              case Left(message) => failure(message)
              case Right(z) =>
                forEach(c.value.toList.zipWithIndex) { (expected, i) =>
                  closeRel(z(i), expected, 1e-12, s"lowerSolve component $i")
                }
          }
        )
  }

  pureTest("the group indexing of multilevel.json") {
    Fixtures.gatherCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        check(cases.length == 1, s"${cases.length} cases, expected 1").and(
          forEach(cases) { c =>
            Linalg.gather(c.values, c.index) match
              case Left(error) => failure(error.message)
              case Right(got) =>
                check(
                  got.length == c.value.length,
                  s"${got.length} values, expected ${c.value.length}"
                )
                  .and(
                    forEach(c.value.toList.zipWithIndex) { (expected, i) =>
                      check(got(i) == expected, s"gather component $i: ${got(i)} against $expected")
                    }
                  )
          }
        )
  }

  pureTest("a factor reproduces the matrix it came from, and its log determinant") {
    Fixtures.choleskyCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        forEach(cases) { c =>
          val got = matrix(c.s).flatMap(m => Linalg.cholesky(m).left.map(_.message))
          got match
            case Left(message) => failure(message)
            case Right(l) =>
              val n = l.dimension
              // L L^T must give back S, which is the only statement that makes the factor useful.
              val product = forEach((0 until n).toList) { i =>
                forEach((0 to i).toList) { j =>
                  val entry = (0 until n).map(k => l.matrix(i, k) * l.matrix(j, k)).sum
                  closeRel(entry, c.s(i)(j), 1e-12, s"(L L^T)($i, $j)")
                }
              }
              val determinant = closeRel(
                Linalg.logDetFromCholesky(l),
                (0 until n).map(i => math.log(l.diagonal(i))).sum,
                1e-12,
                "log det L against the sum of the logs of its diagonal"
              )
              product.and(determinant)
        }
  }

  // The message matters as much as the rejection. A `NaN` diagonal would be caught further down by
  // `Cholesky.ofLower`, which would report a bad diagonal: true, and useless to someone whose
  // covariance is not positive definite. The assertion is therefore on the diagnosis, and it is
  // what makes removing the pivot check a detectable mutation rather than an invisible one.
  pureTest("a matrix that is not positive definite is refused, and the pivot is named") {
    val notPositive = IArray(IArray(1.0, 2.0), IArray(2.0, 1.0))
    val zeroPivot = IArray(IArray(0.0, 0.0), IArray(0.0, 1.0))
    val negative = IArray(IArray(-1.0, 0.0), IArray(0.0, -1.0))
    forEach(
      List("indefinite" -> notPositive, "a zero pivot" -> zeroPivot, "negative" -> negative)
    ) { (label, rows) =>
      val attempt = matrix(rows).flatMap(m => Linalg.cholesky(m).left.map(_.message))
      attempt match
        case Right(_) => failure(s"$label was accepted as positive definite")
        case Left(message) =>
          check(
            message.contains("pivot") && message.contains("positive definite"),
            s"$label was refused, but the message says '$message' rather than naming the pivot"
          )
    }
  }

  pureTest("the constructors refuse what they cannot represent") {
    check(Matrix.of(2, 2, IArray(1.0, 2.0, 3.0)).isLeft, "3 values for a 2 by 2 matrix")
      .and(check(Matrix.ofRows(IArray(IArray(1.0), IArray(1.0, 2.0))).isLeft, "ragged rows"))
      .and(check(Matrix.ofRows(IArray.empty[IArray[Double]]).isLeft, "no rows"))
      .and {
        val notTriangular = IArray(IArray(1.0, 0.5), IArray(0.0, 1.0))
        check(factor(notTriangular).isLeft, "an upper triangle was accepted as a factor")
      }
      .and {
        val negativeDiagonal = IArray(IArray(-1.0, 0.0), IArray(0.0, 1.0))
        check(factor(negativeDiagonal).isLeft, "a negative diagonal was accepted as a factor")
      }
      .and {
        val rectangular = IArray(IArray(1.0, 0.0, 0.0), IArray(0.0, 1.0, 0.0))
        check(factor(rectangular).isLeft, "a rectangular matrix was accepted as a factor")
      }
  }

  pureTest("mismatched sizes and out-of-range indices are values, not exceptions") {
    val square = Matrix.ofRows(IArray(IArray(1.0, 0.0), IArray(0.0, 1.0)))
    check(
      square.flatMap(m => Linalg.matVec(m, IArray(1.0, 2.0, 3.0))).isLeft,
      "a vector of the wrong length was accepted"
    ).and(
      check(
        factor(IArray(IArray(1.0, 0.0), IArray(0.0, 1.0)))
          .flatMap(l => Linalg.lowerSolve(l, IArray(1.0)).left.map(_.message))
          .isLeft,
        "a right-hand side of the wrong length was accepted"
      )
    ).and(check(Linalg.gather(IArray(1.0, 2.0), IArray(0, 2)).isLeft, "an index past the end"))
      .and(check(Linalg.gather(IArray(1.0, 2.0), IArray(-1)).isLeft, "a negative index"))
      .and(
        check(Linalg.gather(IArray(1.0, 2.0), IArray.empty[Int]).isRight, "an empty index is fine")
      )
  }

  pureTest("the identity is an identity, and has no size below one") {
    val n = 4
    val x = IArray(1.5, -2.0, 0.0, 7.25)
    val applied = Matrix.identity(n).flatMap(e => Linalg.matVec(e, x))
    applied match
      case Left(error) => failure(error.message)
      case Right(got) =>
        forEach((0 until n).toList)(i => check(got(i) == x(i), s"component $i"))
          .and(check(Matrix.identity(0).isLeft, "a size of 0"))
          .and(check(Matrix.identity(-2).isLeft, "a negative size"))
  }
