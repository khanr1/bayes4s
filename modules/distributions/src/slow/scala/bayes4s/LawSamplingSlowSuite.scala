package bayes4s

import bayes4s.testkit.Approx
import bayes4s.testkit.MonteCarlo
import weaver.SimpleIOSuite

/** Every law's sampler against its own moments, at a million draws. Run by `sbt slow:test`.
  *
  * With `-Dbayes4s.stress=true` the seed comes from the clock and is printed, so that a failure
  * found by the weekly run can be replayed as a fixed seed.
  */
object LawSamplingSlowSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val stress: Boolean = sys.props.get("bayes4s.stress").contains("true")
  private val root: Long = if stress then System.currentTimeMillis() else 20261104L
  private val n = 1_000_000


  /** The draws, as a primitive array, by threading the seed through the program one step at a
    * time. `replicateA` would build a chain and a boxed list of a million elements; this builds
    * neither, and stays exactly as pure.
    */
  private def drawArray(program: Rand[Double], from: Seed, count: Int): Array[Double] =
    Iterator
      .iterate((from, 0.0))((s, _) => program.run(s).value)
      .drop(1)
      .take(count)
      .map(_._2)
      .toArray

  private def average(xs: Array[Double]): Double = xs.sum / xs.length


  /** The standard error of a sample variance, from the sample's own fourth moment:
    * sqrt((m4 - s^4) / n). No assumption about the law, which matters here because a gamma of
    * shape 0.05 and a Poisson of mean 0.0025 have fourth moments that the normal formula
    * sigma^2 sqrt(2/n) underestimates by factors of three and five.
    */
  private def varianceStandardError(xs: Array[Double]): Double =
    val m = xs.sum / xs.length
    val squares = xs.map(x => (x - m) * (x - m))
    val second = squares.sum / xs.length
    val fourth = squares.map(s2 => s2 * s2).sum / xs.length
    math.sqrt(math.max(0.0, fourth - second * second) / xs.length)

  private def spread(xs: Array[Double]): Double =
    val m = average(xs)
    xs.map(x => (x - m) * (x - m)).sum / (xs.length - 1)

  private def moments(
      name: String,
      sampler: Rand[Double],
      mean: Double,
      variance: Double,
      index: Long
  ) =
    val xs = drawArray(sampler, Seed(root).fold(index), n)
    withinStandardErrors(
      average(xs),
      mean,
      math.sqrt(variance / n),
      4.0,
      s"the mean of $name (seed $root, n = $n)"
    ).and(
      withinStandardErrors(
        spread(xs),
        variance,
        varianceStandardError(xs),
        4.0,
        s"the variance of $name (seed $root, n = $n)"
      )
    )

  private def continuous(name: String, built: Either[BayesError.InvalidArgument, ContinuousLaw], i: Long) =
    built match
      case Left(error) => failure(error.message)
      case Right(law) => moments(name, law.sample[Rand], law.mean, law.variance, i)

  private def discrete(name: String, built: Either[BayesError.InvalidArgument, DiscreteLaw], i: Long) =
    built match
      case Left(error) => failure(error.message)
      case Right(law) => moments(name, law.sample[Rand].map(_.toDouble), law.mean, law.variance, i)

  pureTest("the continuous laws, at a million draws each") {
    continuous("Normal(1.5, 2)", Normal.of(1.5, 2.0), 1L)
      .and(continuous("HalfNormal(0.25)", HalfNormal.of(0.25), 2L))
      .and(continuous("LogNormal(0, 0.5)", LogNormal.of(0.0, 0.5), 3L))
      .and(continuous("Gamma(0.05, 1)", Gamma.of(0.05, 1.0), 4L))
      .and(continuous("Gamma(0.999, 7)", Gamma.of(0.999, 7.0), 5L))
      .and(continuous("Gamma(400, 3)", Gamma.of(400.0, 3.0), 6L))
      .and(continuous("Beta(0.2, 0.3)", Beta.of(0.2, 0.3), 7L))
      .and(continuous("Beta(80, 20)", Beta.of(80.0, 20.0), 8L))
      .and(continuous("Logistic(-1, 3)", Logistic.of(-1.0, 3.0), 9L))
  }

  pureTest("the discrete laws, at a million draws each") {
    discrete("Poisson(0.01)", Poisson.of(0.01), 11L)
      .and(discrete("Poisson(9.999)", Poisson.of(9.999), 12L))
      .and(discrete("Poisson(10.001)", Poisson.of(10.001), 13L))
      .and(discrete("Poisson(1e5)", Poisson.of(1e5), 14L))
      .and(discrete("PoissonLog(-6)", PoissonLog.of(-6.0), 15L))
      .and(discrete("NegBinomial2(4, 0.5)", NegBinomial2.of(4.0, 0.5), 16L))
      .and(discrete("NegBinomial2(100, 50)", NegBinomial2.of(100.0, 50.0), 17L))
      .and(discrete("NegBinomial(8.5, 1.2)", NegBinomial.of(8.5, 1.2), 18L))
      .and(discrete("Binomial(1000, 0.001)", Binomial.of(1000, 0.001), 19L))
      .and(discrete("Binomial(50, 0.5)", Binomial.of(50, 0.5), 20L))
  }
