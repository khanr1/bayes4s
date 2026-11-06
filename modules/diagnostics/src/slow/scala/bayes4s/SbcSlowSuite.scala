package bayes4s

import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** Simulation-based calibration on five small models, with enough replications for its answer to
  * mean something.
  *
  * This is the only test in the library that checks a model's **two readings against each other**
  * end to end: the prior as a density against the prior as a sampler, the likelihood as a density
  * against the likelihood as a simulator, every transform's Jacobian, and the sampler itself. If
  * any one of them disagrees with another the ranks stop being uniform, and nothing else would
  * notice.
  *
  * The threshold: 256 replications in 16 bins of 4 possible ranks each, against the 0.1 % point of
  * chi-squared with 15 degrees of freedom, 37.697. One check per parameter, so the five models
  * together make 18 checks and the announced failure rate is about 1 in 55 runs.
  *
  * The last two are phase 6b's: varying intercepts and slopes with a correlation between them,
  * reduced to six groups, and a multilevel logistic regression. The first of the two is the only
  * model here whose generative part names a quantity the model **derives** rather than declares —
  * `rho`, read off the Cholesky factor — and ranking it is a stronger test than ranking a
  * parameter, because the correlation is where an LKJ prior, the factor's transform and its
  * Jacobian all have to agree at once.
  */
object SbcSlowSuite extends SimpleIOSuite with Approx:

  private val stress: Boolean = sys.props.get("bayes4s.stress").contains("true")
  private val root: Long = if stress then System.currentTimeMillis() else 20261105L
  private val replications = if stress then 512 else 256
  private val bins = 16
  private val thinnedDraws = 63

  /** The 0.1 % point of chi-squared with 15 degrees of freedom. */
  private val critical = 37.697

  private val settings = RunSettings(chains = 2, warmup = 400, samples = 500)

  private def calibrated[T[_], Y](
      name: String,
      model: GenerativeModel[T, Y],
      expected: Set[String]
  ) =
    test(s"$name is calibrated ($replications replications, seed $root)") {
      cats.effect.IO {
        Sbc.run(model, settings, replications, thinnedDraws).runA(Seed(root)).value match
          case Left(error) => failure(s"$name: ${error.message}")
          case Right(result) =>
            val statistics = result.uniformityChiSquared(bins)
            val assertions = forEach(statistics.toList.sortBy((parameter, _) => parameter)) {
              (parameter, statistic) =>
                check(
                  statistic <= critical,
                  s"$name, $parameter: the rank histogram gives chi-squared " +
                    s"${String.format(java.util.Locale.ROOT, "%.2f", statistic)} over $bins bins, " +
                    s"past the 0.1 % point of $critical"
                )
            }
            check(
              statistics.keySet == expected,
              s"$name: the parameters are ${statistics.keySet.toList.sorted}, expected " +
                expected.toList.sorted.mkString(", ")
            ).and(assertions)
      }
    }

  calibrated("a Gaussian location and scale", CalibrationModels.locationScale(12), Set("mu", "sigma"))

  calibrated("a Gamma-Poisson rate", CalibrationModels.gammaPoisson(20), Set("rate"))

  calibrated(
    "four varying levels, non-centred",
    CalibrationModels.varyingLevels(4),
    Set("mu", "tau", "z.1", "z.2", "z.3", "z.4")
  )

  calibrated(
    "correlated varying intercepts and slopes, six groups",
    MultilevelModels.correlatedGenerative(6, 5),
    Set("mu.1", "mu.2", "tau.1", "tau.2", "sigma", "rho")
  )

  calibrated(
    "a multilevel logistic regression",
    MultilevelModels.logisticGenerative(6, 10),
    Set("mu", "tau", "beta")
  )
