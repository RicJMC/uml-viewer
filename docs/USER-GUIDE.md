# UML viewer — user guide

How to read the window, which control does what, and how to check that the
picture matches the source. The short reference for every click is
[CHEATSHEET.md](CHEATSHEET.md); this page explains the model behind it.

## What the window is

The viewer draws the architecture of the project in the working directory.
The canvas is on the left. The **Inspector** on the right is both the
project selector and the help/detail panel.

```
+---------------------------------------------------+--------------------+
| title / ← current package                         | Inspector          |
|                                                   | Real diagram       |
|     +--------+          arrows                    | Proposals          |
|     |  box   |  -------------------------------->  | New Proposal       |
|     +--------+                                    | Declutter none     |
|                                                   | Fit (F)            |
|                                                   | detail or help     |
|                                                   |                    |
|  N boxes · M arrows · zoom 100% · F fits          | Regen              |
+---------------------------------------------------+--------------------+
```

The window is **live**. It reloads the EDN diagram when the file or the
`.metrics/` snapshots change. In standalone mode `R` reloads immediately;
`Regen` reruns the generator.

## Run it

```sh
clj -M:run                           # companion mode (starts an agent)
clj -M:run --standalone              # no agent; load the configured EDN
clj -M:run --standalone target/foo.edn
clj -M:run --screenshot=/tmp/view.png target/foo.edn
clj -M:run --help
```

`--screenshot` saves the canvas after the first fitted frame and exits. It
implies `--standalone`, so it never starts an agent. Use it for design
reviews and documentation. Screenshots are deliberately not committed to
this repository; keep them in `/tmp` or another ignored directory.

Relative `:file` paths in a generated EDN are resolved against the process
working directory, so start the viewer from the inspected project (as the
`./uml` wrapper does) or use absolute source paths.

With `--restart` the viewer restores the saved depth, pan, zoom, proposal,
and selection from `.uml-viewer/session.edn`. `--standalone` also restores
that session. To get the automatic fit view during a demo, remove
`.uml-viewer/session.edn` in the inspected project or press **F**.

### Generate a diagram

```sh
# Clojure / TypeScript / Rust / Python / Java / multi-source
clj -M:ir                          # examples/uml-viewer.policy.edn
clj -M:ir path/to/policy.edn

# C++ (clang-uml JSON -> viewer EDN)
clj -M:cpp-ir path/to/clang-uml.json target/cpp.edn examples/cpp-fixture
```

The viewer watches the `:out` file named by the policy. Edit the **policy**,
not the generated EDN.

### Language quick reference

| Language | Policy `:lang` | One box per | Notes |
|---|---|---|---|
| Clojure | `:clojure` (default) | namespace | CRAP/mutation from `.metrics/` |
| Python | `:python` | `.py` module | classes, methods, imports, inheritance |
| TypeScript | `:typescript` | module | NodeNext `./x.js` → `x.ts` resolved |
| Rust | `:rust` | file | `invoke(...)` → Rust command links |
| Java | `:java` | `.java` file | package names the class; `extends` / `implements`; test roots skipped |
| Go | `:go` | package | exported funcs; `go.mod` module path |
| Kotlin | `:kotlin` | type | `<File>Kt` for top-level functions |
| C# | `:csharp` | type | namespaces and `using` |
| Ruby | `:ruby` | file | Zeitwerk, Rails, Grape, Rake |
| Haskell | `:haskell` | module | layout-aware import scanner |
| C++ | via `-M:cpp-ir` | namespace (view) / class | clang-uml JSON; metrics unavailable |

More than one language uses `:sources` (a vector of
`{:lang :root :prefix?}`), not several policies. Java uses the package as
its namespace, so the same policy prefix works for a conventional
`src/main/java` tree.

## Read the diagram

### Boxes

- **Normal box** — a class, module, or namespace segment.
- **Italic name** — a component/namespace or a non-class classifier, not a
  class. Double-click it to go inside.
- **`«abstract»` / `«interface»` / `«enumeration»`** — Java/C#/Python-style
  classifier stereotypes. `α` and `I` are the compact Clojure markers.
- **Level number** — when the policy has `:levels`, `0` is innermost and
  is drawn at the bottom. A good dependency points down (outer → inner).
- **Green/amber/brown fill** — the combined CRAP + mutation grade. Missing
  measurements count as red, not as success.
- **`C` and `M` dots** — the CRAP and mutation grades separately.
- **Oval** — a foreign library listed in the policy.
- **Small labelled box attached to a class** — a **port**. It is a
  dependency that crosses the package boundary. The arrow points from the
  outside box into the class (in-port) or from the class to the outside
  box (out-port). Click a port to try to open the outside class's card.

### Arrows

- Plain arrow — dependency (“this uses that”).
- Hollow triangle — inheritance (`extends`) or interface/protocol
  implementation. The class card names the exact relationship; the line
  itself is not dotted.
- Hollow/filled diamond — aggregation/composition, with the diamond on the
  **owner** side (the whole, not the part).
- **Red arrow** — dependency-rule violation. Bold red when it belongs to
  the current selection.
- **Collapsed component arrow** — when both ends of a class-level
  inheritance/implements/aggregation edge collapse to namespace boxes, the
  viewer draws a plain dependency. Namespaces do not inherit; the precise
  relationship is still shown inside the package, on the class card.
- Hover an arrow for the list of underlying `from -> to` pairs.

### Levels and collapse

The tree **is** the source namespaces. `:prefix` strips the common root;
every remaining dot is a level. The viewer never invents a package: use
`:proposals` when you want to view a grouping that is not in the code.

At a namespace level, a box can stand for many classes. Double-click it
(or use the chevron/back label) until the boxes are classes. The class
card lists the class's members and its relationships; clicking a member
opens the source at the declaration.

## Controls

### Canvas

| Action | Result |
|---|---|
| Click a box | Select it; Inspector shows its card |
| Double-click a class | Open the class card and source links |
| Double-click a component | Drill into the next namespace level |
| `Esc` / `←` label | Go up one level |
| Right-click a box | Refresh CRAP, Refresh Mutation, Refresh All Mutation, Omit |
| Hover an arrow | List the underlying class pairs |
| Click empty canvas | Deselect |

### Inspector

| Control | Result |
|---|---|
| Real diagram row | Return to the namespace tree |
| A proposal row | Show that proposal (marked **PROPOSAL**) |
| **New Proposal** | Add an empty proposal and select it |
| **Declutter …** | Cycle: none → arrows → remove arrows → elements → classes |
| **Fit (F)** | Fit the whole diagram in the canvas and center it |
| **Regen** | Standalone: regenerate locally. Companion: queue a regen |
| Proposal row, right-click | Rename or delete the proposal |

### Keyboard and mouse

| Input | Result |
|---|---|
| `Esc` | Up one level, or deselect at the root; closes the class card |
| `↑` / `↓` / `←` / `→` | Pan |
| Scroll / Shift+scroll | Pan vertically / horizontally |
| `Ctrl+` / `Ctrl+=` | Zoom in 10% |
| `Ctrl-` | Zoom out 10% |
| `Ctrl+0` | Zoom 100% |
| `F` | Fit the whole diagram |
| `R` | Reload the EDN immediately |

The status bar in the lower-left reports the current **box count, arrow
count, and zoom**. It is the quickest way to check that a regenerated
diagram changed shape as expected.

## Verify the picture against the source

The fixture examples are small enough to check by eye. The counts below
come from the live scene and the source in this checkout.

### C++ (`examples/cpp-fixture`)

`app::Controller : public model::Node` and
`Controller::resolve(const store::Node&)` are the only relations.

- Root: **3 boxes** (`App`, `Model`, `Store`) and **2 arrows**
  (`App → Model`, `App → Store`). These are namespace-level boxes, so the
  arrows are dependencies.
- Drill into `App`: **1 box** with `Controller`, three signatures
  (`run(int)`, `run(double)`, `resolve(const store::Node&)`), and ports to
  `Store` and `Model`.
- The two `run` overloads remain distinct; their line numbers are in the
  class card.

### Java (`examples/java.policy.edn`)

- Root: **5 boxes** (`App`, `Store`, `Model`, `Api`, and the `java.util`
  oval) and **7 arrows**.
- Drill into `Model`: `Animal` with `«abstract»`, `Dog` with
  `speak`/`name`, and one hollow-triangle inheritance arrow
  `Dog → Animal`.
- The ports on `Animal`/`Dog` are the outside classes that use them:
  `App`, `Store`, `Api`.
- `Dog` implements `Named`; at the `Model` level that is a port to `Api`.
  The class card names the exact pair.

### Figueira (real Python project)

The generated scene reports **18 boxes** and **46 arrows**. Spot checks:

- `Cli` lists 14 public functions from `src/figueira/cli.py`
  (`cmd_report` … `main`).
- `Use_cases` lists `run_sources_check`, `collect_doctor`, `run_doctor`,
  `run_detalha`, `ReportOutcome`, `run_report`.
- `Models` lists the public functions and classes from
  `src/figueira/models.py`.
- `__main__ → Cli` is the only entry edge; the rest are dependencies or
  implements edges.

These counts are visible in the status bar. If a language scanner or the
policy changes, the count changes; that is the first warning that the
diagram no longer matches the checkout.

## Proposals and what-if

Right-click the background of a proposal row to rename/delete it.
A proposal is a set of layers and omissions; it does not change the IR.
Use it to test an architecture before editing namespaces. The banner and
the row make it clear that the diagram is **not instantiated in code**.

## Troubleshooting

- **Window is blank / “Waiting for agent”** — in standalone mode use an
  EDN path; in companion mode the agent must send `:display`. Press `R` to
  load the configured EDN immediately.
- **Everything is red** — metrics are missing, stale, or partial. The
  legend says “weak or unmeasured”; run the language's measurement tool
  and check `.metrics/manifest.edn`.
- **Diagram is off-screen** — press `F`. `Ctrl+0` returns to 100%, and
  scroll/drag pans.
- **Text is tiny** — `F` fits a large diagram; zoom with `Ctrl+` or use
  the class cards. A fit view is meant for structure, not for reading
  every member.
- **Screenshot mode hangs** — the first frame is drawn before the fit is
  applied; the current build waits for the fitted frame. If you run an
  older build, remove `.uml-viewer/session.edn` and retry.
- **No display** — the desktop viewer needs Java + Clojure + a graphical
  session. The specs run under `xvfb-run`.

## Where to change it

| Area | File |
|---|---|
| Layout, sidebar rectangles, Fit button | `src/uml_viewer/engine/layout.clj` |
| Canvas, legend, status bar | `src/uml_viewer/adapters/draw.clj` |
| Clicks, keys, fit camera | `src/uml_viewer/application/events.clj` |
| Loading, session, reload | `src/uml_viewer/application/document.clj` |
| Window, screenshot, agent bridge | `src/uml_viewer/adapters/sketch.clj` |
| Language scanners | `src/uml_viewer/*_language/` |

Keep the shared layout and drawing code language-neutral; a scanner may
provide facts, but the viewer decides how to draw them.
