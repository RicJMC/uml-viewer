# C++ support (first slice)

The viewer consumes **clang-uml JSON class diagrams**. It does not parse C++.
The adapter displays namespaces, classes, `extension` (inheritance),
`dependency`, `association`, and `aggregation` relationships, and navigates
to class, method, and field source locations. Class IDs include the qualified namespace; overloads keep
parameter types and source locations so same-named methods remain distinct.

## Windows setup

Install Java 21+, the [Clojure CLI](https://clojure.org/guides/install_clojure),
Visual Studio 2022 Build Tools with the C++ workload, CMake, Ninja, and
[clang-uml](https://clang-uml.github.io/md_docs_2installation.html). Use the
VS 2022 x64 Developer PowerShell so `cl.exe` is on `PATH`. Versions exercised
for this change:

| Tool | Version |
|------|---------|
| Clojure CLI | 1.12.6.1673 |
| Temurin JDK | 21.0.11 |
| clang-uml | 0.6.3 |
| MSVC | 19.44.35224 |
| CMake | 4.2.3 |
| Ninja | 1.13.0 |

From a VS 2022 x64 Developer PowerShell at the repository root, run the
complete real-tool fixture:

```powershell
.\tools\verify-cpp-fixture.ps1
```

The script builds a temporary copy of `examples/cpp-fixture` with MSVC/Ninja,
runs clang-uml extraction, converts JSON to EDN, checks class identities,
relationships, overloads and source locations, removes/restores a dependency,
and checks repeat conversion hashes. Pass `-ClangUmlPath <path>` if the
clang-uml executable is not on `PATH`. The script leaves its generated files
under ignored `target/` and prints the final viewer input path.

For a project configured with clang-uml, regenerate and open a diagram with:

```powershell
cmake -S . -B build -G Ninja -DCMAKE_CXX_COMPILER=cl -DCMAKE_EXPORT_COMPILE_COMMANDS=ON
cmake --build build
clang-uml -c .clang-uml -n class_diagram -g json
clj -M:cpp-ir <clang-uml-json> <viewer-output.edn> <source-root>
clj -M:run <viewer-output.edn>
```

After changing C++ source, repeat the build, extraction, and conversion with
the same EDN output path; the running viewer watches that file and reloads it.
`clj -M:cpp-ir` accepts the JSON emitted by `clang-uml` and writes a Clojure
EDN document. `spec/resources/cpp-fixture.json` is a captured output from the
real clang-uml 0.6.3 run, used by fast adapter specs; the PowerShell command
above re-extracts the live fixture and does not use that snapshot as a mock.

## Data and limits

The adapter reads clang-uml `elements`, `relationships`, `bases`, and
`source_location` data. It maps clang-uml class IDs to viewer edges, retains
relationship labels, access, and original IDs, and preserves distinct
relationships between the same two classes. Aggregation and composition
diamonds appear at the source (owner) end of an edge. It stores a canonical
source root plus clang-uml's file,
line, and column. Because clang-uml already emits a complete class graph,
this path converts its JSON directly to hierarchical viewer IR; it does not
change the `LanguageGraph` protocol, whose current scanner contract omits the
method/member identities and source locations needed here. Clojure continues
to use its existing scanner and policy flow. The generated EDN records an
absolute source root, so regenerate it on each machine. The adapter does not
infer missing relationships. Unsupported
element or relationship kinds, unresolved endpoints, and missing source
locations are reported in the EDN `:diagnostics`; duplicate class identities
are rejected. Metrics are explicitly `:unavailable` for C++.
The viewer skips its Clojure metric overlay for C++ documents: that overlay
groups operations by name and would collapse C++ overloads. Clojure diagrams
still use the overlay.

This slice does not model templates as specializations, enums, free functions,
or non-class UML entities; clang-uml elements outside `class` are diagnosed
and omitted. It supports only the class-diagram JSON relationships above.
The upstream checkout used for this fork had no `LICENSE` file; this change
does not add or assume a license grant. Resolve licensing with upstream before
reusing or releasing the code beyond the fork and review process.

## Checkpoint log

| Checkpoint | Evidence | State / next action |
|------------|----------|---------------------|
| 1. Fork and baseline | Fork `mak1jk/uml-viewer`, `origin` fork, `upstream` `unclebob/uml-viewer`, branch `feat/cpp-clang-uml`; original suite: 343 examples, 2 failures with en-US locale | Done; both failures are recorded below |
| 2. Real extraction | CMake/MSVC build and clang-uml 0.6.3 JSON extraction from the compilable fixture; two same-named `Node` classes, inheritance, dependency, overloads | Done |
| 3. Adapter | Windows/en-US full suite: 351 examples, 0 failures, 1396 assertions; C++ and source-window specs pass. A regression spec checks that nearby Clojure metrics do not collapse C++ overloads | Done |
| 4. Viewer | Windows UI: opened `model`, `store`, and `app`; distinct `Node` classes and both `run` overloads visible; source positions highlighted. Real re-extraction on the watched path changed relationships 2 → 1 → 2 and the dependency arrow disappeared/reappeared | Done |

For checkpoint 4, source clicks opened `model/node.hpp:6`,
`store/node.hpp:4`, and `controller.hpp:10` with the reported line and
column highlighted. After each fixture change, CMake rebuilt it, clang-uml
re-extracted JSON, and the adapter rewrote the same EDN file. The inheritance
arrow remained visible during dependency removal. The restored EDN SHA256
`4B2735506E8A067C5ABACD85FDDE156150004FD9EE2FB09CA3CE32E821CAA956`
matches the earlier repeat conversion.

The original Windows/en-US baseline had 343 examples and two failures. The
stderr assertion now expects the platform newline emitted by `println`, and
the mailbox assertion compares canonical paths so Windows separators do not
change its meaning. The current full suite has 351 examples, 0 failures, and
1396 assertions; no tests were skipped.
