# Glossary

This manual speaks the vocabulary of McElreath's *Statistical Rethinking* (2nd edition, 2020),
and the library's API keeps the names of Stan. That is deliberate on both sides: a reader who has
the book should recognise every word of the prose, and a reader coming from Stan, posteriordb or
the reference data should recognise every name in the code. Renaming `Normal` to `Gaussian` would
break the second to serve the first.

This page is the bridge. The manual names both at the first occurrence of each idea — "a Gaussian
likelihood (`LogDensity.normal`)" — and then uses the book's word.

`scripts/check-terminology.py` enforces the right-hand column over `docs/manual/`, outside code
blocks and inline code, from `docs/manual/terminology.csv`. Appendix A is exempt: its job is to
speak Stan.

| In this manual | In the API | Avoided in prose |
|---|---|---|
| Gaussian | `Normal`, `LogDensity.normal` | normal (the code name stays) |
| gamma-Poisson | `NegBinomial`, `NegBinomial2` | negative binomial |
| beta-binomial | `BetaBinomial` | |
| ordered categorical, cumulative logit link | `OrderedLogistic` | ordinal regression |
| link function, log link, logit link | `PoissonLog`, `BernoulliLogit`, `BinomialLogit` | |
| log-odds | the `eta` of `BernoulliLogit` | logit scale |
| over-dispersion | `BetaBinomial`, `NegBinomial2` | extra-Poisson variation |
| samples from the posterior | `Draws`, `Chain` | draws (the code name stays) |
| grid approximation | the worked example of appendix C | |
| maximum a posteriori | | posterior mode |
| compatibility interval | `Intervals.equalTailed` | credible interval, confidence interval |
| percentile interval | `Intervals.equalTailed` | equal-tailed interval |
| highest posterior density interval | `Intervals.hpd` | HPD |
| warmup | `RunSettings.warmup` | burn-in |
| divergent transitions | `Transition.divergent` | divergences |
| effective number of samples | `essBulk`, `essTail` | effective sample size, ESS |
| Rhat | `Diagnostics.rhat` | Gelman-Rubin statistic |
| regularizing prior, weakly informative prior | | vague prior |
| multilevel model, models with memory | `Hierarchy` | hierarchical model, mixed model |
| clusters | groups | levels, units |
| varying intercepts, varying slopes, varying effects | group effects | random intercepts, random slopes, random effects |
| complete pooling, no pooling, partial pooling | | |
| shrinkage, adaptive regularization | `GammaPoissonShrinkage` | |
| centered and non-centered parameterization | `OffsetMultiplier` | |
| LKJcorr prior, Cholesky factor | `LkjCorrCholesky`, `CholeskyFactorCorr` | |
| fake data | `Generative.simulateData`, `examples.FictionalWorld` | synthetic data |

## Where the rule bends

A flagged word sometimes means something else, and the line then ends with `<!-- term-ok -->` and
says why. The case that comes up is "draw": a draw from a random number generator is not a sample
from a posterior, and calling it one would be wrong rather than merely unidiomatic.

Two of the book's own metaphors — the small world and the large world, and the golem — belong to
the book. The manual may use them once, with the citation, and nothing in it depends on them: a
reader who has not read *Statistical Rethinking* should still be able to follow every page.

Borrowing the vocabulary is not borrowing the book. No text, figure, code or data set from
*Statistical Rethinking* or from the `rethinking` R package appears here: the manual reformulates,
cites the book as a reference, and uses the fictional world of its own examples.
