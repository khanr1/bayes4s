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

package bayes4s.testkit

import cats.syntax.all.*
import io.circe.Decoder
import io.circe.Json

/** One point of a special-function grid. */
final case class PointCase(x: Double, value: Double)

/** One point of a grid indexed by a probability. */
final case class ProbCase(p: Double, value: Double)

/** One case whose argument is a vector, such as `log_sum_exp`. */
final case class VectorCase(x: IArray[Double], value: Double)

/** One case with two scalar arguments, such as `lbeta`. */
final case class PairCase(a: Double, b: Double, value: Double)

/** One density evaluated at an extreme argument. */
final case class ExtremeCase(distribution: String, args: IArray[Double], value: Double)

/** One type-7 quantile asserted by NumPy and R. */
final case class Quantile7Case(data: IArray[Double], p: Double, value: Double)

/** A valid case of a Stan Math distribution test: the arguments and the value Stan asserts. */
final case class StanValid(params: IArray[Double], expected: Double)

/** An invalid case: putting `value` at position `index` must be rejected. */
final case class StanInvalid(index: Int, value: Double)

/** One function of one distribution: `lpdf`, `cdf`, `lcdf`, `lccdf` or `lpdf_log_param`. */
final case class StanFunction(
    paramNames: List[String],
    valid: List[StanValid],
    invalid: List[StanInvalid]
)

/** The whole Stan Math fixture: distribution name, then function name. */
final case class StanMathFixture(laws: Map[String, Map[String, StanFunction]]):
  def function(law: String, name: String): Option[StanFunction] = laws.get(law).flatMap(_.get(name))
  def valid(law: String, name: String): List[StanValid] =
    function(law, name).map(_.valid).getOrElse(Nil)
  def invalid(law: String, name: String): List[StanInvalid] =
    function(law, name).map(_.invalid).getOrElse(Nil)

/** One case of `multi_normal_cholesky_lpdf`.
  *
  * One case carries a `source`, the Stan test it was ported from; the other twelve carry a `scipy`
  * value instead, which is the independent confirmation. [[label]] says which, so a failure names
  * what asserted the number.
  */
final case class MultiNormalCase(
    source: Option[String],
    scipy: Option[Double],
    y: IArray[Double],
    mu: IArray[Double],
    l: IArray[IArray[Double]],
    value: Double
):
  def label: String = source.getOrElse(scipy.fold("no source")(v => s"confirmed by SciPy at $v"))

/** One LKJ normalising constant, for a dimension and a concentration. */
final case class LkjConstantCase(k: Int, eta: Double, constant: Double)

/** One LKJ density, evaluated on the Cholesky factor of a correlation matrix. */
final case class LkjCholeskyCase(
    k: Int,
    eta: Double,
    l: IArray[IArray[Double]],
    value: Double
)

/** The four shapes of the linear-algebra cases, one per operation. */
final case class MatVecCase(a: IArray[IArray[Double]], x: IArray[Double], value: IArray[Double])
final case class CholeskyCase(s: IArray[IArray[Double]], value: IArray[IArray[Double]])
final case class LowerSolveCase(
    l: IArray[IArray[Double]],
    b: IArray[Double],
    value: IArray[Double]
)
final case class GatherCase(values: IArray[Double], index: IArray[Int], value: IArray[Double])

/** Typed readers for the reference files, so that the suites read as assertions rather than as
  * JSON navigation, and the decoding itself is written once.
  *
  * Every scalar is read with [[Reference.looseDouble]]: the files carry Python's `Infinity`,
  * `-Infinity` and `NaN`.
  */
object Fixtures:

  private val d: Decoder[Double] = Reference.looseDouble
  private val ds: Decoder[IArray[Double]] = Reference.doubleArray

  private given pointCase: Decoder[PointCase] = Decoder.instance: c =>
    for
      x <- c.get("x")(using d)
      v <- c.get("value")(using d)
    yield PointCase(x, v)

  private given probCase: Decoder[ProbCase] = Decoder.instance: c =>
    for
      p <- c.get("p")(using d)
      v <- c.get("value")(using d)
    yield ProbCase(p, v)

  private given vectorCase: Decoder[VectorCase] = Decoder.instance: c =>
    for
      x <- c.get("x")(using ds)
      v <- c.get("value")(using d)
    yield VectorCase(x, v)

  private given pairCase: Decoder[PairCase] = Decoder.instance: c =>
    for
      a <- c.get("a")(using d)
      b <- c.get("b")(using d)
      v <- c.get("value")(using d)
    yield PairCase(a, b, v)

  private given extremeCase: Decoder[ExtremeCase] = Decoder.instance: c =>
    for
      name <- c.get[String]("distribution")
      args <- c.get("args")(using ds)
      v <- c.get("value")(using d)
    yield ExtremeCase(name, args, v)

  private given quantile7Case: Decoder[Quantile7Case] = Decoder.instance: c =>
    for
      data <- c.get("data")(using ds)
      p <- c.get("p")(using d)
      v <- c.get("value")(using d)
    yield Quantile7Case(data, p, v)

  private given stanValid: Decoder[StanValid] = Decoder.instance: c =>
    for
      params <- c.get("params")(using ds)
      expected <- c.get("expected")(using d)
    yield StanValid(params, expected)

  private given stanInvalid: Decoder[StanInvalid] = Decoder.instance: c =>
    for
      index <- c.get[Int]("index")
      value <- c.get("value")(using d)
    yield StanInvalid(index, value)

  private given stanFunction: Decoder[StanFunction] = Decoder.instance: c =>
    for
      names <- c.get[List[String]]("param_names")
      valid <- c.getOrElse[List[StanValid]]("valid")(Nil)
      invalid <- c.getOrElse[List[StanInvalid]]("invalid")(Nil)
    yield StanFunction(names, valid, invalid)

  /** A function of `special_functions.json`, read as a grid of scalar points. */
  def specialPoints(name: String): Either[String, List[PointCase]] =
    at[List[PointCase]]("python/special_functions.json", List("functions", name))

  def specialProbs(name: String): Either[String, List[ProbCase]] =
    at[List[ProbCase]]("python/special_functions.json", List("functions", name))

  def specialVectors(name: String): Either[String, List[VectorCase]] =
    at[List[VectorCase]]("python/special_functions.json", List("functions", name))

  def specialPairs(name: String): Either[String, List[PairCase]] =
    at[List[PairCase]]("python/special_functions.json", List("functions", name))

  /** The arguments at which lgamma and digamma must return an infinity rather than throw. */
  def poles(name: String): Either[String, IArray[Double]] =
    at[IArray[Double]]("python/special_functions.json", List("functions", name, "x"))(using ds)

  def extremeCases: Either[String, List[ExtremeCase]] =
    at[List[ExtremeCase]]("python/densities_extreme.json", List("cases"))

  def quantile7: Either[String, List[Quantile7Case]] =
    at[List[Quantile7Case]]("python/scoring.json", List("quantile_type7"))

  def stanMath: Either[String, StanMathFixture] =
    at[Map[String, Map[String, StanFunction]]](
      "stan-math/distributions.json",
      List("distributions")
    ).map(StanMathFixture.apply)

  private val ms: Decoder[IArray[IArray[Double]]] = Reference.doubleMatrix

  private given multiNormalCase: Decoder[MultiNormalCase] = Decoder.instance: c =>
    for
      source <- c.get[Option[String]]("source")
      scipy <- c.getOrElse[Option[Double]]("scipy")(None)
      y <- c.get("y")(using ds)
      mu <- c.get("mu")(using ds)
      l <- c.get("L")(using ms)
      v <- c.get("value")(using d)
    yield MultiNormalCase(source, scipy, y, mu, l, v)

  private given lkjConstantCase: Decoder[LkjConstantCase] = Decoder.instance: c =>
    for
      k <- c.get[Int]("K")
      eta <- c.get("eta")(using d)
      constant <- c.get("constant")(using d)
    yield LkjConstantCase(k, eta, constant)

  private given lkjCholeskyCase: Decoder[LkjCholeskyCase] = Decoder.instance: c =>
    for
      k <- c.get[Int]("K")
      eta <- c.get("eta")(using d)
      l <- c.get("L")(using ms)
      v <- c.get("lkj_corr_cholesky")(using d)
    yield LkjCholeskyCase(k, eta, l, v)

  private given matVecCase: Decoder[MatVecCase] = Decoder.instance: c =>
    for
      a <- c.get("A")(using ms)
      x <- c.get("x")(using ds)
      v <- c.get("value")(using ds)
    yield MatVecCase(a, x, v)

  private given choleskyCase: Decoder[CholeskyCase] = Decoder.instance: c =>
    for
      s <- c.get("S")(using ms)
      v <- c.get("value")(using ms)
    yield CholeskyCase(s, v)

  private given lowerSolveCase: Decoder[LowerSolveCase] = Decoder.instance: c =>
    for
      l <- c.get("L")(using ms)
      b <- c.get("b")(using ds)
      v <- c.get("value")(using ds)
    yield LowerSolveCase(l, b, v)

  private given gatherCase: Decoder[GatherCase] = Decoder.instance: c =>
    for
      values <- c.get("values")(using ds)
      index <- c.get[List[Int]]("index")
      v <- c.get("value")(using ds)
    yield GatherCase(values, IArray.unsafeFromArray(index.toArray), v)

  /** The 13 multivariate normal cases of `multilevel.json`. */
  def multiNormalCases: Either[String, List[MultiNormalCase]] =
    at[List[MultiNormalCase]](Multilevel, List("multi_normal_cholesky", "cases"))

  /** The prose descriptions of what `multi_normal_cholesky` must refuse. */
  def multiNormalInvalid: Either[String, List[String]] =
    at[List[String]](Multilevel, List("multi_normal_cholesky", "invalid"))

  /** The 20 LKJ normalising constants. */
  def lkjConstants: Either[String, List[LkjConstantCase]] =
    at[List[LkjConstantCase]](Multilevel, List("lkj", "constant_cases"))

  /** The 9 LKJ densities on a Cholesky factor. */
  def lkjCholesky: Either[String, List[LkjCholeskyCase]] =
    at[List[LkjCholeskyCase]](Multilevel, List("lkj", "cholesky_cases"))

  /** The linear-algebra cases of one operation. */
  def matVecCases: Either[String, List[MatVecCase]] = linalg[MatVecCase]("mat_vec")
  def choleskyCases: Either[String, List[CholeskyCase]] = linalg[CholeskyCase]("cholesky")
  def lowerSolveCases: Either[String, List[LowerSolveCase]] =
    linalg[LowerSolveCase]("lower_triangular_solve")
  def gatherCases: Either[String, List[GatherCase]] = linalg[GatherCase]("gather")

  private val Multilevel = "multilevel/multilevel.json"

  private def linalg[A](operation: String)(using Decoder[A]): Either[String, List[A]] =
    at[List[Json]](Multilevel, List("linear_algebra")).flatMap: cases =>
      cases
        .filter(_.hcursor.get[String]("op").contains(operation))
        .traverse(_.as[A].left.map(f => s"$Multilevel, $operation: ${f.getMessage}"))

  /** One section of `worked_examples.json`, left as JSON: every section has its own shape. */
  def workedExample(section: String): Either[String, Json] =
    at[Json]("python/worked_examples.json", List(section))

  private def at[A](path: String, fields: List[String])(using Decoder[A]): Either[String, A] =
    Reference
      .json(path)
      .left
      .map(_.message)
      .flatMap: json =>
        fields
          .foldLeft(json.hcursor: io.circe.ACursor)((c, f) => c.downField(f))
          .as[A]
          .left
          .map(f => s"$path at ${fields.mkString(".")}: ${f.getMessage}")
