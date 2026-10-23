# irctest environment freeze (T002)

Recorded read-only on 2026-10-22; nothing in the harness was modified, stashed or reset. irctest was **not run** for #100 (the coordinator asked for no irctest runs here; the full comparison is #101's T039).

| Item | Value |
| --- | --- |
| Harness path | `/Users/gcastro/Projects/jircd/irctest` |
| HEAD | `76b91582ca8f8cf6528f750c10f104e0df45865a` |
| Working-tree modifications (preserved) | `irctest/controllers/jircd.py` (+85/-2), `requirements.txt` (+1) |
| Untracked | `logs/` (run output, not part of the freeze) |
| sha256 `irctest/controllers/jircd.py` | `a9c9ba8d43fcaa35e4b138799d921f6c202c7f8f5d879c22650c6e86ff664f94` |
| sha256 `requirements.txt` | `ac78ec82fad14e79d42d0062ee16d677601318d8c502fd2427aae61d3cea9d3d` |
| sha256 `pyproject.toml` | `c08f9d76d0941cabae084d7802e09fb154629cfa86a33409988a3efb1863fea2` |
| sha256 of `git diff` (both modified files together) | `d78cc79c32b2be06d57fbfbc4cb2ec3f9a1df6f414b8190b02abca589979aa9b` |
| Python venv | `.venv`, Python 3.14.9 (venv config built from 3.14.7), `pyvenv.cfg` sha256 `97d27a2826a65e64fe1f46e8fec4c4af3b49e3979ca02955a0e39d3602811dae` |
| Installed packages | bcrypt 5.0.0, ecdsa 0.19.2, filelock 3.32.3, iniconfig 2.3.0, packaging 26.3, pip 26.2.1, pluggy 1.6.0, Pygments 2.21.0, pytest 9.1.1, pytest-timeout 2.4.0, six 1.17.0, websockets 17.0.1 |
| Server launcher | `JIRCD_LAUNCHER=<worktree>/jircd-server/build/install/jircd-server/bin/jircd-server`, built with `./gradlew :jircd-server:installDist` |
| JDK | Temurin 25.0.4+7 (`JIRCD_JAVA_HOME` must point at the same JDK for baseline and candidate) |
| Run flags (T039) | `--controller irctest.controllers.jircd --timeout=60 --timeout-method=signal --junitxml=<per-revision path>` |

## Open item for the coordinator

Isolated harness execution for #101 needs a coordinator-provisioned harness worktree carrying the reviewed controller patch above; I did not create one (git writes are out of scope). The recorded hashes let #101 prove it ran the identical frozen state.
