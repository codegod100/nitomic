# Package

version       = "0.1.0"
author        = "nitomic contributors"
description   = "Datomic's peer API for clonim"
license       = "MIT"
srcDir        = "src"
# nitomic is Clojure source for clonim, not Nim modules: install src/ as is so
# `--source-path "$(nimble path nitomic)"` finds datomic.api and friends.
installExt    = @["clj"]

# Dependencies

requires "nim >= 2.2.12"
requires "https://github.com/codegod100/clonim"

# Tasks

task test, "Run the test programs under clonim and diff their output":
  exec "test/run.sh"

task example, "Run Datomic's getting-started example (examples/seattle) under clonim":
  let clonim = getEnv("CLONIM", "clonim")
  exec clonim & " run examples/seattle/getting_started.clj --source-path src"
