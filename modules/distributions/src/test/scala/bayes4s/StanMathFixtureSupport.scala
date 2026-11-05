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

import bayes4s.testkit.StanMathFixture

/** The mapping between the Stan Math fixture and this library, written once.
  *
  * Three things here would silently weaken the suites if they were left implicit.
  *
  * First, the fixture names the same parameter differently from one function to the next —
  * `Location` and `Scale` in the cumulative tests against `mu` and `sigma` in the density test —
  * so a rejection suite that compared raw names would pass without asserting anything.
  *
  * Second, and worse, the same name can mean **different things** in two functions of the same
  * law: `bernoulli` calls its parameter `theta` in both `lpdf` and `lpdf_logit`, where it is a
  * probability in the first and a log odds in the second. The mapping is therefore keyed by law,
  * function and name together; keyed by law and name alone it would quietly send a log odds to a
  * constructor expecting a probability.
  *
  * Third, only part of the fixture is in scope, and which part has to be a value the coverage
  * suite can count rather than a comment.
  */
object StanMathFixtureSupport:

  /** The laws of the fixture that this library implements. */
  val lawsInScope: Set[String] = Set(
    "normal",
    "std_normal",
    "lognormal",
    "gamma",
    "beta",
    "logistic",
    "poisson",
    "neg_binomial_2",
    "neg_binomial",
    "binomial",
    "bernoulli",
    "student_t",
    "cauchy",
    "exponential",
    "beta_binomial"
  )

  /** The function families this phase implements: the densities and the alternative
    * parameterisations we have laws for, not the cumulatives.
    */
  def densityFamily(law: String, function: String): Boolean = function match
    case "lpdf" => true
    case "lpdf_log_param" => law == "poisson"
    case "lpdf_logit" => law == "bernoulli" || law == "binomial"
    case _ => false

  /** The fixture parameters this library has no constructor for, and why.
    *
    * `neg_binomial_2_log` is an alternative parameterisation we do not provide: the specification
    * lists `poisson_log` and not its negative binomial counterpart. Its cases are counted as
    * deferred rather than mapped onto `mu`, which they are not.
    */
  val notYetImplemented: Set[(String, String, String)] = Set(
    ("neg_binomial_2", "lpdf_log_param", "eta")
  )

  /** Our constructor's name for a fixture parameter, or `None` when we have no such parameter. */
  def parameterName(law: String, function: String, fixtureName: String): Option[String] =
    if notYetImplemented((law, function, fixtureName)) then None
    else
      (law, function, fixtureName) match
        case ("bernoulli", "lpdf_logit", "theta") => Some("eta")
        case ("binomial", "lpdf_logit", "alpha") => Some("eta")
        case ("logistic", _, "Location") => Some("mu")
        case ("logistic", _, "Scale") => Some("sigma")
        case ("poisson", _, "lambda") => Some("rate")
        case ("poisson", "lpdf_log_param", "alpha") => Some("logRate")
        case ("neg_binomial", _, "Successes") => Some("alpha")
        case ("neg_binomial", _, "logit") => Some("beta")
        case (_, _, "Trials" | "N") => Some("trials")
        case (_, _, "Probability" | "theta") => Some("probability")
        case (_, _, other) => Some(other)

  /** The parameters each smart constructor takes, used to prove no fixture name went untranslated. */
  val parametersOf: Map[String, Set[String]] = Map(
    "normal" -> Set("mu", "sigma"),
    "std_normal" -> Set.empty,
    "lognormal" -> Set("mu", "sigma"),
    "gamma" -> Set("alpha", "beta"),
    "beta" -> Set("alpha", "beta"),
    "logistic" -> Set("mu", "sigma"),
    "poisson" -> Set("rate", "logRate"),
    "neg_binomial_2" -> Set("mu", "phi"),
    "neg_binomial" -> Set("alpha", "beta"),
    "binomial" -> Set("trials", "probability", "eta"),
    "bernoulli" -> Set("probability", "eta"),
    "student_t" -> Set("nu", "mu", "sigma"),
    "cauchy" -> Set("mu", "sigma"),
    "exponential" -> Set("beta"),
    "beta_binomial" -> Set("trials", "alpha", "beta")
  )

  /** The density of a covered law at a fixture parameter vector, position 0 being the variate.
    *
    * `bernoulli_lpmf` goes through `Binomial(1, theta)`, which is the same mass function exactly;
    * the specification lists no plain Bernoulli, and adding one would be a public law that says
    * nothing new.
    */
  def density(law: String, function: String, p: IArray[Double]): Either[String, Double] =
    def of[L](built: Either[BayesError.InvalidArgument, L])(
        f: L => Double
    ): Either[String, Double] =
      built.left.map(_.message).map(f)
    (law, function) match
      case ("normal", "lpdf") => of(Normal.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("std_normal", "lpdf") => Right(Normal.stdNormal.logPdf(p(0)))
      case ("lognormal", "lpdf") => of(LogNormal.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("gamma", "lpdf") => of(Gamma.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("beta", "lpdf") => of(Beta.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("logistic", "lpdf") => of(Logistic.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("poisson", "lpdf") => of(Poisson.of(p(1)))(_.logMass(p(0).toInt))
      case ("poisson", "lpdf_log_param") => of(PoissonLog.of(p(1)))(_.logMass(p(0).toInt))
      case ("neg_binomial_2", "lpdf") => of(NegBinomial2.of(p(1), p(2)))(_.logMass(p(0).toInt))
      case ("neg_binomial", "lpdf") => of(NegBinomial.of(p(1), p(2)))(_.logMass(p(0).toInt))
      case ("binomial", "lpdf") => of(Binomial.of(p(1).toInt, p(2)))(_.logMass(p(0).toInt))
      case ("binomial", "lpdf_logit") =>
        of(BinomialLogit.of(p(1).toInt, p(2)))(_.logMass(p(0).toInt))
      case ("bernoulli", "lpdf") => of(Binomial.of(1, p(1)))(_.logMass(p(0).toInt))
      case ("bernoulli", "lpdf_logit") => of(BernoulliLogit.of(p(1)))(_.logMass(p(0).toInt))
      case ("student_t", "lpdf") => of(StudentT.of(p(1), p(2), p(3)))(_.logPdf(p(0)))
      case ("cauchy", "lpdf") => of(Cauchy.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("exponential", "lpdf") => of(Exponential.of(p(1)))(_.logPdf(p(0)))
      case ("beta_binomial", "lpdf") =>
        of(BetaBinomial.of(p(1).toInt, p(2), p(3)))(_.logMass(p(0).toInt))
      case _ => Left(s"no density mapping for $law.$function")

  /** The smart constructor of a covered law, at a fixture parameter vector. */
  def construct(
      law: String,
      function: String,
      p: IArray[Double]
  ): Either[String, Either[BayesError.InvalidArgument, Any]] =
    (law, function) match
      case ("normal", _) => Right(Normal.of(p(1), p(2)))
      case ("lognormal", _) => Right(LogNormal.of(p(1), p(2)))
      case ("gamma", _) => Right(Gamma.of(p(1), p(2)))
      case ("beta", _) => Right(Beta.of(p(1), p(2)))
      case ("logistic", _) => Right(Logistic.of(p(1), p(2)))
      case ("poisson", "lpdf_log_param") => Right(PoissonLog.of(p(1)))
      case ("poisson", _) => Right(Poisson.of(p(1)))
      case ("neg_binomial_2", _) => Right(NegBinomial2.of(p(1), p(2)))
      case ("neg_binomial", _) => Right(NegBinomial.of(p(1), p(2)))
      case ("binomial", "lpdf_logit") => Right(BinomialLogit.of(p(1).toInt, p(2)))
      case ("binomial", _) => Right(Binomial.of(p(1).toInt, p(2)))
      case ("bernoulli", "lpdf_logit") => Right(BernoulliLogit.of(p(1)))
      case ("bernoulli", _) => Right(Binomial.of(1, p(1)))
      case ("student_t", _) => Right(StudentT.of(p(1), p(2), p(3)))
      case ("cauchy", _) => Right(Cauchy.of(p(1), p(2)))
      case ("exponential", _) => Right(Exponential.of(p(1)))
      case ("beta_binomial", _) => Right(BetaBinomial.of(p(1).toInt, p(2), p(3)))
      case other => Left(s"no constructor mapping for $other")

  /** A valid parameter vector for a law and function, taken from the fixture itself. */
  def base(fixture: StanMathFixture, law: String, function: String): Option[IArray[Double]] =
    fixture
      .valid(law, function)
      .headOption
      .map(_.params)
      .orElse(fixture.valid(law, "lpdf").headOption.map(_.params))

  def substituted(p: IArray[Double], index: Int, value: Double): IArray[Double] =
    IArray.from(p.toList.updated(index, value))

  /** One fixture case read by the `Real[A]` layer: the continuous arguments as the inputs of a
    * [[DiffFn]], the integer ones captured.
    *
    * The same value can then be obtained from three interpreters — `Double` for the comparison
    * against Stan Math, the tape and the dual numbers for the gradient — without writing the
    * density three times. The integers are captured rather than passed as inputs because a count
    * is not something to differentiate with respect to.
    */
  final case class RealCase(inputs: IArray[Double], fn: DiffFn)

  /** The density of a covered law, written against `Real[A]`.
    *
    * `bernoulli_lpmf` goes through `LogMass.binomial(k, 1, theta)` for the same reason the law
    * layer goes through `Binomial(1, theta)`: it is the same mass function, exactly.
    */
  def realCase(law: String, function: String, p: IArray[Double]): Either[String, RealCase] =
    def scalar(n: Int)(body: [A] => IArray[A] => Real[A] ?=> A): Either[String, RealCase] =
      Right(
        RealCase(
          IArray.from(p.toList.slice(p.length - n, p.length)),
          new DiffFn:
            def apply[A: Real](x: IArray[A]): A = body[A](x)
        )
      )
    val k = if p.isEmpty then 0 else p(0).toInt
    (law, function) match
      case ("normal", "lpdf") =>
        scalar(3)([A] => (x: IArray[A]) => LogDensity.normal(x(0), x(1), x(2)))
      case ("std_normal", "lpdf") =>
        scalar(1)([A] =>
          (x: IArray[A]) => LogDensity.normal(x(0), Real[A].const(0.0), Real[A].const(1.0))
        )
      case ("lognormal", "lpdf") =>
        scalar(3)([A] => (x: IArray[A]) => LogDensity.logNormal(x(0), x(1), x(2)))
      case ("gamma", "lpdf") =>
        scalar(3)([A] => (x: IArray[A]) => LogDensity.gamma(x(0), x(1), x(2)))
      case ("beta", "lpdf") =>
        scalar(3)([A] => (x: IArray[A]) => LogDensity.beta(x(0), x(1), x(2)))
      case ("logistic", "lpdf") =>
        scalar(3)([A] => (x: IArray[A]) => LogDensity.logistic(x(0), x(1), x(2)))
      case ("student_t", "lpdf") =>
        scalar(4)([A] => (x: IArray[A]) => LogDensity.studentT(x(0), x(1), x(2), x(3)))
      case ("cauchy", "lpdf") =>
        scalar(3)([A] => (x: IArray[A]) => LogDensity.cauchy(x(0), x(1), x(2)))
      case ("exponential", "lpdf") =>
        scalar(2)([A] => (x: IArray[A]) => LogDensity.exponential(x(0), x(1)))
      case ("poisson", "lpdf") =>
        scalar(1)([A] => (x: IArray[A]) => LogMass.poisson(k, x(0)))
      case ("poisson", "lpdf_log_param") =>
        scalar(1)([A] => (x: IArray[A]) => LogMass.poissonLog(k, x(0)))
      case ("neg_binomial_2", "lpdf") =>
        scalar(2)([A] => (x: IArray[A]) => LogMass.negativeBinomial2(k, x(0), x(1)))
      case ("neg_binomial", "lpdf") =>
        scalar(2)([A] => (x: IArray[A]) => LogMass.negativeBinomial(k, x(0), x(1)))
      case ("binomial", "lpdf") =>
        val n = p(1).toInt
        scalar(1)([A] => (x: IArray[A]) => LogMass.binomial(k, n, x(0)))
      case ("binomial", "lpdf_logit") =>
        val n = p(1).toInt
        scalar(1)([A] => (x: IArray[A]) => LogMass.binomialLogit(k, n, x(0)))
      case ("bernoulli", "lpdf") =>
        scalar(1)([A] => (x: IArray[A]) => LogMass.binomial(k, 1, x(0)))
      case ("bernoulli", "lpdf_logit") =>
        scalar(1)([A] => (x: IArray[A]) => LogMass.bernoulliLogit(k, x(0)))
      case ("beta_binomial", "lpdf") =>
        val n = p(1).toInt
        scalar(2)([A] => (x: IArray[A]) => LogMass.betaBinomial(k, n, x(0), x(1)))
      case other => Left(s"no Real[A] mapping for $other")
