# bayes4s manual

This manual is written phase by phase, alongside the code it describes: each phase updates the
chapters that cover what it delivers, and every code block below is compiled by mdoc against the
current build, so an example that stops compiling fails CI.

Phase 1 delivers the core and the laws as values, phase 2 the automatic differentiation, phase 3 the
models, phase 4 the sampler, phase 5 the diagnostics, phase 6b the multilevel models and model
criticism, phase 7 the simulation of futures, phase 8 the scoring of them, phase 9 the nowcasting
models that use all of it, phase 10 their group structures and phase 11 the effectful edge, so the
chapters here are: a quick start, the concepts the whole library rests on —
`Real[A]` included — how to write a model, how to sample it, how to tell whether to believe the fit
and whether the model is any good, how to pool across groups and predict a group you have not seen,
how to simulate a future and summarise it without adding percentiles, how to score one after the
fact, how to check a model three ways, how to nowcast a count series whose recent periods are
incomplete, how to read and write CmdStan's files, the cost of those concepts, the error contract, and the
classic worked examples.

| Chapter | State |
|---|---|
| [Glossary](glossary.md) | written; the vocabulary of *Statistical Rethinking*, checked in CI |
| [0. Quick start](00-quick-start.md) | written; the summary it points to arrives with the diagnostics |
| [1. Concepts](01-concepts.md) | written, including `Real[A]` and the three interpreters |
| [2. Writing a model](02-writing-a-model.md) | written; the generative part is used in earnest with simulation-based calibration |
| [3. Sampling](03-sampling.md) | written, the `IO` half included: streams, parallel chains, cancellation |
| [4. Diagnosing](04-diagnosing.md) | written, model criticism included: leave-one-out and power scaling |
| [5. Checking a model](05-checking-a-model.md) | written; prior and posterior predictive checks, and calibration |
| [6. Predicting](06-predicting.md) | written |
| [7. Nowcasting step by step](07-nowcasting-step-by-step.md) | written, groups included; the fictional world's version arrives with the `examples` module |
| [8. Scoring a forecast](08-scoring-a-forecast.md) | written, with the worked comparison of two rate variants |
| [9. Interoperability](09-interoperability.md) | written, files included |
| [10. Performance and pitfalls](10-performance.md) | the cost of `Real[A]` is measured; the pitfalls arrive with phase 12 |
| [11. Multilevel models](11-multilevel-models.md) | written; the fictional world's version of the example arrives with the `examples` module |
| [Appendix A. Coming from Stan](appendix-a-from-stan.md) | written; the table is checked in CI |
| [Appendix B. Errors and contracts](appendix-b-errors.md) | written |
| [Appendix C. Classic worked examples](appendix-c-worked-examples.md) | written |

| Chapter | Arrives with phase |
|---|---|
| 1. Concepts, continued | 3 |
| 7. Nowcasting, the fictional world's example | 11 |
| 10. Performance, completed | 12 |
| Appendix B, completed | 12 |
| Appendix C, completed | 4 and 5 |
