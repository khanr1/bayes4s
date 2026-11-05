"""Extract the reference values of Stan Math's distribution tests (test/prob/<dist>/*_test.hpp)
into JSON, and cross-check each value against an independent SciPy implementation.

Usage: python -I extract_stan_math_distributions.py <stan-math checkout> <out.json>
"""
import json, math, re, sys, pathlib
import numpy as np
from scipy import stats, special

ROOT = pathlib.Path(sys.argv[1]) / "test" / "prob"
OUT = pathlib.Path(sys.argv[2])

DISTS = ["normal", "lognormal", "gamma", "beta", "poisson", "neg_binomial_2", "neg_binomial",
         "logistic", "exponential", "student_t", "cauchy", "binomial", "bernoulli",
         "beta_binomial", "inv_gamma", "double_exponential", "std_normal", "chi_square"]
KINDS = {"": "lpdf", "_cdf": "cdf", "_cdf_log": "lcdf", "_ccdf_log": "lccdf", "_log": "lpdf_log_param",
         "_logit": "lpdf_logit"}

NUM = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"

def parse_value(expr, param=None):
    e = expr.strip().rstrip("U").strip()
    e = e.replace("std::", "")
    if "numeric_limits<double>::infinity()" in e or e in ("INFTY", "inf"):
        return float("-inf") if e.startswith("-") else float("inf")
    if "quiet_NaN" in e or "NOT_A_NUMBER" in e:
        return float("nan")
    if "LOG_ZERO" in e:
        return float("-inf")
    try:
        return float(eval(e, {"__builtins__": {}}, {"log": math.log, "exp": math.exp, "sqrt": math.sqrt, "logit": lambda p: math.log(p / (1 - p)), "param": param or {}}))
    except Exception:
        return None

def block(text, name):
    m = re.search(r"void\s+" + name + r"\s*\([^)]*\)\s*\{", text)
    if not m:
        return None
    i, depth = m.end(), 1
    while depth and i < len(text):
        depth += {"{": 1, "}": -1}.get(text[i], 0); i += 1
    return text[m.end():i - 1]

def parse_valid(body):
    cases, cur, names, problems = [], {}, {}, []
    for line in body.splitlines():   # parameter names come from trailing comments
        m = re.match(r"\s*param\[(\d+)\]\s*=.*//\s*([A-Za-z_]\w*)", line)
        if m: names[int(m.group(1))] = m.group(2)
    code = "\n".join(l.split("//")[0] for l in body.splitlines())
    for stmt in code.split(";"):      # statements may span several lines
        s = " ".join(stmt.split())
        m = re.fullmatch(r"param\[(\d+)\]\s*=\s*(.+)", s)
        if m:
            v = parse_value(m.group(2))
            if v is None: problems.append(s)
            cur[int(m.group(1))] = v; continue
        if re.fullmatch(r"parameters\.push_back\(param\)", s):
            cases.append({"params": [cur[k] for k in sorted(cur)]}); continue
        m = re.fullmatch(r"(\w+)\.push_back\((.+)\)", s)
        if m and m.group(1) != "parameters" and cases and "expected" not in cases[-1]:
            v = parse_value(m.group(2), dict(cur))
            if v is None: problems.append(s)
            cases[-1]["expected"] = v
    arity = max((len(c["params"]) for c in cases), default=0)
    return cases, [names.get(i, f"p{i}") for i in range(arity)], problems

def parse_invalid(body):
    idx = [int(x) for x in re.findall(r"index\.push_back\((\d+)U?\)", body)]
    vals = [parse_value(x) for x in re.findall(r"value\.push_back\((.+?)\);", body)]
    return [{"index": i, "value": v} for i, v in zip(idx, vals)]

# SciPy oracles: f(params) -> value, params in Stan's order
def nb2(n, mu, phi):
    return stats.nbinom.logpmf(n, phi, phi / (mu + phi))
ORACLE = {
    ("normal", "lpdf"): lambda y, mu, s: stats.norm.logpdf(y, mu, s),
    ("normal", "cdf"): lambda y, mu, s: stats.norm.cdf(y, mu, s),
    ("normal", "lcdf"): lambda y, mu, s: stats.norm.logcdf(y, mu, s),
    ("normal", "lccdf"): lambda y, mu, s: stats.norm.logsf(y, mu, s),
    ("std_normal", "lpdf"): lambda y: stats.norm.logpdf(y),
    ("lognormal", "lpdf"): lambda y, mu, s: stats.lognorm.logpdf(y, s, scale=math.exp(mu)),
    ("lognormal", "cdf"): lambda y, mu, s: stats.lognorm.cdf(y, s, scale=math.exp(mu)),
    ("lognormal", "lcdf"): lambda y, mu, s: stats.lognorm.logcdf(y, s, scale=math.exp(mu)),
    ("lognormal", "lccdf"): lambda y, mu, s: stats.lognorm.logsf(y, s, scale=math.exp(mu)),
    ("gamma", "lpdf"): lambda y, a, b: stats.gamma.logpdf(y, a, scale=1 / b),
    ("gamma", "cdf"): lambda y, a, b: stats.gamma.cdf(y, a, scale=1 / b),
    ("gamma", "lcdf"): lambda y, a, b: stats.gamma.logcdf(y, a, scale=1 / b),
    ("gamma", "lccdf"): lambda y, a, b: stats.gamma.logsf(y, a, scale=1 / b),
    ("inv_gamma", "lpdf"): lambda y, a, b: stats.invgamma.logpdf(y, a, scale=b),
    ("beta", "lpdf"): lambda y, a, b: stats.beta.logpdf(y, a, b),
    ("beta", "cdf"): lambda y, a, b: stats.beta.cdf(y, a, b),
    ("beta", "lcdf"): lambda y, a, b: stats.beta.logcdf(y, a, b),
    ("beta", "lccdf"): lambda y, a, b: stats.beta.logsf(y, a, b),
    ("poisson", "lpdf"): lambda n, l: stats.poisson.logpmf(n, l),
    ("poisson", "cdf"): lambda n, l: stats.poisson.cdf(n, l),
    ("poisson", "lcdf"): lambda n, l: stats.poisson.logcdf(n, l),
    ("poisson", "lccdf"): lambda n, l: stats.poisson.logsf(n, l),
    ("poisson", "lpdf_log_param"): lambda n, a: stats.poisson.logpmf(n, math.exp(a)),
    ("neg_binomial_2", "lpdf"): nb2,
    ("neg_binomial_2", "cdf"): lambda n, mu, phi: stats.nbinom.cdf(n, phi, phi / (mu + phi)),
    ("neg_binomial_2", "lcdf"): lambda n, mu, phi: stats.nbinom.logcdf(n, phi, phi / (mu + phi)),
    ("neg_binomial_2", "lccdf"): lambda n, mu, phi: stats.nbinom.logsf(n, phi, phi / (mu + phi)),
    ("neg_binomial_2", "lpdf_log_param"): lambda n, eta, phi: nb2(n, math.exp(eta), phi),
    ("neg_binomial", "lpdf"): lambda n, a, b: stats.nbinom.logpmf(n, a, b / (1 + b)),
    ("logistic", "lpdf"): lambda y, mu, s: stats.logistic.logpdf(y, mu, s),
    ("logistic", "cdf"): lambda y, mu, s: stats.logistic.cdf(y, mu, s),
    ("logistic", "lcdf"): lambda y, mu, s: stats.logistic.logcdf(y, mu, s),
    ("logistic", "lccdf"): lambda y, mu, s: stats.logistic.logsf(y, mu, s),
    ("exponential", "lpdf"): lambda y, b: stats.expon.logpdf(y, scale=1 / b),
    ("exponential", "cdf"): lambda y, b: stats.expon.cdf(y, scale=1 / b),
    ("exponential", "lcdf"): lambda y, b: stats.expon.logcdf(y, scale=1 / b),
    ("exponential", "lccdf"): lambda y, b: stats.expon.logsf(y, scale=1 / b),
    ("student_t", "lpdf"): lambda y, nu, mu, s: stats.t.logpdf(y, nu, mu, s),
    ("cauchy", "lpdf"): lambda y, mu, s: stats.cauchy.logpdf(y, mu, s),
    ("double_exponential", "lpdf"): lambda y, mu, s: stats.laplace.logpdf(y, mu, s),
    ("chi_square", "lpdf"): lambda y, nu: stats.chi2.logpdf(y, nu),
    ("binomial", "lpdf"): lambda n, N, p: stats.binom.logpmf(n, N, p),
    ("bernoulli", "lpdf"): lambda n, p: stats.bernoulli.logpmf(n, p),
    ("bernoulli", "lpdf_logit"): lambda n, a: stats.bernoulli.logpmf(n, 1 / (1 + math.exp(-a))),
    ("binomial", "lpdf_logit"): lambda n, N, a: stats.binom.logpmf(n, N, 1 / (1 + math.exp(-a))),
    ("beta_binomial", "lpdf"): lambda n, N, a, b: stats.betabinom.logpmf(n, N, a, b),
}

def close(a, b):
    if a is None or b is None: return False
    if math.isinf(a) or math.isinf(b): return a == b
    return abs(a - b) <= 1e-8 * max(1.0, abs(a))

out, report = {"source": "stan-dev/math test/prob (BSD-3-Clause)", "distributions": {}}, []
for d in DISTS:
    for suffix, kind in KINDS.items():
        f = ROOT / d / f"{d}{suffix}_test.hpp"
        if not f.exists(): continue
        text = f.read_text()
        vb = block(text, "valid_values")
        if vb is None: continue
        cases, names, problems = parse_valid(vb)
        ib = block(text, "invalid_values")
        invalid = parse_invalid(ib) if ib else []
        args = re.search(r"//\s*Arguments:\s*(.+)", text)
        oracle = ORACLE.get((d, kind))
        n_ok = n_bad = 0
        for c in cases:
            if oracle is None: c["scipy_check"] = "no_oracle"; continue
            with np.errstate(all="ignore"):
                ref = float(oracle(*c["params"]))
            c["scipy_value"] = ref
            ok = close(c.get("expected"), ref)
            c["scipy_check"] = "agree" if ok else "DISAGREE"
            n_ok += ok; n_bad += (not ok)
        out["distributions"].setdefault(d, {})[kind] = {
            "file": str(f.relative_to(ROOT.parent.parent)),
            "arguments": args.group(1).strip() if args else None,
            "param_names": names, "valid": cases, "invalid": invalid,
            "parse_problems": problems,
        }
        report.append(f"{d:20s} {kind:16s} cases={len(cases):3d} invalid={len(invalid):2d} "
                      f"scipy agree={n_ok} disagree={n_bad} problems={len(problems)}")

OUT.write_text(json.dumps(out, indent=1, allow_nan=True))
print("\n".join(report))
