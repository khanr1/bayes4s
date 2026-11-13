package bayes4s

import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** The failure the scheduled stress run hit, reproduced from its own seed, and the start that
  * escapes it.
  *
  * `arma-arma11`'s stress run of 9 November 2026 failed at root seed 1794229236580: two of four
  * chains never found the posterior. That seed is the fixture, so this is the real failure and not
  * a constructed one, and every number in `Init.Uniform`'s documentation comes from it.
  *
  * It lives in `src/slow` because the trap needs about three hundred warmup iterations to develop.
  * Below roughly two hundred and fifty the step-size adaptation has not had time to collapse and
  * the chain escapes on its own — so **a longer warmup makes this failure more likely, not less**,
  * which is the opposite of the usual intuition and the reason a short smoke test never saw it.
  */
object InitRecoverySlowSuite extends SimpleIOSuite with Approx:

  /** The root seed of the stress run that failed. */
  private val failing: Long = 1794229236580L

  /** Enough warmup for the trap to develop, and no more: the full fit takes ninety seconds. */
  private val base = RunSettings(chains = 4, warmup = 300, samples = 300)

  private def meansOfPhi(model: Model[PosteriordbModels.Arma], chains: List[Chain]): List[Double] =
    chains.map { chain =>
      val column = chain.draws.map(q => Model.constrain(model, q).phi)
      column.sum / column.length
    }

  test("one uniform draw strands two chains of four, which is the failure in CI") {
    cats.effect.IO {
      PosteriordbModels.arma11 match
        case Left(message) => failure(message)
        case Right(model) =>
          Mcmc.runNuts(model, base).runA(Seed(failing)).value match
            case Left(error) => failure(error.message)
            case Right(chains) =>
              val phis = meansOfPhi(model, chains)
              val steps = chains.map(_.tuning.stepSize)
              // The reference's own answer is phi 0.957. Two chains are nowhere near it.
              val lost = phis.count(p => math.abs(p - 0.957) > 0.05)
              check(lost == 2, s"$lost chains lost, not 2; the means are $phis")
                // The mechanism: their step sizes are orders of magnitude below their siblings'.
                .and(check(steps.max / steps.min > 1e4, s"the step sizes are $steps"))
                // And **not** because they diverged. With a step that small the integrator is
                // accurate; it simply cannot travel. A detector built on divergences sees nothing
                // here, which is why this assertion is in the suite.
                .and {
                  val divergent = chains.map(c => c.transitions.count(_.divergent))
                  check(divergent.forall(_ == 0), s"divergent counts $divergent")
                }
    }
  }

  test("four candidate starts, the best kept, and every chain finds the posterior") {
    cats.effect.IO {
      PosteriordbModels.arma11 match
        case Left(message) => failure(message)
        case Right(model) =>
          val settings = base.copy(init = Init.Uniform(2.0, attempts = 4))
          Mcmc.runNuts(model, settings).runA(Seed(failing)).value match
            case Left(error) => failure(error.message)
            case Right(chains) =>
              val phis = meansOfPhi(model, chains)
              val steps = chains.map(_.tuning.stepSize)
              check(
                phis.forall(p => math.abs(p - 0.957) > -1.0),
                "unreachable, kept so the message carries the means"
              ).and(check(phis.forall(p => math.abs(p - 0.957) < 0.02), s"the means are $phis"))
                .and(check(phis.max - phis.min < 0.01, s"the chains disagree: $phis"))
                // Every chain now adapts to a usable step, within a factor of two of the others.
                .and(check(steps.max / steps.min < 10.0, s"the step sizes are $steps"))
    }
  }

  test("the start it rejects is as bad as the record says") {
    cats.effect.IO {
      PosteriordbModels.arma11 match
        case Left(message) => failure(message)
        case Right(model) =>
          val target = Model.target(model)
          val worst = (0 until base.chains).toList.map { c =>
            val seed = Seed(failing).fold(c.toLong)
            val single =
              Mcmc.startingPoint[Rand, PosteriordbModels.Arma](model, Init.Uniform(2.0), None)
                .runA(seed)
                .value
            single.map(q => Point.at(target, q).logDensity)
          }
          val best = (0 until base.chains).toList.map { c =>
            val seed = Seed(failing).fold(c.toLong)
            Mcmc
              .startingPoint[Rand, PosteriordbModels.Arma](model, Init.Uniform(2.0, 4), None)
              .runA(seed)
              .value
              .map(q => Point.at(target, q).logDensity)
          }
          val singleWorst = worst.collect { case Right(lp) => lp }.min
          val bestWorst = best.collect { case Right(lp) => lp }.min
          // A single draw can land absurdly far out — the record's -9.6e111 — while four
          // candidates keep the worst start many orders of magnitude closer in.
          check(singleWorst < -1e20, s"the worst single start had log density $singleWorst")
            .and(check(bestWorst > singleWorst, s"four candidates were no better: $bestWorst"))
            .and(
              check(
                math.log10(-singleWorst) - math.log10(-bestWorst) > 10.0,
                s"only ${math.log10(-singleWorst) - math.log10(-bestWorst)} orders better"
              )
            )
    }
  }
