# Installation

nitomic is Clojure source, not a compiled library. You need
[clonim](https://github.com/codegod100/clonim) to compile programs, and you
point clonim at nitomic's `src/` directory with `--source-path`.

## Requirements

- [Nim](https://nim-lang.org) 2.2.12 or later, with Nimble.
- clonim, at a revision from `main` that includes
  [codegod100/clonim#1](https://github.com/codegod100/clonim/pull/1). That
  change added the Clojure features nitomic relies on:
  `reify`/`deftype`/`defprotocol`, `#inst`/`#uuid` and `*data-readers*`,
  `compare` and comparator sorts, `for`/`doseq` modifiers, `ex-data`, and a
  set of collection functions.
- On Linux, the PCRE runtime library (`libpcre3` on Debian and Ubuntu).

If you don't have Nim yet, `choosenim` is the quickest route:

```bash
curl https://nim-lang.org/choosenim/init.sh -sSf | sh
export PATH="$HOME/.nimble/bin:$PATH"
```

## With Nimble

nitomic is a Nimble package, and installing it also installs clonim:

```bash
nimble install https://github.com/codegod100/nitomic
```

Nimble installs `src/` as is (the package keeps only `.clj` files), so the
package directory is itself the source path:

```bash
clonim run hello.clj --source-path "$(nimble path nitomic)"
```

## From a checkout

```bash
git clone https://github.com/codegod100/nitomic
cd nitomic
nimble install          # installs the working tree (and clonim)
```

A checkout also gives you two Nimble tasks:

| task | what it does |
|---|---|
| `nimble example` | runs the Seattle walkthrough, `examples/seattle/getting_started.clj` |
| `nimble test` | runs every test program and diffs its output against `test/expected/` |

Both use the `clonim` on your `PATH`. Set `CLONIM` to use a different one:

```bash
CLONIM=path/to/clonim/bin/clonim nimble example
```

## Building clonim by hand

This is what CI does, and it is handy when you want a specific clonim
revision:

```bash
git clone https://github.com/codegod100/clonim
cd clonim
nim c --hints:off --warnings:off -o:bin/clonim src/clonim.nim
```

Then use `path/to/clonim/bin/clonim` wherever this book says `clonim`.
