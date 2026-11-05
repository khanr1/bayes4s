# bayes4s manual

This manual is written phase by phase, alongside the code it describes: each phase updates the
chapters that cover what it delivers, and every code block below is compiled by mdoc against the
current build, so an example that stops compiling fails CI.

Phase 1 delivers the core and the laws as values, and phase 2 the automatic differentiation, so the
chapters here are: the concepts the whole library rests on — `Real[A]` included — the cost of those
concepts, the error contract, and the classic worked examples. The quick start and the sampling
chapters arrive with the sampler.

| Chapter | State |
|---|---|
| [Glossary](glossary.md) | written; the vocabulary of *Statistical Rethinking*, checked in CI |
| [1. Concepts](01-concepts.md) | written, including `Real[A]` and the three interpreters |
| [10. Performance and pitfalls](10-performance.md) | the cost of `Real[A]` is measured; the pitfalls arrive with phase 12 |
| [Appendix B. Errors and contracts](appendix-b-errors.md) | written |
| [Appendix C. Classic worked examples](appendix-c-worked-examples.md) | written |

| Chapter | Arrives with phase |
|---|---|
| 1. Concepts, continued | 3 |
| 2. Writing a model | 3 |
| 0. Quick start | 4, finalised in 11 |
| 3. Sampling | 4 and 11 |
| 4. Diagnosing | 5 and 6 |
| 5. Checking a model | 3 and 9 |
| 6. Predicting | 7 |
| 7. Nowcasting step by step | 9 and 10 |
| 8. Scoring a forecast | 8 and 10 |
| 9. Interoperability | 5 and 11 |
| 10. Performance, completed | 12 |
| Appendix A. Coming from Stan and NumPyro | 6 |
| Appendix B, completed | 12 |
| Appendix C, completed | 4 and 5 |
