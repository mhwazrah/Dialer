# Implementation brief (for each phase agent)

Repo: C:\Users\moham\Documents\projects\Right Dialer (Android, Kotlin, XML views, package com.goodwy.dialer).
Plan: docs/handoff/perf-review-plan.md — read the "Corrections after advisor review" section FIRST (it overrides items),
then your phase. Evidence per finding ID: docs/handoff/review/batch1..6.md.

Rules
- Step 0 per item: re-read the cited code. If a finding does not reproduce, skip it and list it as "not reproduced" with a reason.
- Fork is permanently independent from upstream: edit existing files directly; choose the clean, idiomatic design.
- Kotlin best practices: small functions, early returns, no `!!` on nullable platform data, immutable snapshots for
  cross-thread data, main-thread only for UI, background for ContentResolver/IO. No speculative abstractions.
- Minimal churn outside the items: no drive-by renames/reformatting.
- Do NOT commit, push, or change gradle.properties versions. Do NOT edit docs except appending to your phase's
  "Status" note at the end of perf-review-plan.md (one line per item: done / skipped + why).
- Build must pass before you finish (PowerShell):
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; & "$env:JAVA_HOME\bin\java.exe" -classpath ".\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :app:assembleFossRelease --console=plain -q
  (run from the repo root; ignore `w:` warnings unless introduced by you).
- commons library (com.goodwy.commons) is binary; source on GitHub Goodwy/Goodwy-Commons @ 7712ef3eff (use `gh api` to read files when needed).
- Final reply <= 300 words: items done, items skipped (+why), files touched, anything the reviewer should look at closely,
  and emulator test steps for the phase.
