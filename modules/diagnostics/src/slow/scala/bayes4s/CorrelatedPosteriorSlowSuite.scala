package bayes4s

import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import cats.syntax.all.*
import io.circe.Json
import weaver.SimpleIOSuite

/** The one reference posterior with correlated varying effects, fitted and compared.
  *
  * posteriordb has none, so this one was simulated and fitted elsewhere: twelve groups, 138
  * observations, a Cholesky factor of correlation under an LKJ(2) prior, and a reference of eight
  * NumPyro chains of four thousand draws with no divergent transition. The model this library
  * fits is the same `model.stan` that produced it, read by the tape instead of by Stan's compiler.
  *
  * The rule is posteriordb's, as in [[PosteriordbSlowSuite]]: every mean within
  * `4 sqrt(mcse_ours² + mcse_ref²)` of the reference's, and every standard deviation likewise.
  *
  * This posterior is harder than any of the nine. `tau[2]`, the spread of the slopes, has a
  * posterior mean of 0.148 against a standard deviation of 0.105, so it is pressed against zero;
  * and when a scale is near zero the correlation it multiplies is barely identified, which is why
  * `rho` has a standard deviation of 0.41 on a parameter that lives in (-1, 1). A sampler that
  * handled the funnel badly would not reproduce either number, and both are asserted.
  */
object CorrelatedPosteriorSlowSuite extends SimpleIOSuite with Approx:

  private val stress: Boolean = sys.props.get("bayes4s.stress").contains("true")
  private val root: Long = if stress then System.currentTimeMillis() else 20261105L
  private val settings =
    if stress then RunSettings(chains = 4, warmup = 2000, samples = 4000)
    else RunSettings(chains = 4, warmup = 1000, samples = 2000)

  private def show(x: Double): String = String.format(java.util.Locale.ROOT, "%.6f", x)

  /** The data, as the reference's own `data.json` holds it. The groups are one-based there, as
    * Stan's `array[N] int<lower=1, upper=J>` demands, and zero-based here.
    */
  private def read(data: Json): Either[String, MultilevelModels.Grouped] =
    val cursor = data.hcursor
    val fields = for
      groups <- cursor.get[Int]("J")
      group <- cursor.get[List[Int]]("group")
      x <- cursor.get[List[Double]]("x")
      y <- cursor.get[List[Double]]("y")
    yield MultilevelModels.Grouped(
      IArray.from(group.map(_ - 1)),
      IArray.from(x),
      IArray.from(y),
      groups
    )
    fields.left.map(failure => s"data.json: ${failure.getMessage}")

  private def summary(json: Json): Either[String, ReferenceSummary] =
    PosteriordbSummary.parse(json.noSpaces).left.map(_.message)

  /** Fitted, pooled over chains, and lined up with the reference's rows. */
  private def comparisons: Either[String, List[(ReferenceRow, IArray[IArray[Double]])]] =
    for
      pair <- Fixtures.correlatedEffects
      (dataJson, reference) = pair
      data <- read(dataJson)
      rows <- summary(reference.json).map(_.rows)
      model = MultilevelModels.correlatedEffects(data)
      target = Model.target(model)
      chains <- (0 until settings.chains).toList.traverse { c =>
        val program = for
          start <- cats.Monad[Rand]
            .replicateA(model.params.unconstrainedSize, Rng[Rand].uniform)
            .map(us => IArray.from(us.map(u => 2.0 * (2.0 * u - 1.0))))
          chain <- Mcmc.run[Rand](
            Nuts.kernel[Rand](target, settings.nuts),
            target,
            start,
            settings.warmup,
            settings.samples,
            settings.adaptation
          )
        yield chain
        program.runA(Seed(root).fold(c.toLong)).value.left.map(_.message)
      }
      // The derived quantities too: `rho` is the reference's `generated quantities` block, and
      // `Model.values` reads the parameter blocks only.
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

  test(s"correlated varying effects, against its reference summary (seed $root)") {
    cats.effect.IO {
      comparisons match
        case Left(message) => failure(message)
        case Right(compared) =>
          val assertions = forEach(compared) { (row, byChain) =>
            val pooled = (0 until byChain.length).flatMap(c =>
              (0 until byChain(c).length).map(byChain(c)(_))
            )
            val mean = pooled.sum / pooled.length
            val sd = math.sqrt(
              pooled.map(x => (x - mean) * (x - mean)).sum / (pooled.length - 1)
            )
            val ourMcseMean = Convergence.mcseMean(byChain)
            val ourMcseSd = Convergence.mcseSd(byChain)
            val meanGap = math.abs(mean - row.mean)
            val meanBudget =
              4.0 * math.sqrt(ourMcseMean * ourMcseMean + row.mcseMean * row.mcseMean)
            val sdGap = math.abs(sd - row.sd)
            val sdBudget = 4.0 * math.sqrt(ourMcseSd * ourMcseSd + row.mcseSd * row.mcseSd)
            val rhat = Convergence.rhatRank(byChain)
            val essBulk = Convergence.essBulk(byChain)
            check(
              rhat < 1.01 && essBulk > 100.0,
              s"${row.name}: our own fit has Rhat ${show(rhat)} and a bulk effective size of " +
                s"${show(essBulk)}, so the comparison would say nothing"
            ).and(
              check(
                meanGap <= meanBudget,
                s"${row.name}: mean ${show(mean)} against ${show(row.mean)}, gap ${show(meanGap)}" +
                  s" over a budget of ${show(meanBudget)} (our MCSE ${show(ourMcseMean)}, " +
                  s"theirs ${show(row.mcseMean)})"
              )
            ).and(
              check(
                sdGap <= sdBudget,
                s"${row.name}: sd ${show(sd)} against ${show(row.sd)}, gap ${show(sdGap)} over " +
                  s"a budget of ${show(sdBudget)}"
              )
            )
          }
          check(
            compared.map((row, _) => row.name).toSet ==
              Set("mu[1]", "mu[2]", "tau[1]", "tau[2]", "rho", "sigma"),
            s"the reference's parameters are ${compared.map((row, _) => row.name)}"
          ).and(assertions)
    }
  }
