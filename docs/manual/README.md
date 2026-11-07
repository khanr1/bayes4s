# bayes4s manual

Write a model, fit it, find out whether to believe the fit, find out whether the model is any good,
predict something with it, score the prediction, and draw any of it. That is the whole manual, in
that order.

Every code block below is compiled by mdoc against the current build and every figure is drawn when
the manual is built, so an example that stops compiling fails CI and a figure can never be a stale
picture of an older library. The outputs you see are the outputs the code produced.

If the statistics feel assumed rather than explained — what a posterior is, what a sampler is doing,
why a count series can be wrong at its right-hand edge — **[read the ideas page
first](the-ideas.md)**. It has no code in it and takes ten minutes. If the *code* is what feels
assumed, chapter 1 opens with the seven words every example uses.

Two conventions worth knowing before you start. The prose uses the vocabulary of *Statistical
Rethinking* — samples from the posterior, compatibility interval, multilevel model — while the API
keeps the names of Stan, and the [glossary](glossary.md) is the bridge between them. And there is no
`get` on an `Either` anywhere in these pages: errors are values here, so an example that can fail
reads its result through `map`, which is also what you will write.

## The chapters

| Chapter | What it is for |
|---|---|
| [The ideas, in plain words](the-ideas.md) | no code: posteriors, samplers, priors, pooling, reporting delays, scoring |
| [0. Quick start](00-quick-start.md) | one page: a model, a fit, a summary table, a trace |
| [1. Concepts](01-concepts.md) | the pure core and the effectful edge, seeds, errors as values, `Real[A]` and its three interpreters |
| [2. Writing a model](02-writing-a-model.md) | `ParamSpec`, constraints, prior and likelihood apart, derived quantities, the generative part |
| [3. Sampling](03-sampling.md) | `Mcmc.run` in `State` and `Sampler` in `IO`: the same chains, parallel or not |
| [4. Diagnosing](04-diagnosing.md) | Rhat, the effective number of samples, MCSE, divergent transitions, E-BFMI — and leave-one-out and power scaling |
| [5. Checking a model](05-checking-a-model.md) | the prior predictive simulation, the posterior predictive check, simulation-based calibration |
| [6. Predicting](06-predicting.md) | ensembles, contributions, and why percentiles are computed last |
| [7. Nowcasting step by step](07-nowcasting-step-by-step.md) | the invented world end to end: a censored grid, a fit, a nowcast, a forecast, a report by quarter |
| [8. Scoring a forecast](08-scoring-a-forecast.md) | CRPS, calibration, interval scores, a backtest, and the standard error that is wrong |
| [9. Interoperability](09-interoperability.md) | CmdStan's CSV in and out, ArviZ, posteriordb |
| [10. Performance and pitfalls](10-performance.md) | what a gradient costs, how to count nodes, and the five mistakes that cost the most |
| [11. Multilevel models](11-multilevel-models.md) | varying intercepts and slopes, correlated effects, pooling compared, empirical against full Bayes |
| [12. Plots](12-plots.md) | a figure as a value: the catalogue, the statistics behind it, the demonstration page |
| [Appendix A. Coming from Stan](appendix-a-from-stan.md) | every Stan name and its equivalent here, checked in CI |
| [Appendix B. Errors and contracts](appendix-b-errors.md) | every `BayesError`, when it appears, what to do |
| [Appendix C. Classic worked examples](appendix-c-worked-examples.md) | the grid, the conjugate update, intervals, the predictive, Metropolis |
| [Appendix E. Every knob](appendix-e-every-knob.md) | every constructor, constraint, setting and figure, with its default |
| [Glossary](glossary.md) | the book's term, the name in the API, and the term to avoid |

## Reading it in order

**New to Bayesian modelling:** the ideas page, then chapters 0 to 4 in order. That is the loop
everything else sits inside — write, fit, diagnose — and nothing after it will make sense without
it.

**You know Stan or NumPyro:** [appendix A](appendix-a-from-stan.md) first. It maps every name and
every structure to what you already know, and a model translated line by line, rather than
explaining the ideas again. Then chapter 0, and chapter 1 for the two concepts that have no
equivalent where you are coming from.

**You know the statistics and want the API:** chapter 0, chapter 1's first section, then
[appendix E](appendix-e-every-knob.md), which lists everything there is.

After the loop: chapter 5 to find out whether your model is any good, 6 to 8 if you are forecasting
something, 11 if your data arrives in groups, 12 when you want to look at any of it, 10 when it is
too slow or you suspect you have made one of the five usual mistakes, and 9 if you have a fit from
CmdStan already.

The appendices are references rather than chapters, and appendix D is deliberately absent: it is
reserved for the optional `rethinking` module, which is not built yet.

## What this manual is not

It is not the API reference, which is the Scaladoc; not the record of why the library is built this
way, which is `docs/design.md`; and not the evidence that it is correct, which is
`docs/validation.md` — every reference, every tolerance and every deliberate mutation. This manual
points at those rather than repeating them.
