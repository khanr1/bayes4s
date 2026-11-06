package bayes4s

import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** Every combination of components, calibrated.
  *
  * The point of making the model an assembly of interchangeable pieces is that a variant costs a
  * component rather than a rewrite — and the price of that is that each combination is a model
  * nobody has checked. This checks all eight: four stories about the rate against two about the
  * delay.
  *
  * The threshold deserves a word. Eight combinations rank six to nine parameters each, which is
  * about fifty-six checks; at the 0.1 % point that would fail by chance about one run in eighteen,
  * and a slow suite that cries wolf is a slow suite nobody reads. The critical value is therefore
  * the **0.01 %** point of chi-squared with three degrees of freedom, 21.108, which puts the
  * announced failure rate near one run in a hundred and eighty.
  *
  * What that buys in power is less than phase 9's single-combination suite, and the division of
  * labour is deliberate: this suite exists to catch a combination that is **broken**, and
  * `DelayCountCalibrationSlowSuite` exists to check one combination deeply.
  */
object CombinationCalibrationSlowSuite extends SimpleIOSuite with Approx:

  private val stress: Boolean = sys.props.get("bayes4s.stress").contains("true")
  private val root: Long = if stress then System.currentTimeMillis() else 20261107L
  private val replications = if stress then 64 else 32

  private val months = 8
  private val maxLag = 3
  private val groups = 2
  private val t = IArray.tabulate(months)(m => (m - (months - 1) / 2.0) / months)
  private val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)
  private val settings = RunSettings(chains = 2, warmup = 400, samples = 600)

  /** The 0.01 % point of chi-squared with 3 degrees of freedom. */
  private val critical = 21.108
  private val bins = 4

  private val blank =
    DelayCountData
      .of(
        t,
        IArray.tabulate(groups)(_ => IArray.tabulate(months)(_ => IArray.fill(maxLag + 1)(0))),
        visible
      )
      .toOption
      .get

  private val onePopulation = IArray.fill(groups)(0)

  private def calibrated[R[_], D[_]](
      name: String,
      rate: RateComponent[R],
      delay: DelayComponent[D]
  ) =
    test(s"$name is calibrated ($replications replications, seed $root)") {
      cats.effect.IO {
        val generative = DelayCountModel.generative(rate, delay, blank)
        Sbc.run(generative, settings, replications, thinnedDraws = 63).runA(Seed(root)).value match
          case Left(error) => failure(s"$name: ${error.message}")
          case Right(result) =>
            val statistics = result.uniformityChiSquared(bins)
            check(statistics.nonEmpty, s"$name: nothing was ranked")
              .and(
                forEach(statistics.toList.sortBy((parameter, _) => parameter)) {
                  (parameter, statistic) =>
                    check(
                      statistic <= critical,
                      f"$name, $parameter: the rank histogram gives chi-squared $statistic%.2f " +
                        f"over $bins bins, past the 0.01 %% point of $critical"
                    )
                }
              )
      }
    }

  // Four stories about the rate, two about the delay, all eight pairings.
  calibrated("independent groups, one delay", Ar1Trend(), OrderedLogitDrift())
  calibrated("independent groups, a delay per group", Ar1Trend(), OrderedLogitDriftByGroup())
  calibrated(
    "pooled groups, non-centred, one delay",
    Ar1TrendHierarchical(onePopulation),
    OrderedLogitDrift()
  )
  calibrated(
    "pooled groups, non-centred, a delay per group",
    Ar1TrendHierarchical(onePopulation),
    OrderedLogitDriftByGroup()
  )
  calibrated(
    "pooled groups, centred, one delay",
    Ar1TrendHierarchical(onePopulation, centred = true),
    OrderedLogitDrift()
  )
  calibrated(
    "two populations, one delay",
    Ar1TrendHierarchical(IArray(0, 1)),
    OrderedLogitDrift()
  )
  calibrated("a local level, one delay", LocalLevel(), OrderedLogitDrift())
  calibrated("a local level, a delay per group", LocalLevel(), OrderedLogitDriftByGroup())
