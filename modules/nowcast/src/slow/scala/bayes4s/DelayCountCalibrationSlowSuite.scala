package bayes4s

import bayes4s.testkit.Approx
import bayes4s.testkit.MonteCarlo
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** Does the count model recover what generated the data, and are its intervals honest?
  *
  * Two checks the phase asks for, on the same reduced model — twelve months, a maximum delay of
  * six, right-censored so the recent months are incomplete, which is the whole point.
  *
  * **Coverage.** Draw the parameters from the prior, simulate censored data, fit, and ask whether
  * the 90 % interval contains the value that generated it. Over many replications that has to
  * happen about 90 % of the time. It is a weaker check than calibration — it looks at one interval
  * rather than the whole rank — but it fails for a different reason, and it is the one a modeller
  * actually relies on.
  *
  * **Calibration.** The same loop read as ranks: simulation-based calibration, which catches a
  * prior written twice and differently, a Jacobian that is wrong, or a sampler that cannot explore
  * the model — none of which coverage alone would notice.
  */
object DelayCountCalibrationSlowSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val stress: Boolean = sys.props.get("bayes4s.stress").contains("true")
  private val root: Long = if stress then System.currentTimeMillis() else 20261106L

  private val months = 12
  private val maxLag = 6
  private val t = IArray.tabulate(months)(m => (m - (months - 1) / 2.0) / months)
  private val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)
  private val blank = DelayCountData.blank(months, maxLag, t, visible).toOption.get
  private val generative = DelayCountModel.generative(Ar1Trend(), OrderedLogitDrift(), blank)

  private val replications = if stress then 80 else 40
  private val settings = RunSettings(chains = 2, warmup = 500, samples = 1000)

  /** One replication: draw, simulate, fit, and say whether each interval covered its truth. */
  private def covered(seed: Seed): Either[BayesError, Map[String, Boolean]] =
    val program = for
      truth <- generative.generative.samplePrior[Rand]
      simulated <- generative.generative.simulateData[Rand](truth)
    yield (truth, simulated)
    val (truth, simulated) = program.runA(seed).value
    val model = generative.conditionedOn(simulated)
    Mcmc.runNuts(model, settings).runA(seed.fold(7L)).value.map { chains =>
      val wanted = generative.generative.named(truth)
      val samples = chains.flatMap(_.draws.map(q => Model.values(model, q)))
      wanted.toList.flatMap { (block, values) =>
        (0 until values.length).map { index =>
          val name = if values.length == 1 then block else s"$block.${index + 1}"
          val column = IArray.from(samples.map(_.getOrElse(block, IArray(Double.NaN))(index)))
          val low = Quantile.quantile(column, 0.05)
          val high = Quantile.quantile(column, 0.95)
          name -> (values(index) >= low && values(index) <= high)
        }
      }.toMap
    }

  test(s"the ninety per cent intervals cover about ninety per cent of the time (seed $root)") {
    cats.effect.IO {
      val outcomes = (0 until replications).toList.map(i => covered(Seed(root).fold(i.toLong)))
      val failures = outcomes.collect { case Left(error) => error.message }
      val successes = outcomes.collect { case Right(flags) => flags }
      if failures.nonEmpty then
        failure(s"${failures.length} of $replications replications did not fit: ${failures.head}")
      else
        val names = successes.head.keySet.toList.sorted
        val total = successes.length * names.length
        val coveredCount = successes.map(flags => names.count(flags)).sum
        val rate = coveredCount.toDouble / total
        // The standard error of a coverage estimate over `total` intervals, at the nominal 0.9.
        // The intervals within a replication are not independent, so this understates it; four of
        // these is the threshold, and the suite says so rather than pretending the intervals are
        // independent.
        val standardError = math.sqrt(0.9 * 0.1 / total)
        val perName = names.map { name =>
          val count = successes.count(flags => flags(name))
          val share = count.toDouble / successes.length
          (name, share)
        }
        check(names.length == 6, s"the parameters are $names")
          .and(
            withinStandardErrors(
              rate,
              0.9,
              standardError,
              4.0,
              s"the coverage over $total intervals"
            )
          )
          // And no single parameter is wildly off, which a pooled rate could hide: with 40
          // replications one parameter's own coverage has a standard error of 4.7 %, so the band
          // is wide, but a parameter covering half the time would still show.
          .and(
            forEach(perName) { (name, share) =>
              check(
                share > 0.70 && share <= 1.0,
                f"$name was covered ${share}%.3f of the time over ${successes.length} replications"
              )
            }
          )
    }
  }

  test(s"the reduced count model is calibrated ($replications replications, seed $root)") {
    // Simulation-based calibration, at the thresholds of phase 6: ranks thinned to 63, binned into
    // 16 of 4 possible values, against the 0.1 % point of chi-squared with 15 degrees of freedom.
    // The replication count is lower than phase 6's 256 because each one fits a 23-parameter
    // model; with this many the test has less power, which is why the coverage check above runs
    // beside it rather than instead of it.
    val bins = 4
    val critical = 16.266 // the 0.1 % point of chi-squared with 3 degrees of freedom
    cats.effect.IO {
      Sbc.run(generative, settings, replications, thinnedDraws = 63).runA(Seed(root)).value match
        case Left(error) => failure(error.message)
        case Right(result) =>
          val statistics = result.uniformityChiSquared(bins)
          check(
            statistics.keySet == Set("a", "b", "phi", "tau", "cut0", "beta"),
            s"the ranked parameters are ${statistics.keySet.toList.sorted}"
          ).and(
            forEach(statistics.toList.sortBy((name, _) => name)) { (name, statistic) =>
              check(
                statistic <= critical,
                f"$name: the rank histogram gives chi-squared $statistic%.2f over $bins bins, " +
                  f"past the 0.1 %% point of $critical"
              )
            }
          )
    }
  }
