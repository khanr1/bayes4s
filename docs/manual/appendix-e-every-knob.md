# Appendix E. Every knob, with its default

A reference rather than a chapter: every constructor, constraint, setting and figure you can reach,
each with one line saying what it is for. Nothing here is explained from first principles — the
chapters do that — and nothing here is left out, which is the difference between this page and
them.

Appendix D is deliberately missing: it is reserved for the optional `rethinking` module, which is
not built yet.

CI checks this page against the code. A constraint, a parameter block, a setting field or a figure
that exists and is not named below fails `scripts/check-manual.py`, so "every" stays true.

## Declaring parameters

`ParamSpec[T]` says what a model's parameters are called and what shape they have. Several are
combined with `mapN`, which is cats' syntax for an applicative — the order of the blocks is the
order of the arguments.

| Constructor | Shape | What it declares |
|---|---|---|
| `ParamSpec.real(name)` | `A` | one unconstrained scalar |
| `ParamSpec.positive(name)` | `A` | one scalar above zero |
| `ParamSpec.unitInterval(name)` | `A` | one scalar in `(0, 1)`: a probability |
| `ParamSpec.bounded(name, lower, upper)` | `A` | one scalar in `(lower, upper)` |
| `ParamSpec.scalar(name, constraint)` | `A` | one scalar under any constraint below |
| `ParamSpec.vector(name, n)` | `IArray[A]` | `n` scalars; takes an optional `constraint` |
| `ParamSpec.matrix(name, rows, cols)` | `IArray[IArray[A]]` | a dense matrix, row by row |
| `ParamSpec.choleskyCorr(name, k)` | `IArray[IArray[A]]` | the Cholesky factor of a `k × k` correlation matrix |
| `ParamSpec.pure(value)` | anything | a constant carried through `mapN` without becoming a parameter |

A name must be unique within a model: a duplicate is `Left(DuplicateParameter)` from
`params.validated` rather than a silent collision. `ParamSpec.vector("z", 3)` names its values
`z.1`, `z.2`, `z.3` in summaries and CSV files, counting from one as CmdStan does — while the
`IArray` you read in the model is indexed from zero, as Scala does.

## Constraints

A constraint is a transform plus its log Jacobian. You declare the constrained space and the
sampler walks the unconstrained one; the Jacobian is added once, in `Model.target`, and never by
you. [Chapter 2](02-writing-a-model.md) is the chapter.

| Constraint | Constrained space | Stan's spelling |
|---|---|---|
| `Constraint.Unconstrained` | the whole real line; the identity, no Jacobian | *(none)* |
| `Constraint.Positive` | `(0, ∞)`, by the exponential | `<lower=0>` |
| `Constraint.UnitInterval` | `(0, 1)`, by the logistic | `<lower=0, upper=1>` |
| `Constraint.LowerBound(l)` | `(l, ∞)` | `<lower=l>` |
| `Constraint.UpperBound(u)` | `(-∞, u)` | `<upper=u>` |
| `Constraint.Interval(l, u)` | `(l, u)` | `<lower=l, upper=u>` |
| `Constraint.Ordered` | increasing values: the first free, the gaps exponential | `ordered` |
| `Constraint.Simplex` | positive values summing to one | `simplex` |
| `Constraint.SumToZero` | values summing to zero | `sum_to_zero_vector` |
| `Constraint.OffsetMultiplier(o, m)` | the affine `o + m · z`, a constant Jacobian | `<offset=o, multiplier=m>` |
| `Constraint.CholeskyFactorCorr(k)` | lower triangular, positive diagonal, unit rows | `cholesky_factor_corr` |

Two of these are worth a sentence beyond the table. `OffsetMultiplier` is the non-centred
parameterisation expressed as a constraint rather than as a rewrite of the model, which is why
switching a component between centred and non-centred is a flag rather than a second model.
`SumToZero` is what an effect coded as a deviation needs: without it a level and a set of group
effects are not separately identified, and the sampler walks a ridge forever.

## Densities, by what they are over

Written once against `Real[A]`, so the same call is a value in `Double`, a gradient on the tape and
an exact check in `Dual`. Each takes its parameters in Stan's order and returns a log density;
outside the support it is `-∞`, and for an invalid parameter it is `NaN` — never an exception.
Appendix A maps every one of these to its Stan name.

**Over the whole line.** `normal`, `studentT`, `cauchy`, `logistic`.

**Over the positive half.** `halfNormal`, `halfStudentT`, `halfCauchy`, `exponential`, `gamma`,
`logNormal`.

**Over `(0, 1)`.** `beta`.

**Over a vector or a matrix.** `multiNormalCholesky` (a Gaussian given the Cholesky factor of its
covariance), `lkjCorrCholesky` (the LKJ prior on a correlation's Cholesky factor), `dirichlet`.

**Over the integers** — these live in `LogMass`, not `LogDensity`. `poisson` and `poissonLog`
(the same law, parameterised by the rate or by its logarithm), `negativeBinomial` and
`negativeBinomial2` (shape-and-rate, or mean-and-dispersion: the gamma-Poisson under two
parameterisations), `binomial` and `binomialLogit`, `bernoulliLogit`, `betaBinomial`,
`orderedLogistic`.

The `…Logit` forms take a log-odds and **never form a probability**: at a log-odds of -25,
`log(invLogit(eta))` has lost five digits and `bernoulliLogit` has lost none. Prefer them whenever
the thing you have is a linear predictor.

Several come in a **summed** form on `Real[A]` — `normalSum`, `poissonSum`, `poissonLogSum`,
`bernoulliLogitSum`, `binomialLogitSum` — which put one node on the gradient tape instead of one
per observation. [Chapter 10](10-performance.md) measures what that is worth: three orders of
magnitude on a ten-thousand-observation model.

## Running the sampler

`RunSettings` is what `Mcmc.runNuts` and `Sampler.run` take. The defaults are Stan's.

| Field | Default | What it does |
|---|---|---|
| `chains` | 4 | how many chains. Chain `c` runs at `seed.fold(c)`, so parallel and sequential agree exactly |
| `warmup` | 1000 | iterations spent adapting, then discarded |
| `samples` | 1000 | iterations kept, per chain |
| `nuts` | `NutsSettings()` | the trajectory limits, below |
| `adaptation` | `AdaptationSettings()` | the warmup schedule, below |
| `init` | `Init.Uniform(2.0)` | where each chain starts |

`NutsSettings`:

| Field | Default | What it does |
|---|---|---|
| `maxTreeDepth` | 10 | the trajectory doubles at most this many times, so at most 1023 leapfrog steps. Saturating it is a cost problem, not a correctness one |
| `maxEnergyError` | 1000.0 | an energy change beyond this is a divergent transition, and the trajectory stops |

`AdaptationSettings` — the first four are dual averaging on the step size, the next three are the
window schedule, and the last is the shape of the mass matrix:

| Field | Default | What it does |
|---|---|---|
| `targetAccept` | 0.8 | the acceptance the step size is driven to. A random walk wants about 0.234; a Hamiltonian method wants this |
| `gamma` | 0.05 | how hard dual averaging pulls towards the target |
| `t0` | 10.0 | damps the first iterations, which are the least informative |
| `kappa` | 0.75 | how fast the learning rate decays |
| `initBuffer` | 75 | iterations before the first metric window |
| `baseWindow` | 25 | the first metric window's length; each is twice the last |
| `termBuffer` | 50 | iterations after the last window, for a final step size |
| `metric` | `MetricShape.Diagonal` | `Diagonal` learns one scale per coordinate; `Dense` learns the whole covariance |

`Dense` is worth its cost when the posterior is strongly correlated and small: on a ten-dimensional
Gaussian correlated at 0.95 it buys several times the effective number of samples per gradient, and
on a high-dimensional model it spends more on the metric than it saves.

`Init`:

| Case | What it does |
|---|---|
| `Init.Uniform(radius)` | uniform on `(-radius, radius)` in the **unconstrained** space, which is Stan's default |
| `Init.Given(values)` | named constrained values, as `Model.values` reports them. A value outside its constraint is an error, not a clamp |
| `Init.PriorMedian(draws)` | samples the prior and starts at the median, which needs the model's generative part |

## Reading a fit

| What | Where it comes from |
|---|---|
| the table | `Summary.table(model, chains)`, or `Summary.of` for the rows as values |
| the sampler's own report | `SamplerReport.of(chains, maxTreeDepth)`: divergent transitions, saturated depth, E-BFMI per chain |
| one draw's parameters | `Model.constrain(model, point)` — the object you declared |
| one draw by name | `Model.values(model, point)` — the map a CSV row is made of |
| intervals | `Intervals.equalTailed`, `Intervals.hpd`, `Intervals.probabilityAbove` |
| convergence, one statistic at a time | `Convergence.rhatRank`, `essBulk`, `essTail`, `essBasic`, `mcseMean`, `mcseSd` |
| cross-validation | `Loo.psis`, then `Loo.compare`; `LooResult.problematic` names the untrustworthy observations |
| prior sensitivity | `PowerScaling.analyse(model, chains)`, or `sensitivity` for one parameter |
| calibration | `Sbc.run(generativeModel, settings, replications, thinnedDraws)` |

## Count models, component by component

The `nowcast` half of the library is a **rate** component, a **delay** component and the data. Both
are interfaces, so a different story about the rate is a different component rather than a rewritten
model. [Chapter 7](07-nowcasting-step-by-step.md) builds one end to end.

A rate component answers "how many events happened in group `g`, month `m`" on the log scale, and
can project itself past the data, which is what makes a forecast possible.

| Rate component | The story it tells |
|---|---|
| `Ar1Trend(…)` | one group: a level, a linear trend, and a wobble that reverts to it. The wobble is an AR(1) — each month is `phi` times the last plus fresh noise — so a surprise fades at a rate the fit estimates |
| `Ar1TrendHierarchical(population)` | one of the above per group, with each group's level and trend drawn around a common one. The argument says which **population** each group belongs to: `IArray(0, 0, 0, 0)` is four groups sharing one, `IArray(0, 0, 1, 1)` is two pairs sharing one each |
| `LocalLevel(…)` | a level that wanders rather than reverting: no trend to extrapolate and no mean to come back to. Fitted to a trending series it will not find the trend, because it has no parameter for one |

Each takes its priors as constructor arguments — `levelPrior`, `trendPrior`, `stepPrior` and so on,
all `(location, scale)` pairs — and `Ar1TrendHierarchical` takes `centred` as a flag, which is the
reparameterisation of the ideas page rather than a second component.

A delay component answers "of the events of month `m`, what share were recorded `k` months later".

| Delay component | The story it tells |
|---|---|
| `OrderedLogitDrift(…)` | the delay is an ordered categorical: one cut point per delay, and a drift that lets the whole distribution slide earlier or later over time. Ordered, because "recorded within two months" implies "within three" |
| `OrderedLogitDriftByGroup(…)` | the same, with one offset per group: a group that reports faster or slower overall. It cannot express a group that is quick at first and slow in the tail |

What a forecast needs beyond those two:

| Piece | What it is for |
|---|---|
| `SizeModel.unit` | every event counts as one: a forecast of counts rather than of amounts |
| `SizeModel.logNormal(params, cap)` | each event has a size drawn from a log-normal with a trend, optionally capped — a log-normal has no largest value, and one absurd event can dominate a total |
| `TemplatePool.of(values, weights)` | the pool of event kinds a future samples from |
| `BayesianBootstrap.weights` | one opinion per future about the pool's composition: Dirichlet(1, …, 1) weights, drawn **once** per future and held for the whole horizon |
| `StratifiedPools.of(…)` | a pool per group, mixed with the whole pool at `n / (n + k0)`, so a thin group borrows from everybody |
| `ContributionRule` | where what an event produces belongs: an amount, under a key, at an offset from the month the event closes in. The offset is how a delay is expressed |
| `TrendScaling.scaled(…)` | a multiplicative trend applied to an amount |
| `ModelError.draw` / `applied` / `calibrate` | the error the model does not know it has: one scale per future, and a grid search for the scale that scores best on months already past |
| `Forecast.run` / `Forecast.byGroup` | assembles all of the above into an `Ensemble`, one future per sample from the posterior |
| `ForecastSettings(perDraw, firstPeriodFactor)` | how many futures per sample, and how much of the first month has already happened |
| `Checks.priorPredictive` / `posteriorPredictive` | the two predictive checks of the ideas page, over the reports that **arrived** in each month |

The two shrinkage models are the same gamma-Poisson written twice:
`GammaPoissonShrinkage.fit` estimates the dispersion by maximum likelihood and treats it as known,
and `GammaPoissonHierarchical` samples it. [Chapter 11](11-multilevel-models.md) measures when the
difference matters.

## Figures

`Catalogue` is fifteen figures, each a pure `Plot` compiled by `VegaLite.spec`.
[Chapter 12](12-plots.md) is the chapter; this is the list with its arguments.

| Figure | Takes |
|---|---|
| `tracePlot(chains, parameter, divergent)` | one row per chain, optionally a divergence flag per draw |
| `trankPlot(chains, parameter, bins)` | the same, ranked |
| `autocorrelationPlot(chains, parameter, lags)` | the same |
| `energyPlot(energies, eBfmi)` | one chain's energies and its E-BFMI |
| `pairsPlot(first, second, names, divergent)` | two parameters' pooled samples |
| `densityPlot(chains, parameter, rule, mass, points, reflectAt)` | a kernel density per chain; `reflectAt` keeps mass off a boundary |
| `intervalPlot(rows, title)` | `(name, point, lower, upper)` per parameter |
| `compareModelsPlot(comparisons)` | the output of `Loo.compare` |
| `paretoKPlot(result, threshold)` | a `LooResult` |
| `predictivePlot(observed, replicated, mass, title)` | the observed series and the replicated ones |
| `pitPlot(pits, mass, points, seed)` | probability integral transforms, with a simultaneous band |
| `sbcPlot(ranks, possible, bins, mass, seed)` | calibration ranks; `possible` is the largest rank |
| `sensitivityPlot(sensitivities, threshold)` | the output of `PowerScaling.analyse` |
| `fanChart(ensemble, masses, observed, title)` | an `Ensemble[Unit]`, and optionally what happened |
| `shrinkagePlot(labels, unpooled, pooled, population)` | each group twice, and the population |

`VegaLite.concat(plots, columns)` makes a grid, `Plot.facet` splits one figure into panels, and
`VegaLite.raw(plot)(edit)` hands over the JSON when the catalogue is nearly right. Writing happens
in `PlotFile`, in the `effect` module, and nowhere else.

## Scoring a forecast

[Chapter 8](08-scoring-a-forecast.md) is the chapter. Every one of these is a loss — lower is
better — except `coverage`, which is a share.

| What | Where it comes from |
|---|---|
| the score of a predictive distribution | `Crps.ensemble(forecast, observed)`, which sorts, or `Crps.energy`, which is the definition; `Crps.gaussian(mu, sigma, observed)` is the closed form |
| where the observation fell inside the forecast | `Calibration.pit(forecast, observed)`; `Calibration.pitHistogram` and `pitUniformity` summarise many |
| whether the forecast sat above or below | `Calibration.bias` — positive means above |
| the price of an interval's width plus its misses | `Calibration.intervalScore(lower, upper, observed, alpha)` |
| all of the above over a run of dates | `Backtest.run(dates, alpha)`, giving `meanCrps`, `coverage`, `coverageGap`, `meanBias`, `pitUniformity` |
| the standard error that is not `sd/sqrt(n)` | `BacktestResult.blockBootstrap(statistic, blockSize, replications)` |

## In `F`: the effectful edge

The only module that touches the clock, the file system or a thread. Everything above is pure, and
these interpret it. [Chapter 3](03-sampling.md) and [chapter 9](09-interoperability.md) are the
chapters.

| What | Where it comes from |
|---|---|
| chains on separate fibres, cancellable | `Sampler[F].run(model, settings, seed)` — equal to `Mcmc.runNuts` on the same seed, value for value |
| one chain as a stream | `Sampler[F].chain(model, settings, chainId, seed)`, so a long fit can be watched or stopped between iterations |
| CmdStan's CSV on disk | `PosteriorCsv[F].writeAll` / `readAll`, one file per chain as CmdStan writes them |
| CmdStan's CSV as text | `StanCsv.parse` / `parseAll` / `render` / `renderAll`, pure functions of lines |
| a backtest that refits per origin | `BacktestRunner[F].run(...)`, as a stream: two rows cost two refits |
| figures on disk | `PlotFile[F].writeHtml` / `writeReport` / `writeJson` |
| randomness over a `Ref` | `RefRng[F]`, running the same pure programs the `State` interpreter does |

A `Left` from the core becomes an error of `F` — raised through `MonadThrow`, so nothing is
swallowed. [Appendix B](appendix-b-errors.md) is the contract.

## Where the randomness comes from

| Thing | What it is |
|---|---|
| `Seed` | an immutable value. `seed.fold(i)` derives an independent stream by index |
| `Rand[A]` | `State[Seed, A]`: a pure program. `program.runA(seed).value` is how a value comes out |
| `Rng[F]` | the capability a program asks for; `State` interprets it in the core, a `Ref` in `effect` |
| `Sampling` | `gamma`, `beta`, `poisson`, `dirichlet`, `categorical`, `alias` — the generators, as `Rand` programs |
| `Batch` | the same generators filling an array, for when a million values are wanted at once |

Nothing in this library reads a global generator, so two runs of the same program with the same
seed agree exactly, on any machine and in any order. The mistake that replaces is handing the same
seed to two things that should be independent — [chapter 10](10-performance.md) shows it.
