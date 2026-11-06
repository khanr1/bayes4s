package bayes4s

import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import cats.syntax.all.*
import io.circe.Json
import weaver.SimpleIOSuite

/** The count model against the same model written independently in NumPyro.
  *
  * This is the cross-check the phase asks for, and it is the only test in the library that
  * compares the **whole assembly** — an AR(1) wobble in non-centred form, a drifting
  * ordered-logistic delay, a Poisson thinned by it, and a mask that hides the recent cells —
  * against another tool's answer for the same data. `reference/build_nowcast_reference.py` writes
  * both the fictional data and the posterior: eight chains of four thousand draws, one divergent
  * transition in thirty-two thousand, worst R-hat 1.0005.
  *
  * The rule is posteriordb's, as everywhere else: every mean within
  * `4 sqrt(mcse_ours² + mcse_ref²)` of the reference's, and every standard deviation likewise.
  * Thirty-four parameters are compared, the twenty-four monthly rates included, so the test fails
  * if the rate is right on average and wrong in its shape.
  */
object DelayCountReferenceSlowSuite extends SimpleIOSuite with Approx:

  private val stress: Boolean = sys.props.get("bayes4s.stress").contains("true")
  private val root: Long = if stress then System.currentTimeMillis() else 20261106L
  private val settings =
    if stress then RunSettings(chains = 4, warmup = 2000, samples = 4000)
    else RunSettings(chains = 4, warmup = 1000, samples = 2000)

  private def show(x: Double): String = String.format(java.util.Locale.ROOT, "%.6f", x)

  private def read(data: Json): Either[String, DelayCountData] =
    val cursor = data.hcursor
    val fields = for
      months <- cursor.get[Int]("months")
      maxLag <- cursor.get[Int]("maxLag")
      current <- cursor.get[Int]("current")
      t <- cursor.get[List[Double]]("t")
      counts <- cursor.get[List[List[Int]]]("counts")
    yield (months, maxLag, current, t, counts)
    fields.left.map(failure => s"data.json: ${failure.getMessage}").flatMap {
      (months, maxLag, current, t, counts) =>
        DelayCountData
          .of(
            IArray.from(t),
            IArray(IArray.from(counts.map(row => IArray.from(row)))),
            ReportingTriangle.rightCensored(months, maxLag + 1, current)
          )
          .left
          .map(_.message)
    }

  private def comparisons: Either[String, List[(ReferenceRow, IArray[IArray[Double]])]] =
    for
      pair <- Fixtures.delayCount
      (dataJson, reference) = pair
      data <- read(dataJson)
      rows <- PosteriordbSummary.parse(reference.json.noSpaces).left.map(_.message).map(_.rows)
      model = DelayCountModel(Ar1Trend(), OrderedLogitDrift(), data)
      chains <- (0 until settings.chains).toList.traverse { c =>
        val program = for
          start <- cats.Monad[Rand]
            .replicateA(model.params.unconstrainedSize, Rng[Rand].uniform)
            .map(us => IArray.from(us.map(u => 2.0 * (2.0 * u - 1.0))))
          chain <- Mcmc.run[Rand](
            Nuts.kernel[Rand](Model.target(model), settings.nuts),
            Model.target(model),
            start,
            settings.warmup,
            settings.samples,
            settings.adaptation
          )
        yield chain
        program.runA(Seed(root).fold(c.toLong)).value.left.map(_.message)
      }
      named = chains.map(chain =>
        chain.draws.map(q => Model.values(model, q) ++ model.derived(Model.constrain(model, q)))
      )
    yield rows.map { row =>
      val wanted = row.cmdStanName
      val (block, index) = wanted.lastIndexOf('.') match
        case -1 => (wanted, 0)
        case at =>
          wanted.drop(at + 1).toIntOption match
            case Some(i) => (wanted.take(at), i - 1)
            case None => (wanted, 0)
      val perChain = named.map(draws =>
        IArray.from(draws.map(values => values.getOrElse(block, IArray(Double.NaN))(index)))
      )
      (row, IArray.from(perChain))
    }

  test(s"the delay-count model, against its NumPyro reference (seed $root)") {
    cats.effect.IO {
      comparisons match
        case Left(message) => failure(message)
        case Right(compared) =>
          val assertions = forEach(compared) { (row, byChain) =>
            val pooled = (0 until byChain.length).flatMap(c =>
              (0 until byChain(c).length).map(byChain(c)(_))
            )
            val mean = pooled.sum / pooled.length
            val sd = math.sqrt(pooled.map(x => (x - mean) * (x - mean)).sum / (pooled.length - 1))
            val ourMcseMean = Convergence.mcseMean(byChain)
            val ourMcseSd = Convergence.mcseSd(byChain)
            val meanBudget =
              4.0 * math.sqrt(ourMcseMean * ourMcseMean + row.mcseMean * row.mcseMean)
            val sdBudget = 4.0 * math.sqrt(ourMcseSd * ourMcseSd + row.mcseSd * row.mcseSd)
            val rhat = Convergence.rhatRank(byChain)
            val essBulk = Convergence.essBulk(byChain)
            check(
              rhat < 1.01 && essBulk > 100.0,
              s"${row.name}: our own fit has Rhat ${show(rhat)} and a bulk effective size of " +
                s"${show(essBulk)}, so the comparison would say nothing"
            ).and(
              check(
                math.abs(mean - row.mean) <= meanBudget,
                s"${row.name}: mean ${show(mean)} against ${show(row.mean)}, gap " +
                  s"${show(math.abs(mean - row.mean))} over a budget of ${show(meanBudget)}"
              )
            ).and(
              check(
                math.abs(sd - row.sd) <= sdBudget,
                s"${row.name}: sd ${show(sd)} against ${show(row.sd)}, gap " +
                  s"${show(math.abs(sd - row.sd))} over a budget of ${show(sdBudget)}"
              )
            )
          }
          check(compared.length == 34, s"${compared.length} parameters compared, expected 34")
            .and(assertions)
    }
  }
