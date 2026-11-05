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

/** One case of a scalar constraint transform with a single bound: `lb` and `ub`. */
final case class BoundCase(x: Double, bound: Double, value: Double, logJacobian: Double)

/** One `lub` case. `numerical` is absent where the fixture could not form a finite difference. */
final case class IntervalCase(
    x: Double,
    lower: Double,
    upper: Double,
    value: Double,
    logJacobian: Double,
    numerical: Option[Double]
)

final case class OffsetMultiplierCase(
    x: Double,
    offset: Double,
    multiplier: Double,
    value: Double,
    logJacobian: Double
)

/** One case of a transform on a whole vector: `ordered`, `sum_to_zero`, `simplex`.
  *
  * `sum_to_zero` carries no log Jacobian because it has none to carry — the map is linear and
  * volume preserving, and the fixture states the sum instead. `simplex` carries a round trip
  * because its inverse is the one worth asserting separately.
  */
final case class VectorTransformCase(
    y: IArray[Double],
    value: IArray[Double],
    logJacobian: Option[Double],
    numerical: Option[Double],
    sum: Option[Double],
    roundTrip: Option[IArray[Double]]
)

/** One case Stan's own transform tests assert. The fields differ from one test to the next, so
  * everything but the name is optional and the suite names the case it is reading.
  */
final case class StanAssertedTransform(
    test: String,
    x: Option[Double],
    lower: Option[Double],
    upper: Option[Double],
    y: Option[IArray[Double]],
    free: Option[IArray[Double]],
    xVector: Option[IArray[Double]],
    scalarValue: Option[Double],
    vectorValue: Option[IArray[Double]],
    lpBefore: Option[Double],
    lpAfter: Option[Double]
):
  /** What the transform added to Stan's accumulator, where the case states both ends of it. */
  def logJacobian: Option[Double] = lpBefore.zip(lpAfter).map((before, after) => after - before)

/** One case of `cholesky_corr_constrain`. */
final case class CholeskyCorrCase(
    k: Int,
    y: IArray[Double],
    l: IArray[IArray[Double]],
    logJacobian: Double,
    numerical: Double
)

/** One case of `expl_leapfrog_test.cpp`, in Stan's own variables.
  *
  * Stan integrates the **potential** `V = -log p(q)` and its gradient `g = dV/dq`, which are the
  * negatives of what this library carries in a `Point`. The suite converts, and the conversion is
  * the thing worth being explicit about: a sign error there is a sampler that walks uphill.
  */
final case class LeapfrogCase(
    test: String,
    potential: Double,
    position: Double,
    momentum: Double,
    gradient: Double,
    epsilon: Double,
    operations: List[String],
    repeat: Int,
    expectedPotential: Double,
    expectedPosition: Double,
    expectedMomentum: Double,
    expectedGradient: Double,
    tolerance: Double
)

/** What `build_tree` must return, in Stan's names. */
final case class TreeExpectation(
    validSubtree: Boolean,
    rho: IArray[Double],
    momentumBegin: IArray[Double],
    sharpBegin: IArray[Double],
    momentumEnd: IArray[Double],
    sharpEnd: IArray[Double],
    finalPosition: IArray[Double],
    finalMomentum: IArray[Double],
    leapfrogSteps: Int,
    logSumWeight: Double,
    sumMetropolisProbability: Double
)

/** `unit_e_nuts_test.cpp`'s `build_tree_test`: the setup, what Stan asserts, and what the
  * independent port computed for the same setup.
  *
  * Stan's assertions are `EXPECT_FLOAT_EQ`, so they carry six digits; the port carries sixteen.
  * Both are asserted, at their own precision.
  */
final case class BuildTreeCase(
    position: IArray[Double],
    momentum: IArray[Double],
    stepSize: Double,
    depth: Int,
    initialEnergy: Double,
    sign: Double,
    stan: TreeExpectation,
    port: TreeExpectation
)

/** One case of the generalised U-turn criterion. */
final case class CriterionCase(
    sharpStart: IArray[Double],
    sharpEnd: IArray[Double],
    rho: IArray[Double],
    expected: Boolean
)

/** One warmup length and the window schedule `windowed_adaptation.hpp` gives it. */
final case class WindowCase(
    warmup: Int,
    adapt: Boolean,
    initBuffer: Int,
    termBuffer: Int,
    baseWindow: Int,
    ends: List[Int]
)

/** The dual-averaging trajectory of `stepsize_adaptation.hpp`, step by step. */
final case class DualAveragingCase(
    mu: Double,
    delta: Double,
    gamma: Double,
    kappa: Double,
    t0: Double,
    acceptStats: IArray[Double],
    stepSizes: IArray[Double],
    finalStepSize: Double
)

/** One diagnostic Stan's own tests assert, with what the independent port and ArviZ got.
  *
  * `arviz` is kept because three of these differ there by 0.5 % to 15 %, and the suite asserts the
  * **disagreement**: following Stan rather than ArviZ is a decision, and a decision that no test
  * mentions is one that can be reversed by accident.
  */
final case class DiagnosticCase(
    column: String,
    statistic: String,
    expected: Double,
    tolerance: Double,
    source: String,
    port: Double,
    arviz: Option[Double]
)

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

  private given boundCase: Decoder[BoundCase] = Decoder.instance: c =>
    for
      x <- c.get("x")(using d)
      bound <- c.get("lower")(using d).orElse(c.get("upper")(using d))
      v <- c.get("value")(using d)
      j <- c.get("log_jacobian")(using d)
    yield BoundCase(x, bound, v, j)

  private given intervalCase: Decoder[IntervalCase] = Decoder.instance: c =>
    for
      x <- c.get("x")(using d)
      lower <- c.get("lower")(using d)
      upper <- c.get("upper")(using d)
      v <- c.get("value")(using d)
      j <- c.get("log_jacobian")(using d)
    yield IntervalCase(x, lower, upper, v, j, present(c.get("numerical_log_jacobian")(using d)))

  private given offsetMultiplierCase: Decoder[OffsetMultiplierCase] = Decoder.instance: c =>
    for
      x <- c.get("x")(using d)
      offset <- c.get("offset")(using d)
      multiplier <- c.get("multiplier")(using d)
      v <- c.get("value")(using d)
      j <- c.get("log_jacobian")(using d)
    yield OffsetMultiplierCase(x, offset, multiplier, v, j)

  private given vectorTransformCase: Decoder[VectorTransformCase] = Decoder.instance: c =>
    for
      y <- c.get("y")(using ds)
      v <- c.get("value")(using ds)
    yield VectorTransformCase(
      y,
      v,
      present(c.get("log_jacobian")(using d)),
      present(
        c.get("numerical_log_jacobian")(using d)
          .orElse(c.get("numerical_log_jacobian_first_n")(using d))
      ),
      present(c.get("sum")(using d)),
      c.get("round_trip")(using ds).toOption
    )

  private given stanAssertedTransform: Decoder[StanAssertedTransform] = Decoder.instance: c =>
    c.get[String]("test").map { name =>
      StanAssertedTransform(
        name,
        present(c.get("x")(using d)),
        present(c.get("lower")(using d)),
        present(c.get("upper")(using d)),
        c.get("y")(using ds).toOption,
        c.get("free")(using ds).toOption,
        c.get("x")(using ds).toOption,
        present(c.get("value")(using d)),
        c.get("value")(using ds).toOption,
        present(c.get("lp_before")(using d)),
        present(c.get("lp_after")(using d))
      )
    }

  private given choleskyCorrCase: Decoder[CholeskyCorrCase] = Decoder.instance: c =>
    for
      k <- c.get[Int]("K")
      y <- c.get("y")(using ds)
      l <- c.get("L")(using ms)
      j <- c.get("log_jacobian")(using d)
      n <- c.get("numerical_log_jacobian")(using d)
    yield CholeskyCorrCase(k, y, l, j, n)

  /** A field the fixture states, as opposed to one it writes `null` for.
    *
    * [[Reference.looseDouble]] reads Python's `null` as a `NaN`, which is right for a reference
    * value that really is `NaN` and wrong for a finite difference the fixture could not form. The
    * transform files use `null` only in the second sense.
    */
  private def present(decoded: Decoder.Result[Double]): Option[Double] =
    decoded.toOption.filterNot(_.isNaN)

  private val Transforms = "stan-math/transforms.json"

  /** The 18 `lb` and 9 `ub` cases of `transforms.json`. */
  def lowerBoundCases: Either[String, List[BoundCase]] =
    at[List[BoundCase]](Transforms, List("cases", "lb"))

  def upperBoundCases: Either[String, List[BoundCase]] =
    at[List[BoundCase]](Transforms, List("cases", "ub"))

  /** The 27 `lub` cases. */
  def intervalCases: Either[String, List[IntervalCase]] =
    at[List[IntervalCase]](Transforms, List("cases", "lub"))

  def offsetMultiplierCases: Either[String, List[OffsetMultiplierCase]] =
    at[List[OffsetMultiplierCase]](Transforms, List("cases", "offset_multiplier"))

  /** The 15 cases of one vector transform: `ordered`, `sum_to_zero` or `simplex`. */
  def vectorTransformCases(name: String): Either[String, List[VectorTransformCase]] =
    at[List[VectorTransformCase]](Transforms, List("cases", name))

  /** The 5 cases Stan's own transform tests assert. */
  def stanAssertedTransforms: Either[String, List[StanAssertedTransform]] =
    at[List[StanAssertedTransform]](Transforms, List("stan_asserted"))

  /** How far the fixture's own numerical Jacobians were from its closed forms. */
  def numericalJacobianError: Either[String, Double] =
    at[Double](Transforms, List("max_abs_numerical_jacobian_error"))(using d)

  /** The 12 `cholesky_corr_constrain` cases of `multilevel.json`. */
  def choleskyCorrCases: Either[String, List[CholeskyCorrCase]] =
    at[List[CholeskyCorrCase]](Multilevel, List("cholesky_corr_transform", "cases"))

  /** The data block of a posteriordb model, left as JSON: every model's has its own shape.
    *
    * `eight_schools` is Rubin (1981), which is public and is the model the manual uses; the
    * suites decode the fields they need.
    */
  def posteriordbData(posterior: String): Either[String, Json] =
    at[Json](s"posteriordb/$posterior/data.json", Nil)

  private val Hmc = "stan/hmc/hmc.json"

  private given leapfrogCase: Decoder[LeapfrogCase] = Decoder.instance: c =>
    def at(field: String, key: String): Decoder.Result[Double] =
      c.downField(field).downField(key).as[Double](using d)
    for
      name <- c.get[String]("test")
      v <- at("initial", "V")
      q <- at("initial", "q(0)")
      p <- at("initial", "p(0)")
      g <- at("initial", "g(0)")
      epsilon <- c.get("epsilon")(using d)
      operations <- c.get[List[String]]("operations")
      repeat <- c.get[Int]("repeat")
      ev <- at("expected", "V")
      eq <- at("expected", "q(0)")
      ep <- at("expected", "p(0)")
      eg <- at("expected", "g(0)")
      tolerance <- c.get("tolerance")(using d)
    yield LeapfrogCase(
      name,
      v,
      q,
      p,
      g,
      epsilon,
      operations,
      repeat,
      ev,
      eq,
      ep,
      eg,
      tolerance
    )

  private given treeExpectation: Decoder[TreeExpectation] = Decoder.instance: c =>
    for
      valid <- c.get[Boolean]("valid_subtree")
      rho <- c.get("rho")(using ds)
      pBegin <- c.get("p_begin")(using ds)
      sBegin <- c.get("p_sharp_begin")(using ds)
      pEnd <- c.get("p_end")(using ds)
      sEnd <- c.get("p_sharp_end")(using ds)
      q <- c.get("final_q")(using ds)
      p <- c.get("final_p")(using ds)
      steps <- c.get[Int]("n_leapfrog")
      weight <- c.get("log_sum_weight")(using d)
      metropolis <- c.get("sum_metro_prob")(using d)
    yield TreeExpectation(valid, rho, pBegin, sBegin, pEnd, sEnd, q, p, steps, weight, metropolis)

  private given criterionCase: Decoder[CriterionCase] = Decoder.instance: c =>
    for
      start <- c.get("p_sharp_start")(using ds)
      end <- c.get("p_sharp_end")(using ds)
      rho <- c.get("rho")(using ds)
      expected <- c.get[Boolean]("expected")
    yield CriterionCase(start, end, rho, expected)

  /** The 11 leapfrog cases of `expl_leapfrog_test.cpp`. */
  def leapfrogCases: Either[String, List[LeapfrogCase]] =
    at[List[LeapfrogCase]](Hmc, List("leapfrog"))

  /** `build_tree_test`, with Stan's assertions and the port's own values. */
  def buildTreeCase: Either[String, BuildTreeCase] =
    for
      setup <- at[Json](Hmc, List("nuts_build_tree_unit_e", "setup"))
      stan <- at[TreeExpectation](Hmc, List("nuts_build_tree_unit_e", "expected"))
      port <- at[TreeExpectation](Hmc, List("nuts_build_tree_unit_e", "numpy_port"))
      cursor = setup.hcursor
      q <- cursor.get("q")(using ds).left.map(_.getMessage)
      p <- cursor.get("p")(using ds).left.map(_.getMessage)
      stepSize <- cursor.get("stepsize")(using d).left.map(_.getMessage)
      depth <- cursor.get[Int]("depth").left.map(_.getMessage)
      energy <- cursor.get("H0")(using d).left.map(_.getMessage)
      sign <- cursor.get("sign")(using d).left.map(_.getMessage)
    yield BuildTreeCase(q, p, stepSize, depth, energy, sign, stan, port)

  /** The 2 cases of the generalised U-turn criterion. */
  def criterionCases: Either[String, List[CriterionCase]] =
    at[List[CriterionCase]](Hmc, List("nuts_criterion"))

  /** The 9 window schedules of `windowed_adaptation.hpp`. */
  def windowCases: Either[String, List[WindowCase]] =
    at[Map[String, Json]](Hmc, List("adaptation", "windows", "cases")).flatMap { cases =>
      cases.toList
        .sortBy((warmup, _) => warmup.toInt)
        .traverse { (warmup, json) =>
          val cursor = json.hcursor
          for
            adapt <- cursor.get[Boolean]("adapt").left.map(_.getMessage)
            ends <- cursor.getOrElse[List[Int]]("ends")(Nil).left.map(_.getMessage)
            initBuffer <- cursor.getOrElse[Int]("init_buffer")(0).left.map(_.getMessage)
            termBuffer <- cursor.getOrElse[Int]("term_buffer")(0).left.map(_.getMessage)
            baseWindow <- cursor.getOrElse[Int]("base_window")(0).left.map(_.getMessage)
          yield WindowCase(warmup.toInt, adapt, initBuffer, termBuffer, baseWindow, ends)
        }
    }

  /** The dual-averaging trajectory the port computed, and Stan's own single assertion. */
  def dualAveragingCase: Either[String, DualAveragingCase] =
    at[Json](Hmc, List("adaptation", "dual_averaging_port_trajectory")).flatMap { json =>
      val cursor = json.hcursor
      for
        mu <- cursor.get("mu")(using d).left.map(_.getMessage)
        delta <- cursor.get("delta")(using d).left.map(_.getMessage)
        gamma <- cursor.get("gamma")(using d).left.map(_.getMessage)
        kappa <- cursor.get("kappa")(using d).left.map(_.getMessage)
        t0 <- cursor.get("t0")(using d).left.map(_.getMessage)
        stats <- cursor.get("accept_stats")(using ds).left.map(_.getMessage)
        steps <- cursor.get("stepsizes")(using ds).left.map(_.getMessage)
        last <- cursor.get("final_stepsize_exp_xbar")(using d).left.map(_.getMessage)
      yield DualAveragingCase(mu, delta, gamma, kappa, t0, stats, steps, last)
    }

  /** The one step size `stepsize_adaptation_test.cpp` asserts: `mu = 0`, `delta = 0.65`, one
    * acceptance statistic equal to `delta`, and the step size must stay at 1.
    */
  def dualAveragingStanCase: Either[String, (Double, Double, Double, Double)] =
    at[Json](Hmc, List("adaptation", "dual_averaging_stan_test")).flatMap { json =>
      val cursor = json.hcursor
      for
        mu <- cursor.get("mu")(using d).left.map(_.getMessage)
        delta <- cursor.get("delta")(using d).left.map(_.getMessage)
        stats <- cursor.get("accept_stats")(using ds).left.map(_.getMessage)
        expected <- cursor.get("expected_stepsize")(using d).left.map(_.getMessage)
      yield (mu, delta, stats(0), expected)
    }

  /** What the variance regulariser must give for ten identical draws in ten dimensions. */
  def varianceRegularization: Either[String, Double] =
    at[Double](
      Hmc,
      List("adaptation", "variance_regularization_stan_test", "expected_each_component")
    )(using
      d
    )

  private val Diagnostics = "stan/diagnostics/expected.json"

  /** The 14 values of `stan/diagnostics/expected.json`: 7 statistics for each of 2 columns. */
  def diagnosticCases: Either[String, List[DiagnosticCase]] =
    at[Map[String, Map[String, Json]]](Diagnostics, List("values")).flatMap { columns =>
      columns.toList
        .sortBy((column, _) => column)
        .flatTraverse { (column, statistics) =>
          statistics.toList.sortBy((name, _) => name).traverse { (name, json) =>
            val cursor = json.hcursor
            for
              expected <- cursor.get("expected")(using d).left.map(_.getMessage)
              tolerance <- cursor.get("tolerance")(using d).left.map(_.getMessage)
              source <- cursor.get[String]("source").left.map(_.getMessage)
              port <- cursor.get("numpy_port")(using d).left.map(_.getMessage)
            yield DiagnosticCase(
              column,
              name,
              expected,
              tolerance,
              source,
              port,
              cursor.get("arviz")(using d).toOption
            )
          }
        }
    }

  /** The prose list of edge cases the fixture states, so a suite can count them. */
  def diagnosticEdgeCases: Either[String, List[String]] =
    at[List[String]](Diagnostics, List("edge_cases"))

  /** The first autocovariances of `ar1.csv` that Stan's own test asserts, and its tolerance. */
  def autocovarianceCase: Either[String, (IArray[Double], Double, IArray[Double])] =
    for
      expected <- at[IArray[Double]](Diagnostics, List("autocovariance_ar1", "expected_first_6"))(
        using ds
      )
      tolerance <- at[Double](Diagnostics, List("autocovariance_ar1", "tolerance"))(using d)
      port <- at[IArray[Double]](
        Diagnostics,
        List("autocovariance_ar1", "numpy_biased_estimator")
      )(using ds)
    yield (expected, tolerance, port)

  /** One column of the four Bernoulli chains, as four chains of a thousand draws.
    *
    * Read by `Reference.csvColumns`, which skips the `#` comments and takes the names from the
    * first remaining line — a reader of its own, so that `stan-io`'s parser is checked against
    * something and not against itself.
    */
  def bernoulliChains(column: String): Either[String, IArray[IArray[Double]]] =
    (1 to 4).toList
      .traverse { c =>
        Reference
          .csvColumns(s"stan/diagnostics/bern$c.csv", List(column))
          .left
          .map(_.message)
          .flatMap(_.get(column).toRight(s"bern$c.csv has no column '$column'"))
      }
      .map(chains => IArray.unsafeFromArray(chains.toArray))

  /** The AR(1) series, one value a line and no header. */
  def ar1Series: Either[String, IArray[Double]] =
    Reference.csvValues("stan/diagnostics/ar1.csv").left.map(_.message)

  /** The raw lines of one Bernoulli chain's file, for the CSV round trip. */
  def bernoulliCsv(chain: Int): Either[String, List[String]] =
    Reference
      .text(s"stan/diagnostics/bern$chain.csv")
      .left
      .map(_.message)
      .map(_.linesIterator.toList)

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
