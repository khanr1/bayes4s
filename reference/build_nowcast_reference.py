"""Reference posterior for a one-group delay-count (nowcasting) model on fictional data.

posteriordb has nothing of this shape — a Poisson rate with an AR(1) wobble, thinned by an
ordered-logistic delay distribution that drifts, observed only on the reported part of the grid —
so this script makes one with an independent implementation (NumPyro, long NUTS run, 8 chains).
The data are simulated here from known parameters with a fixed seed; nothing comes from any real
dataset.

Model, mirroring `Ar1Trend` and `OrderedLogitDrift` with their default priors:

  a ~ Normal(0, 2), b ~ Normal(0, 1), (phi + 1)/2 ~ Beta(2, 2), tau ~ HalfNormal(0.5)
  eps[m] ~ Normal(0, 1)
  z[0] = tau/sqrt(1 - phi^2) eps[0],  z[m] = phi z[m-1] + tau eps[m]
  log lambda[m] = a + b t[m] + z[m]

  cut0 ~ Normal(0, 2), inc[j] ~ HalfNormal(1), beta ~ Normal(0, 1)
  cut = [cut0, cut0 + cumsum(inc)]
  F[m, k] = logistic(cut[k] - beta t[m]),  F[m, K] = 1
  p[m, 0] = F[m, 0],  p[m, k] = F[m, k] - F[m, k-1],  p[m, K] = 1 - F[m, K-1]

  y[m, k] ~ Poisson(lambda[m] p[m, k])    for the reported cells only, m + k <= current - 1

Usage: python -I build_nowcast_reference.py <out dir>
"""
import json, sys, pathlib, time
import numpy as np
import jax, jax.numpy as jnp
import numpyro, numpyro.distributions as dist
from numpyro.infer import MCMC, NUTS
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import stan_analyze_reference as S

numpyro.set_host_device_count(1)
OUT = pathlib.Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)

M, K, CURRENT = 24, 5, 24                      # months, maximum delay, months of history
t = (np.arange(M) - (M - 1) / 2.0) / M         # centred, as the Scala suites do
visible = (np.arange(M)[:, None] + np.arange(K + 1)[None, :]) <= CURRENT - 1

# ---- fictional data, simulated from known parameters ----
rng = np.random.default_rng(909090)
true = {"a": 3.5, "b": 0.8, "phi": 0.6, "tau": 0.25,
        "cut0": 0.3, "inc": [0.8, 0.6, 0.5, 0.4], "beta": 0.7}


def wobble(phi, tau, eps):
    lag = np.arange(M)[:, None] - np.arange(M)[None, :]
    w = np.where(lag >= 0, phi ** np.clip(lag, 0, None), 0.0)
    scaled = tau * eps * np.concatenate([[1.0 / np.sqrt(1.0 - phi ** 2)], np.ones(M - 1)])
    return w @ scaled


def delay_pmf(cut0, inc, beta, times):
    cut = np.concatenate([[cut0], cut0 + np.cumsum(inc)])
    f = 1.0 / (1.0 + np.exp(-(cut[None, :] - beta * times[:, None])))
    first = f[:, :1]
    middle = np.diff(f, axis=1)
    last = 1.0 - f[:, -1:]
    return np.concatenate([first, middle, last], axis=1)


eps_true = rng.normal(size=M)
z_true = wobble(true["phi"], true["tau"], eps_true)
lambda_true = np.exp(true["a"] + true["b"] * t + z_true)
p_true = delay_pmf(true["cut0"], np.array(true["inc"]), true["beta"], t)
counts = rng.poisson(lambda_true[:, None] * p_true)

# ---- the same model in NumPyro ----
def model(t, visible, counts=None):
    a = numpyro.sample("a", dist.Normal(0.0, 2.0))
    b = numpyro.sample("b", dist.Normal(0.0, 1.0))
    # A Beta(2, 2) on (phi + 1)/2: its density is proportional to 1 - phi^2, which exactly
    # cancels the stationary scale's 1/sqrt(1 - phi^2) blow-up at the boundary. Uniform on
    # (-1, 1) leaves that singularity in place and NUTS reports divergences from it.
    phi_raw = numpyro.sample("phi_raw", dist.Beta(2.0, 2.0))
    phi = numpyro.deterministic("phi", 2.0 * phi_raw - 1.0)
    tau = numpyro.sample("tau", dist.HalfNormal(0.5))
    eps = numpyro.sample("eps", dist.Normal(0.0, 1.0).expand([M]))
    cut0 = numpyro.sample("cut0", dist.Normal(0.0, 2.0))
    inc = numpyro.sample("inc", dist.HalfNormal(1.0).expand([K - 1]))
    beta = numpyro.sample("beta", dist.Normal(0.0, 1.0))

    lag = jnp.arange(M)[:, None] - jnp.arange(M)[None, :]
    w = jnp.where(lag >= 0, phi ** jnp.clip(lag, 0, None), 0.0)
    scaled = tau * eps * jnp.concatenate([
        jnp.array([1.0]) / jnp.sqrt(1.0 - phi ** 2), jnp.ones(M - 1)])
    z = w @ scaled
    log_lambda = a + b * t + z
    numpyro.deterministic("lambda", jnp.exp(log_lambda))

    cut = jnp.concatenate([jnp.array([cut0]), cut0 + jnp.cumsum(inc)])
    f = jax.nn.sigmoid(cut[None, :] - beta * t[:, None])
    pmf = jnp.concatenate([f[:, :1], jnp.diff(f, axis=1), 1.0 - f[:, -1:]], axis=1)
    rate = jnp.exp(log_lambda)[:, None] * pmf
    # Only the reported cells enter the likelihood: the rest are unknown, not zero.
    with numpyro.handlers.mask(mask=jnp.array(visible)):
        numpyro.sample("y", dist.Poisson(rate), obs=counts)


t0 = time.time()
mcmc = MCMC(NUTS(model, target_accept_prob=0.99), num_warmup=3000, num_samples=4000, num_chains=8,
            chain_method="sequential", progress_bar=False)
mcmc.run(jax.random.PRNGKey(20261106), t=jnp.array(t), visible=visible, counts=jnp.array(counts))
s = mcmc.get_samples(group_by_chain=True)
div = int(np.asarray(mcmc.get_extra_fields(group_by_chain=True)["diverging"]).sum())
elapsed = time.time() - t0

params = {"a": s["a"], "b": s["b"], "phi": s["phi"], "tau": s["tau"],
          "cut0": s["cut0"], "beta": s["beta"]}
for j in range(K - 1):
    params[f"inc[{j + 1}]"] = s["inc"][..., j]
for m in range(M):
    params[f"lambda[{m + 1}]"] = s["lambda"][..., m]

summary = {}
for k, v in params.items():
    x2 = np.asarray(v).T                                   # (draws, chains)
    b_, t_ = S.split_rank_normalized_rhat(x2)
    eb, et = S.split_rank_normalized_ess(x2)
    flat = x2.ravel()
    summary[k] = {"mean": float(flat.mean()), "sd": float(flat.std(ddof=1)),
                  "q05": float(np.quantile(flat, .05)), "q50": float(np.quantile(flat, .5)),
                  "q95": float(np.quantile(flat, .95)), "rhat": float(max(b_, t_)),
                  "ess_bulk": float(eb), "mcse_mean": float(S.mcse_mean(x2)),
                  "mcse_sd": float(S.mcse_sd(x2))}

worst_rhat = max(v["rhat"] for v in summary.values())
worst_ess = min(v["ess_bulk"] for v in summary.values())
print(f"divergences {div}, worst rhat {worst_rhat:.4f}, smallest bulk ESS {worst_ess:.0f}, "
      f"{elapsed:.0f}s")
# One divergent transition in 32 000 survives even at a target acceptance of 0.99: the
# posterior is well explored (rhat below 1.01, bulk ESS above 2000 everywhere) and the count is
# recorded in the fixture rather than hidden by reseeding. Three in 32 000 is the limit.
assert div <= 3, f"{div} divergent transitions"
assert worst_rhat < 1.01, f"worst rhat {worst_rhat}"
assert worst_ess > 2000, f"smallest bulk ESS {worst_ess}"

(OUT / "data.json").write_text(json.dumps({
    "months": M, "maxLag": K, "current": CURRENT,
    "t": t.tolist(),
    "counts": counts.tolist(),
}, indent=1))
(OUT / "reference_summary.json").write_text(json.dumps({
    "posterior": "fictional-delay_count_ar1_ordered_logit",
    "why": "a Poisson rate with an AR(1) wobble, thinned by a drifting ordered-logistic delay, "
           "observed on the reported part of the grid only",
    "inference": {"tool": f"NumPyro {numpyro.__version__}, JAX {jax.__version__}", "chains": 8,
                  "warmup": 3000, "draws_per_chain": 4000, "target_accept": 0.99,
                  "divergences": div},
    "true_parameters_used_to_simulate": true,
    "parameters": summary,
}, indent=1))
print(f"written to {OUT}")
