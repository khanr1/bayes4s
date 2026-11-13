package bayes4s

import bayes4s.testkit.Approx
import bayes4s.testkit.Reference
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** The nine reference posteriors of posteriordb, fitted and compared.
  *
  * This is the end-to-end test of the whole library: a model written once against `Real[A]`, read
  * by the tape, sampled by the no-U-turn sampler, summarised by the diagnostics, and compared
  * against what Stan itself produced from the same data and the same model — ten chains of a
  * thousand thinned draws of a long run.
  *
  * The rule is the collection's own, stated in its `index.json`: for each parameter, the means
  * must agree within `4 sqrt(mcse_ours² + mcse_ref²)` and the standard deviations likewise. Both
  * sides are Monte Carlo estimates, so the comparison is of two noisy numbers and the threshold
  * is built from both their errors; a fixed tolerance would be meaningless.
  *
  * It lives in `src/slow/scala` because it is nine fits of four chains. `sbt slow:test` runs it,
  * and `-Dbayes4s.stress=true` gives it fresh seeds and twice the draws.
  */
object PosteriordbSlowSuite extends SimpleIOSuite with Approx:

  private val stress: Boolean = sys.props.get("bayes4s.stress").contains("true")

  /** Candidate starting points per chain, the best by log density kept. */
  private val attempts: Int = 4
  /** The root seed: fixed by default, the clock under stress, and overridable either way.
    *
    * `-Dbayes4s.seed=1794229236580` reproduces a stress failure exactly. Without it, reproducing
    * one means reading the seed out of a test's name and hand-editing a suite, which is what it
    * took to diagnose the failure of 9 November.
    */
  private val root: Long =
    sys.props
      .get("bayes4s.seed")
      .flatMap(_.toLongOption)
      .getOrElse(if stress then System.currentTimeMillis() else 20261105L)
  private val settings =
    // Four candidate starting points per chain, the best by log density kept. A single uniform
    // draw is what Stan does and what `runNuts` does by default; it is also what stranded two of
    // four chains of `arma-arma11` in the stress run of 9 November, at a start whose log density
    // was -9.6e111 (docs/validation.md, and `InitRecoverySlowSuite` reproduces it).
    //
    // Nothing else here is relaxed. The tolerances are the same, the seeds are the same, and the
    // guard that refuses to compare an unconverged fit is the same — so if initialisation ever
    // fails again, this suite still says so instead of comparing nonsense.
    val init = Init.Uniform(2.0, attempts)
    if stress then RunSettings(chains = 4, warmup = 2000, samples = 2000, init = init)
    else RunSettings(chains = 4, warmup = 1000, samples = 1000, init = init)

  /** One parameter's comparison, with everything the message needs. */
  private final case class Comparison(
      name: String,
      ourMean: Double,
      ourSd: Double,
      ourMcseMean: Double,
      ourMcseSd: Double,
      ourRhat: Double,
      ourEssBulk: Double,
      reference: ReferenceRow
  ):
    def meanGap: Double = math.abs(ourMean - reference.mean)
    def meanBudget: Double =
      4.0 * math.sqrt(ourMcseMean * ourMcseMean + reference.mcseMean * reference.mcseMean)
    def sdGap: Double = math.abs(ourSd - reference.sd)
    def sdBudget: Double =
      4.0 * math.sqrt(ourMcseSd * ourMcseSd + reference.mcseSd * reference.mcseSd)

  /** A number for a failure message, in the root locale: a log read on another machine should say
    * the same thing (docs/design.md, D-82).
    */
  private def show(x: Double): String = String.format(java.util.Locale.ROOT, "%.6f", x)

  private def summary(posterior: String): Either[String, ReferenceSummary] =
    Reference
      .text(s"posteriordb/$posterior/reference_summary.json")
      .left
      .map(_.message)
      .flatMap(PosteriordbSummary.parse(_).left.map(_.message))

  /** Fit a port and line its parameters up with the reference's. */
  private def compare(
      posterior: String,
      model: PosteriordbModels.Ported,
      seed: Long
  ): Either[String, List[Comparison]] =
    for
      reference <- summary(posterior)
      chains <- (0 until settings.chains).toList
        .traverse { c =>
          val program = for
            start <- Mcmc
              .uniformStart[Rand](model.target, model.dimension, 2.0, attempts)
              .flatMap {
                case Right(q) => cats.data.State.pure[Seed, IArray[Double]](q)
                // `attempts` is a literal above, so this cannot happen; failing loudly is still
                // better than a start nobody chose.
                case Left(error) => sys.error(error.message)
              }
            chain <- Mcmc.run[Rand](
              Nuts.kernel[Rand](model.target, settings.nuts),
              model.target,
              start,
              settings.warmup,
              settings.samples,
              settings.adaptation
            )
          yield chain
          program.runA(Seed(seed).fold(c.toLong)).value.left.map(_.message)
        }
      // Every draw, named and constrained, derived quantities included.
      named = chains.map(chain => chain.draws.map(model.valuesAt))
    yield reference.rows.flatMap { row =>
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
      val byChain = IArray.from(perChain)
      val pooled = perChain.flatMap(c => (0 until c.length).map(c.apply))
      val mean = pooled.sum / pooled.length
      val sd = math.sqrt(
        pooled.map(x => (x - mean) * (x - mean)).sum / (pooled.length - 1)
      )
      List(
        Comparison(
          wanted,
          mean,
          sd,
          Convergence.mcseMean(byChain),
          Convergence.mcseSd(byChain),
          Convergence.rhatRank(byChain),
          Convergence.essBulk(byChain),
          row
        )
      )
    }

  PosteriordbModels.all.foreach { (posterior, built) =>
    test(s"$posterior, against its reference summary (seed $root)") {
      cats.effect.IO {
        built.flatMap(model => compare(posterior, model, root)) match
          case Left(message) => failure(s"$posterior: $message")
          case Right(comparisons) =>
            val assertions = forEach(comparisons) { c =>
              check(
                c.meanGap <= c.meanBudget,
                s"$posterior ${c.name}: mean ${show(c.ourMean)} against ${show(c.reference.mean)}," +
                  s" gap ${show(c.meanGap)} over a budget of ${show(c.meanBudget)} " +
                  s"(our MCSE ${show(c.ourMcseMean)}, theirs ${show(c.reference.mcseMean)})"
              ).and(
                check(
                  c.sdGap <= c.sdBudget,
                  s"$posterior ${c.name}: sd ${show(c.ourSd)} against ${show(c.reference.sd)}, " +
                    s"gap ${show(c.sdGap)} over a budget of ${show(c.sdBudget)}"
                )
              )
            }
            // A fit that has not converged cannot be compared with anything, so its own
            // diagnostics are asserted first and the message says which parameter failed.
            val converged = forEach(comparisons) { c =>
              check(
                c.ourRhat < 1.01 && c.ourEssBulk > 100.0,
                s"$posterior ${c.name}: our own fit has Rhat ${show(c.ourRhat)} and a bulk " +
                  s"effective size of ${show(c.ourEssBulk)}, so the comparison would say nothing"
              )
            }
            check(comparisons.nonEmpty, s"$posterior: no parameter was compared")
              .and(converged)
              .and(assertions)
      }
    }
  }
