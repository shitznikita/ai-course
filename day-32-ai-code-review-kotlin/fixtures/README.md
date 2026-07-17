# Day 32 fixtures

Fixtures are committed, inert review inputs. They do not contain executable
workflow code, credentials, or a real pull-request payload. `fixture-demo`
uses `demo.diff` and `repository/` to demonstrate a review without Eliza or
GitHub credentials.

- `demo.diff` is a static patch for `src/ReviewTarget.kt`.
- `repository/` is the small trusted corpus that supplies architecture and
  repository evidence to the deterministic fixture reviewer.
- `base/` and `head/` are a tiny visual nullability example. `head/` is data
  only; the GitHub workflow never checks out or runs it.
