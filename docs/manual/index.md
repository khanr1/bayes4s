# bayes4s manual

This manual is written phase by phase, alongside the code it describes: each phase updates the
chapters that cover what it delivers, and every code block below is compiled by mdoc against the
current build, so an example that stops compiling fails CI.

Phase 1 delivers the core and the laws as values, phase 2 the automatic differentiation, phase 3 the
models, phase 4 the sampler, phase 5 the diagnostics and phase 6b the multilevel models and model
criticism, so the chapters here are: a quick start, the concepts the whole library rests on —
`Real[A]` included — how to write a model, how to sample it, how to tell whether to believe the fit
and whether the model is any good, how to pool across groups and predict a group you have not seen,
how to read and write CmdStan's files, the cost of those concepts, the error contract, and the
classic worked examples.

| Chapter | State |
|---|---|
| [Glossary](glossary.md) | written; the vocabulary of *Statistical Rethinking*, checked in CI |
| [0. Quick start](00-quick-start.md) | written; the summary it points to arrives with the diagnostics |
| [1. Concepts](01-concepts.md) | written, including `Real[A]` and the three interpreters |
| [2. Writing a model](02-writing-a-model.md) | written; the generative part is used in earnest with simulation-based calibration |
| [3. Sampling](03-sampling.md) | written for the pure sampler; the `IO` half arrives with the `effect` module |
| [4. Diagnosing](04-diagnosing.md) | written, model criticism included: leave-one-out and power scaling |
| [9. Interoperability](09-interoperability.md) | written for the pure parser; reading and writing files arrives with the `effect` module |
| [11. Multilevel models](11-multilevel-models.md) | written; the fictional world's version of the example arrives with the `examples` module |
| [Appendix A. Coming from Stan](appendix-a-from-stan.md) | written; the table is checked in CI |
| [10. Performance and pitfalls](10-performance.md) | the cost of `Real[A]` is measured; the pitfalls arrive with phase 12 |
| [Appendix B. Errors and contracts](appendix-b-errors.md) | written |
| [Appendix C. Classic worked examples](appendix-c-worked-examples.md) | written |

| Chapter | Arrives with phase |
|---|---|
| 1. Concepts, continued | 3 |
| 3. Sampling, the `IO` half | 11 |
| 5. Checking a model | 3 and 9 |
| 6. Predicting | 7 |
| 7. Nowcasting step by step | 9 and 10 |
| 8. Scoring a forecast | 8 and 10 |
| 9. Interoperability, with files | 11 |
| 10. Performance, completed | 12 |
| Appendix B, completed | 12 |
| Appendix C, completed | 4 and 5 |
