# UML viewer
## Abstract

This project displays a project as UML. The UML is *dynamic*. You can click
elements to drill down — all the way to source code. You can also play
what-if games by proposing architecture changes.

The app is coupled to an agent assistant. The two communicate, so you can
display the UML, drill down, tell the agent what you don't like, and watch
it make the changes. Describe a proposal and the agent will build a new
diagram. Keep revising that proposal until you like it, then tell the
agent to make the code match.

Color coding comes from CRAP and mutation metrics. You can adjust the
thresholds in config if you like.

The viewer and agent treat namespaces as components. Modules inside those
namespaces are the components' elements. Nesting can be arbitrarily deep.
You build an architecture by moving modules into the right namespaces with
the right dependencies.

You choose architectural levels by telling the agent what coupling and
cohesion criteria to use. For example, I tell my agent to group modules
into components by the Common Closure Principle.

Dependencies that point from a higher level to a lower level are colored
red, in accordance with the Dependency Rule of Clean Architecture.

The bottom line: you can view the structure of your code, see how well
tests cover it, play what-if games, and manipulate the system from a high
level.

**New to the window?** Read [docs/USER-GUIDE.md](docs/USER-GUIDE.md) for
what every box and arrow means, then keep
[docs/CHEATSHEET.md](docs/CHEATSHEET.md) open while clicking. The guide
also records the fixture counts used to check that a diagram still matches
its source.

## Tech

A live Quil app that lays out and draws UML from an EDN IR, plus an optional
browser renderer (`--web`) with no npm build step: the JVM serves SVG and
pushes frames over SSE, and a vanilla JS page sends clicks and keys back. A
**policy** plus a language-specific parser write the topology; this tool
displays it, routes the arrows, colors CRAP, and lets you click.

The IR is **topology**: the namespace tree, classes, and edges. **Metrics**
(CC, coverage, CRAP, killed/survived/uncovered) come from `.metrics/`
snapshots produced by [crap4clj](https://github.com/unclebob/crap4clj) and
[clj-mutate](https://github.com/unclebob/clj-mutate). The viewer overlays
those files at load, keyed by namespace and function name. Agents edit the
policy, not the IR. See [Policy](#policy).

## Python support

The Python adapter uses the same desktop viewer, policy format, and language
interfaces. It reads source without importing the examined package.

From this checkout, install Python 3.9+ support and generate the small example:

```sh
python3 -m pip install -e .
clojure -M:ir examples/python.policy.edn
clojure -M:run --standalone target/python.edn
```

The desktop needs Java, Clojure CLI, and a graphical display. `--standalone`
opens the native UI without launching or controlling a companion agent.
Set `UML_PYTHON` if the viewer should use a particular Python interpreter.
The upstream `get-uml-viewer` installer defaults to the Clojure repository.
For Python, set `UML_VIEWER_REPO_URL` to this fork's Git URL when installing,
install the Python package from that checkout, and use `--standalone` with
your generated diagram.
To examine your code, copy `examples/python.policy.edn`, then change `:src`,
`:prefix`, and `:out`. Policy paths are relative to the working directory.

Python modules, classes, methods, fields, imports, and inheritance appear in
the diagram. Clicking a member opens its source. Regen reparses the policy's
source package. Static dependencies do not prove runtime wiring or correctness.
Computed imports and bases are reported as warnings when they cannot be resolved.

### Local agent commands

Codex, Claude, and other terminal agents can use the installed JSON interface:

```sh
uml-python inspect --root examples/python/library --prefix library
uml-python scan --root examples/python/library --prefix library
uml-python source --root examples/python/library --prefix library \
  --namespace library.book.Book --name borrow
uml-python refresh --policy examples/python.policy.edn
```

`inspect`, `scan`, `source`, and `metrics` are read-only. `refresh` invokes the
same Clojure generator as the native Regen button. Set `UML_CLOJURE` to select
a Clojure executable. No command calls an external AI service. Repository
instructions for agents are in [AGENTS.md](AGENTS.md).

Right-click actions and diagram selections also write the upstream mailbox,
`.uml-viewer/to-agent.edn`. In standalone mode they do not wake Grok or launch
an agent. Ask your local agent to handle its `:queue` oldest first and remove
each handled entry. Preserve `:next-id` and other queued entries when writing
the envelope. See [Companion mailbox](#companion-mailbox) for the format.
For Python quality requests, use the measurement command below, not the
Clojure quality aliases. It measures the configured source package; it does
not currently limit a campaign to the clicked class. `:omit` changes the
policy or proposal, then regenerates the diagram. `:context` selects the
diagram being discussed. Regen itself runs locally in standalone mode.

### Python quality measurements

Install the optional tools into an environment that can run the examined
project's tests. This example runs two small tests and at most three mutations:

```sh
python3 -m pip install -e '.[quality]'
python3 -m uml_viewer_python.measure \
  --project examples/python --source library --prefix library \
  --tests tests --work target/python-quality --output target/.metrics \
  --mutation-limit 3
uml-python metrics --root examples/python/library --metrics target/.metrics
```

Tests and mutations run in a copied checkout. The baseline must pass before
scores are published. Use one campaign per work and output directory. A new
campaign needs a new work directory; `--resume` continues unchanged inputs.
`--mutation-limit 0` runs all candidates from the supported operators.

Coverage is Python statement coverage. It excludes native libraries and child
Python processes. Complexity comes from Radon. CRAP is
`complexity² × (1 - coverage_fraction)³ + complexity`. Class coverage is weighted
by executable statements. Mutation operators invert comparisons and booleans,
swap addition/subtraction, and replace multiplication with floor division.
These limited operators measure test sensitivity, not software correctness.
Timeouts, errors, uncovered sites, and pending candidates remain explicit.

The viewer finds `.metrics` next to the generated diagram or in a parent
folder. Verified snapshots carry input hashes. Changed or missing inputs
invalidate them; missing and partial Python results stay unscored. Rerun after
adding tests or changing dependencies. Nested function lines are included in
the enclosing function's coverage range. Classes without written methods have
no method score.

Generated files can contain resolved source paths for local navigation.
Keep them in ignored `target/`, not in commits. No examined project, dependency
cache, screenshots, or machine-specific launcher belongs in this repository.

### Development and upstream updates

- `src/uml_viewer_python/` owns Python parsing, commands, and measurements.
- `src/uml_viewer/python_language/` implements the existing Clojure language
  interfaces. Main selects the implementation; the layout engine stays generic.
- `spec/uml_viewer_python/` holds portable Python tests.
- `spec/uml_viewer/python_language/` checks the language boundary in the native
  viewer. Shared changes also run against the original viewer specifications.
- `examples/python/` is the small self-contained demonstration.

```sh
python3 -m unittest discover -s spec/uml_viewer_python -v
clojure -M:spec
```

The window specifications need a display. On Linux without a desktop, use
`xvfb-run -a clojure -M:spec`.

Keep Python-specific behavior behind these interfaces. Do not copy whole
upstream viewer files to update the integration. When incorporating an upstream
release, preserve its viewer behavior, reconcile the small shared integration
changes, then run both test commands and regenerate the example. The original
Clojure workflow remains available below.

## Run

Needs Clojure CLI and Java 21+.

From **this repo**:

```bash
clj -M:ir                            # policy → examples/uml-viewer.edn
clj -M:run
clj -M:run examples/library.edn
clj -M:run examples/uml-viewer.edn
clj -M:run --standalone target/java.edn
clj -M:run --screenshot=/tmp/view.png target/java.edn
clj -M:web --standalone target/java.edn             # browser viewer
clj -M:run --web --standalone target/java.edn       # same, without the alias
clj -M:run --web --standalone --host 0.0.0.0 target/java.edn  # phone on the LAN
clj -M:run --help
```

The canvas fits the whole diagram on load and after every drill,
proposal switch, and declutter change. Press **F** (or the Inspector's
**Fit (F)** button) to fit it again; `Ctrl+0` returns to 100%. The
lower-left status bar reports the box count, arrow count, and zoom.
`--screenshot` saves one fitted frame to a PNG and exits without starting
an agent; use it for design review and keep the file out of the repository.
The full control reference is [docs/CHEATSHEET.md](docs/CHEATSHEET.md).

`--web` serves the same diagram as SVG at a fixed per-project port in
20000–29999 (written to `.uml-viewer/web.edn`). It binds `127.0.0.1` by
default; `--host 0.0.0.0` exposes it to the LAN for a phone or tablet, and
`--no-open` skips the browser. A narrow window drops the inspector and keeps
the **←** and **Fit** buttons, so boxes stay readable on a phone. The page
has no authentication: expose it only on a trusted network. See
[docs/web-viewer.md](docs/web-viewer.md).

From **any Clojure project** you want to view (no local uml-viewer checkout
needed):

```bash
cd /path/to/the-project
curl -fsSL https://raw.githubusercontent.com/unclebob/uml-viewer/master/scripts/get-uml-viewer -o get-uml-viewer
chmod +x get-uml-viewer
./get-uml-viewer               # fetch, write ./uml, start
./uml                          # later fresh starts (returns immediately)
./uml --restart                # companion only: new JVM, restore last view
```

`scripts/get-uml-viewer` is in this repo. It clones uml-viewer into
**gitignored** `.uml-viewer/uml-viewer/` (a nested `.git` there is invisible
to the project's repo), writes `./uml`, and starts the viewer. `--install-only`
skips the start. `UML_VIEWER_REPO_URL` and `UML_VIEWER_REF` override the clone
source (default `master`).

`./uml` returns immediately (`clojure` in the background, not `clj`/`rlwrap`).
The diagram is its own JVM. Exceptions and JVM output append to
**`uml-viewer-log.txt`** in the project directory (gitignored). `clj -M:run`
from a terminal also returns immediately: the viewer detaches itself from
the launcher's session (`setsid` when available).

A **tmux** session unique to the examined project starts interactive Grok in
that directory (`--yolo --trust --rules …` plus a launch prompt). The name is
`uml-viewer-<project>-<hash>`, stored in `.uml-viewer/companion.edn`. On start
it writes a hierarchical policy from that project's namespaces and regenerates
the IR. Type there; Esc is the real TUI interrupt. Closing the diagram kills
**only that** tmux session and its Terminal window — other Grok agents stay
up. If that Grok process dies, tmux respawns it in the same pane. That
instance also runs `clj -M:crap`, `clj -M:mutate`, and IR generate after later
changes. Project-wide rules live in `.grok/rules/uml-viewer.md`.

Prefer `./uml` in the examined project (from `get-uml-viewer`). Aliases
`:uml-viewer` / `:uml-viewer-restart` still work if present.

| Command | Who | What |
|---------|-----|------|
| `./uml` | anyone | Fresh window in a background JVM. Starts the companion. Waits for `:display`. |
| `./uml --restart` | **associated agent only** | New JVM, same companion. Restores the last view. |

Do **not** pass `--restart` unless you are that companion recycling the
window after source changes. A stray `--restart` skips spawning Grok and
leaves a diagram with no agent. The companion recycles the window by
writing `:quit-for-restart` to `.uml-viewer/to-viewer.edn`, waiting for
the JVM to exit, then `./uml --restart`. The new JVM restores depth, pan,
zoom, and which proposal was showing (`.uml-viewer/session.edn`). Do not
SIGKILL. Closing the window kills only that project's companion, not other
Grok agents.

On a fresh start the canvas stays blank until the companion sends `:display`,
with **Waiting for agent to create diagram.** `R` reloads the current EDN
immediately and does not wait. A missing or unreadable file prints
`UML viewer: file not found: …` in the inspector instead of throwing.

```bash
clj -M:spec
clj -M:cov
clj -M:ir                            # writes examples/uml-viewer.edn
clj -M:crap                          # writes .metrics/crap.edn
clj -M:mutate src/uml_viewer/engine/layout.clj
```

This project's `:crap` alias uses `../clojure/crap4clj`. `:mutate` pins
[clj-mutate](https://github.com/unclebob/clj-mutate) by git SHA. Commit
`.metrics/` so a clone has numbers without re-running those tools.

Rename or move of a function is a new form: overlay does not match old names.

## Navigation

A control-by-control list of every click and what to expect:
[docs/CHEATSHEET.md](docs/CHEATSHEET.md).

**Layer** and **component** mean the same thing: a namespace grouping
(the first segment after the prefix, or a named proposal group).

- First view: **namespace components** (layers). Dependencies between them
  collapse to one arrow. Each component lists nested namespaces.
- Double-click a component to open the next level. Esc or the ← label goes
  up a level, including after drilling a proposal group. Esc does not quit.
  The window close box or a `:quit-for-restart` mail message exits the app.
- Hover an arrow for a popup of every `from -> to` it bundles, in any
  declutter mode. Violating pairs are red.
- Right-click a class or component for **Refresh CRAP**, **Refresh
  Mutation**, **Refresh All Mutation**, or **Omit**. Each writes
  `to-agent.edn` and wakes the companion. CRAP runs on that class or the
  files under that component. Mutation is differential `clj -M:mutate` on
  those src files; All Mutation passes `--mutate-all`. Omit adds the id to
  the current proposal's `:omit`, or to policy `:omit` on the real diagram.
- Component titles paint **on top of** crossing arrows.
- The inspector lists the **real diagram** (the namespace tree) above
  **Proposals**. Click the real row to return to the tree; click a proposal
  to show it (marked as not in the code). Either click writes `:context`
  so the companion treats that diagram as the discussion. **New Proposal**
  adds a timestamp-named proposal. Right-click to rename or delete.
  **Declutter** cycles Declutter arrows / Remove arrows / Declutter
  elements / Declutter classes / Declutter none. **Remove arrows** hides
  the lines and puts a triangle on the top (incoming) and bottom
  (outgoing) of each box, **including nested classes** as well as their
  packages. A triangle is red if any bundled pair is violating. Hover it
  for the `from -> to` list.
- Double-click a leaf module for its **class card**.
- The class card names the **module** (`:ns`). Click it to open that source
  file at the top. Hover a member to highlight it; click it to open the same
  file at the defn. See [Source extractors](#source-extractors).
- Methods on the card are `+` public and `-` private. `defn-` is not drawn on
  the class box.
- Abstract classes show a white **α** in the upper-right; interfaces a white
  **I**. Names of rectangles that are not classes (components/layers,
  interfaces, enumerations, package banners) are italic. Foreign libraries
  listed in policy are ovals outside the components.
- The main window is resizable.
- Scroll to pan vertically; Shift-scroll (or left/right arrows) for
  horizontal. Pan can follow arrows that bow past the origin.
- **Ctrl+** (or **Ctrl+=**) zooms in 10%; **Ctrl-** zooms out 10%;
  **Ctrl+0** restores 100%. Zoom keeps the view center still.
- **Regen** in the inspector asks the companion to rewrite policy and IR
  (see [Companion mailbox](#companion-mailbox)).
- `R` reloads the current EDN. The watcher also reloads on save, and when
  `.metrics/` snapshots change. An open class card updates with the new
  numbers.
- `Esc` on the class card closes it (it does not quit the viewer). Closing
  the main window exits the app.

## Policy

This project's diagram is **generated**. Do not edit `examples/uml-viewer.edn`.
Edit `examples/uml-viewer.policy.edn`, then run `clj -M:ir` (or press Regen).

The **parser** (`LanguageGraph`) reads source and emits facts. Clojure is
one class per namespace. A `:require` or `:use` of another project
namespace is a `:dependency`. `requiring-resolve` of a quoted var is a
`:dependency` on that var's namespace. `defprotocol` is
`:stereotype :interface`. `defrecord` or `deftype` of a protocol is
`:implements`. External `:require`s and `:import`s become **foreign**
classes. The overlay fills Clojure members from `.metrics/`. TypeScript,
Rust, Python, and Java are separate scanners (see
[Language graphs](#language-graphs)): one class per module or source file,
with exported members as `:ops`. The overlay joins each snapshot by `:ns`,
then by the class id.

### Do not invent layers (components)

The tree **is** the namespaces. After `:prefix`, every `.` is a nesting
level. `uml-viewer.engine.layout` is a child of `engine`.
`uml-viewer.clojure-language.source-clojure` is a child of `clojure-language`.
The policy does **not** assign nses to invented packages. If you want Domain /
Engine / Adapters boxes **in the source tree**, those segments must exist as
namespaces. To **view** a grouping that is not in the code, use `:proposals`
(see [Proposed components](#proposed-components)) — do not rewrite namespaces.

To write a policy for a project:

1. Set `:prefix` to the namespace prefix (`foo` for `foo.bar.baz`). A
   single language also sets `:src` (default `src`) and `:lang` when the
   language is not Clojure. More than one language sets `:sources`
   instead of `:src`.
2. Set `:hierarchical true` (or omit `:packages` and `:diagrams`).
3. List top-level **segments** in `:order` — the first dotted part after
   the prefix, in the order you want the boxes. Do not invent names.
4. List real libraries in `:foreign` if they should appear as ovals.
5. Optionally override a require with `:edge-kinds {[:from :to] :association}`
   using the **leaf** ids (`clojure-language.source-clojure`, not
   `clojure-language`).
6. Set `:levels` so the generator can mark dependency-rule violations
   (see [Dependency rule](#dependency-rule)).
7. Optionally set `:proposals` to name design components that are not namespaces
   (see [Proposed components](#proposed-components)).
8. Run `clj -M:ir` (or Regen).

If `foo.bar` and `foo.bar.baz` both exist, the `bar` box lists `bar` (the
module) and `baz` (the child). Module titles are the last namespace
segment (`layout`, not `engine.layout`). A hyphenated segment capitalizes
each part (`source-clojure` → `SourceClojure`). A segment that already
contains a capital keeps that spelling and capitalizes the first letter
(`tauriFs` → `TauriFs`). Double-click the component to open that level;
double-click the `bar` module line for its class card.

Wrong (invented partitions):

```edn
:packages [{:id :domain :nses [ir geom source]}
           {:id :engine :nses [layout route]}]
```

Right (the ns tree):

```edn
{:title "UML viewer"
 :src "src"
 :prefix "uml-viewer"
 :lang :clojure
 :out "examples/uml-viewer.edn"
 :hierarchical true
 :foreign [quil]
 :order [main adapters application engine source graph
         clojure-language typescript-language rust-language python-language
         domain]
 :levels [[domain source graph clojure-language typescript-language
           rust-language python-language]
          [engine]
          [application]
          [adapters]
          [main]]
 :edge-kinds {[:engine.compose :engine.layout] :association}}
```

A project with more than one language lists each tree under `:sources`.
The policy `:prefix` is stripped from ids. A source `:prefix` is that
tree's namespace root. `src/model.ts` becomes `bookwriter.model`
(`:model`). `src-tauri/src/lib.rs` becomes `bookwriter.rust` (`:rust`).
`main.rs` becomes `bookwriter.rust.main` (`:rust.main`).

```edn
{:title "Bookwriter"
 :prefix "bookwriter"
 :sources [{:lang :typescript :root "src"}
           {:lang :rust :root "src-tauri/src" :prefix "bookwriter.rust"}]
 :foreign [at.tauri-apps markdown-it highlight.js node tauri serde std]
 :hierarchical true}
```

| Key | Role |
|-----|------|
| `:prefix` | Strip this from each ns; remaining dots are the tree |
| `:hierarchical` | Namespace tree (default when `:packages` is omitted) |
| `:order` | Order of **existing** top-level ns segments, not new component names |
| `:levels` | Groups of those segments, **inner (higher-level) first**. Same group = same rank |
| `:forbidden-pairs` | Directed bans: `[{:from id-or-ids :to id-or-ids}]` — a dependency from any source to any target is violating regardless of rank |
| `:independent` | Mutual bans: `[[id-or-ids ...] ...]` — any dependency between two ids of the same group, either direction, is violating |
| `:proposals` | Named groupings of real segments; **not** instantiated in source. Inspector **Real diagram** row returns to the ns tree |
| `:omit` | On a proposal or the policy: nses (and their children) left off the diagram |
| `:edge-kinds` | Override parser kind for `[from to]` (usually `:association`) |
| `:omit-edges` | Drop `[from to]` |
| `:lang` | Scanner when `:sources` is absent (default `:clojure`) |
| `:src` | Source root when `:sources` is absent |
| `:sources` | One scan per `{:lang :root :prefix?}`. The entry `:prefix` is that tree's namespace root |
| `:foreign` | External libs as ovals. A listed prefix collapses `quil.core` to `quil`. Unlisted externals are dropped |

**Viewer Grok loop** (passed with `--rules` to the companion session only)

On launch: from the examined directory, write or update the hierarchical
policy and regenerate the IR, then wait.

After **every** later source or policy change, refresh metrics and
regenerate the IR.

For Clojure that is `clj -M:crap`, then `clj -M:mutate` on the changed
`src/` files, then `clj -M:ir`. Uncovered mutants that remain are coverage
gaps. Keep the snapshot. Do not re-run the file or force a full mutation
because mutate exited non-zero.

For another language, run that language's CRAP and mutation tools when
they exist. The overlay joins each snapshot by `:ns`, then by the class
id. Missing CRAP or mutation data is red. Then regenerate the IR.

- Add/rename/delete a namespace: the tree updates on `clj -M:ir`. Put a new
  top-level **segment** in `:order` if you care about box order.
- Nested nses appear as contents of the parent component.
- “This require is really an association”: one `:edge-kinds` entry.
- Show a library like quil as an oval: add it to `:foreign`.
- Do not add `:packages` to fake Clean Architecture components. Use
  `:proposals` to view a grouping that is not in the code.
- Preserve `:proposals` when rewriting policy. Do not invent them on launch.
  If instructed, add a named proposal (default name is a timestamp).

Hand-written sample IRs (e.g. `examples/library.edn`) are still valid; they
are not generated.

### Dependency rule

A `:dependency` edge is **violating** when it runs from a **higher-level**
(inner) component to a **lower-level** (outer) one. That is the Clean
Architecture dependency rule: source-code dependencies point inward.

Evaluation is deterministic given `:levels`:

1. Take the first dotted segment of each end (`engine.layout` → `engine`).
2. Look up that segment in `:levels`. Rank is the group's index; **smaller
   is inner / higher-level**.
3. If both ends have a rank and `from-rank < to-rank`, the edge is
   `:violating true`. Same rank is allowed. `:implements` and
   `:association` are never violating. Foreign / unranked ends are not
   compared.
4. Collapsed component arrows keep the flag if any bundled leaf dependency
   was violating. Remapping a pair to `:association` clears it.

`:order` is visual box order, not rank. Nesting is not layering: you cannot
infer inner vs outer from the namespace tree alone, so `:levels` must group
segments that sit at the same architectural level (e.g. `domain`, `source`,
and `graph`). Omit `:levels` and nothing is marked. If `:levels` is omitted
and `:proposals` is set, rank follows the first proposal's component order.

### Explicit forbidden and independent pairs

The rank model sees only the ascent of a single 1-D order: a ban that runs
*down* the ranks, or between two same-rank siblings, is invisible. A policy
(or one of its proposals) can declare those rules itself:

```clojure
:forbidden-pairs [{:from [store costs] :to [sources llm]}]
:independent     [[pipeline status] [store costs]]
```

- `:forbidden-pairs` is **directed**: any `:dependency` from a listed source
  to a listed target is violating no matter what the ranks say.
- `:independent` is **mutual**: any `:dependency` between two ids of the
  same group, in either direction, is violating.
- Each entry accepts one id or a vector of ids; ids match by dotted prefix
  (`store` covers `store.cache`). An edge whose two ends resolve to the
  **same** listed id (e.g. `store.cache -> store` under `store`) is that
  module's own wiring and never violates — the same convention `:levels`
  uses, where a nested ns inherits its top segment's rank.
- The generated IR carries both keys (policy level and per proposal), so the
  viewer re-evaluates them when you preview a proposal. `:association` and
  the other non-dependency kinds are never violating.

Use them when the real contract is not a single order — e.g. import-linter's
`forbidden` and `independence` contracts, or ArchUnit's slices
`notDependOnEachOther`, whose bans would otherwise need two complementary
views to be seen.

The paint is over **direct** edges. Import-linter's `forbidden` contract also
checks *indirect* import chains by default (`allow_indirect_imports` unset),
so a chain through a third module is the linter's catch, not the viewer's —
the linter remains the authority, and the two are complementary.

### Reading the counts from the shell

The loop this tool is built for is: regenerate, count, point at one arrow.
The generator prints the count and the list in the same run:

```bash
clj -M:ir uml-viewer.policy.edn target/uml-viewer.edn --report
```

After the usual `Wrote …` line it prints `Violations: N (ranks a, forbidden
b, independent c)`, one line per violating edge (`from -> to [rule]`), and
the project's mutual-import pairs (`a <-> b`) — module pairs that import
each other, which no single-direction rank rule can see. Exit code and the
written file are unchanged, so a post-push alias or CI step can regenerate
and read the list in one run.

### Proposed components

`:proposals` is a list of named groupings of **existing** top-level segments.
Those names are not namespaces. Each item is `{:id :name :layers [...] :omit [...]}`
plus the optional `:forbidden-pairs` and `:independent` rules above
(`:layers` here are named components; `:omit` keeps listed nses off
**Unassigned**). The as-is diagram stays the ns tree.
The inspector lists the real diagram (the namespace tree) just above
**Proposals**; click it to return to the tree. Click a proposal to show it
(canvas marked **PROPOSAL — not instantiated in code**). Either click
tells the companion that diagram is the context of discussion. **New
Proposal** adds an empty proposal named with a timestamp. Right-click a
name to rename or delete it. Double-click a ns box to drill; **←** at the
top returns to the proposal.

The **Declutter** button cycles **Declutter arrows** (one arrow per
component pair per direction) → **Remove arrows** (triangles on each box
and on nested classes instead of lines; hover lists deps; red if any pair
is violating) →
**Declutter elements** (also hide nested names, members, and ports) →
**Declutter classes** (also hide classes inside components) →
**Declutter none**.

When a proposal is shown, rank follows **that** proposal's component order
and violating arrows are re-evaluated. The real diagram uses `:levels`.
A component inherits the **max** level of its elements.
Class boxes show the current view's rank (innermost **0**)
at the upper left; the class card repeats **Level n**. Level 0 is drawn at
the **bottom**. Good arrows (outer → inner) point down; violating arrows
(inner → outer) point up and stay red. When arrows are collapsed,
selecting a class highlights the component arrows it belongs to.
Collapsed components keep their color
and C/M dots; double-click still opens a component.

```edn
:proposals [{:id :ccp
             :name "2026-09-18 10:30:00"
             :layers [{:id :playfield :label "Playfield"
                       :nses [entities world missiles cities batteries flyers]}
                      {:id :hosts :label "Hosts" :nses [jvm browser]}
                      {:id :jvm :label "JVM"
                       :nses [jvm.cli jvm.main
                              {:id :quil-swing :label "Quil/Swing"
                               :nses [jvm.sketch jvm.window]}]}]
             :omit [cli]}]
```

Companion Grok must not invent `:proposals` on launch and must keep them when
updating `:order`. If instructed, add a named proposal and regenerate the IR.

The viewer draws a violating arrow **red**, and **bold red** when a selected
element highlights it. Hand-written IR may set `:violating true` directly.

## Companion mailbox

The viewer and the companion Grok talk through `.uml-viewer/` in the examined
project (gitignored). The file is the mail; tmux is only a doorbell.

| File | Direction |
|------|-----------|
| `.uml-viewer/to-viewer.edn` | Grok → viewer |
| `.uml-viewer/to-agent.edn` | viewer → Grok |

Each mailbox file is a small queue `{:next-id n :queue [cmd …]}` (tmp-then-rename).
Commands have a rising `:id`. Append; do not overwrite. Handling a command
**pops** it from `:queue` and rewrites the file (oldest first). The viewer
does this itself on `to-viewer.edn`. The companion must pop each
`to-agent.edn` command as it handles it.

`.uml-viewer/session.edn` is the last view (depth, pan, zoom, proposal) written
on `:quit-for-restart` and restored by `--restart`. `.uml-viewer/companion.edn`
records this viewer's tmux session and Terminal window id so close/kill never
touches another project's agent.

| `:op` | Meaning |
|-------|---------|
| `:display` | Viewer loads `:path` (relative to the project root) |
| `:regen` | Grok rewrites hierarchical policy, regenerates IR, then `:display` |
| `:quit-for-restart` | Viewer exits the JVM without killing Grok. The associated agent then runs `clj -M:uml-viewer-restart`. |
| `:refresh-crap` | Run CRAP on `:target` (class or component), then IR |
| `:refresh-mutate` | Differential mutate `:target`'s src files, then IR |
| `:refresh-mutate-all` | `clj -M:mutate --mutate-all` on `:target`'s src files, then IR |
| `:omit` | Add `:target` `:id` to proposal or policy `:omit`, then IR |
| `:context` | Inspector selection is the discussion context: `{:context :real}` or `{:context :proposal :proposal-id id :name "…"}` |

Clicking **Real diagram** or a proposal (including **New Proposal**) writes
`:context` and wakes Grok. Stay on that architecture until the next
`:context`.

Right-click ops include `:target {:id :ns :kind :class|:component :proposal-id?}`.
`:kind` is `:component` for a layer box (and its nested nses) and `:class`
for a module.

**Regen** in the inspector queues `:regen` and wakes Grok with literal text, a
150ms pause, `C-m`, 50ms, then `C-j` (same timing as SwarmForge). The wake-up
does not contain the command. If Grok is busy, it finishes first, then reads
the mailbox. If tmux is missing, the button still writes the file and the
inspector says the session is not attached.

## Language graphs

Generating the IR asks `uml-viewer.graph` to scan a source tree. `:lang`
selects the scanner (default `:clojure`). `:sources` scans each root and
merges them. Register another implementation with
`(graph/register! :my-lang my-scanner)`. The scanner must satisfy
`LanguageGraph`:

| method | role |
|--------|------|
| `scan` | From a root and `{:prefix :ns-prefix?}`, return `{:classes :edges}` |

`:commands` and `:invokes` are optional. `merge-scans` turns a matching
command name into a `:dependency` and does not copy those keys onto a
class.

Classes are `{:id :name :ns :stereotype :lang :file}`. Edges are
`{:from :to :kind}` (`:dependency`, `:inheritance`, `:implements`,
`:association`, `:aggregation`, or `:composition`). The policy layer is
language-neutral. Do not special-case a language in `policy`. Main
requires each scanner so it registers; `ir-generator` looks the scanner
up.

**Clojure** (`uml-viewer.clojure-language.graph-clojure`) reads `ns` forms
(including prefix lists), `requiring-resolve` of a quoted var (including
nested calls), `defprotocol`, `defrecord`, and `deftype`. It does not emit
`:ops`. Project classes carry `:lang :clojure` and `:file`.

**TypeScript** (`uml-viewer.typescript-language.graph-typescript`) emits
one class per module file (`.ts`, `.tsx`, `.js`, `.jsx`, `.mjs`, `.cjs`).
It skips `*.test.*`, `*.spec.*`, `*.d.ts`, and paths under `node_modules`,
`dist`, and `target`. A relative import is a dependency, and `import type`
counts. An asset import (CSS, images, JSON, and the like) is ignored. A
bare specifier is foreign: `@tauri-apps/api/core` becomes
`:at.tauri-apps.api.core`. A listed `:foreign` prefix such as
`at.tauri-apps` collapses that id. `export function f(): T` and
`export const x: T` are `:implements` when `T` is an imported project
name. `Promise<Book>` and `TreeNode[]` are not. A module that exports an
interface and no values is `:stereotype :interface`. Exported functions,
constants, classes, and enums are `:ops`. `invoke("name")` is recorded
for the cross-language link.

**Rust** (`uml-viewer.rust-language.graph-rust`) walks the modules
reachable from `lib.rs` and `main.rs` through `mod`. The `[lib] name` in
`Cargo.toml` is the crate name, so `bookwriter_lib::run()` in `main.rs`
depends on the lib class. Every Rust class is named by its source
file. The directory box keeps the namespace segment. `pub fn` functions
are `:ops`. `pub(crate)` functions are not,
unless they are `#[tauri::command]` functions, which are `:ops` even when
private. `impl Trait for Type` is `:implements` when the trait resolves
to a project module. The first segment of a `use`, other than `crate`,
`self`, `super`, or the lib crate, is a foreign crate.

**Python** (`uml-viewer.python-language.graph-python`) emits one class per
`.py` module. `__init__.py` is the package. Point `:src` at the package
directory or at its parent. A path that already begins with the
prefix stays as written, so `src/tools/parse.py` and
`src/tools/__init__.py` become `tools.parse` (`:parse`) and `tools`
(`:tools`). It skips `test_*.py`, `*_test.py`, `*_tests.py`,
`conftest.py`, and paths under `__pycache__`, `tests`, virtualenvs,
`dist`, and `build`. `import` and
`from ... import` are dependencies, including an import nested in a
function. A relative import (`from . import`, `from ..mod import`) stays
inside the package. A name that is not a project module is foreign
(`os.path` stays `os.path`, and a listed `:foreign` prefix such as `os`
collapses it). `class Dog(Animal)` is `:inheritance` when `Animal` is a
project module. A base of `Protocol` or `ABC` is `:implements`.
`def load() -> Animal` is `:implements` when `Animal` is a bare imported
project name. `list[Animal]` is not. Public module-level functions and
classes are `:ops`. A module whose public classes are only `Protocol` or
`ABC` bases, with no public functions, is `:stereotype :interface`.

`merge-scans` links a TypeScript `invoke("read_text")` to the Rust class
that owns `#[tauri::command] fn read_text`, as a `:dependency`. Two
project classes with the same id are an error. When a dependency and an
`:implements` edge join the same pair, the IR keeps `:implements`.

**Go** (`uml-viewer.go-language.graph-go`) emits one class per package
directory. The module path comes from `go.mod`; a project import is a
dependency, a standard-library import is ignored, and another module is
foreign (named by its repository, so `github.com/jackc/pgx/v5/pgxpool`
becomes `jackc.pgx`). Exported functions and methods are `:ops`; methods
are `Type.Method`. `vendor`, `testdata`, and `_test.go` files are skipped.

**Kotlin** (`uml-viewer.kotlin-language.graph-kotlin`) emits one class per
`class`, `interface`, `object`, or `enum`, plus a `<File>Kt` class for
top-level functions. `:ns` is the name JaCoCo reports, so
`demo.Shape.Companion` and `demo.GeometryKt` join directly. Test source
sets (`src/test`, `jvmTest`, `commonTest`, `testFixtures`, and the like)
are skipped.

**C#** (`uml-viewer.csharp-language.graph-csharp`) emits one class per
type. Namespaces are the tree, `using` and type references are
dependencies, and methods are `:ops` for the overlay. `bin`, `obj`,
`*.g.cs`, `*.Designer.cs`, and test projects are skipped.

**Ruby** (`uml-viewer.ruby-language.graph-ruby`) emits one class per `.rb`
or `.rake` file. Constants resolve the way Zeitwerk autoloads them, so a
Rails reference needs no `require`. A Rails root walks `app/*` (and
`concerns`) and `lib`. Grape HTTP endpoints and Rake tasks are members, so
they open at their definition. `_spec.rb` and `_test.rb` files are
skipped.

**Java** (`uml-viewer.java-language.graph-java`) emits one class per
`.java` file. The `package` declaration names it, so
`src/main/java/com/acme/model/Dog.java` in `package com.acme.model` becomes
`:model.Dog` under the prefix `com.acme`. Point `:src` at
`src/main/java` or at the project root. A `:sources` entry needs no
`:prefix`; the package already names each class. Nested types belong to
their file. It skips `*Test.java`, `*Tests.java`, `*IT.java`,
`package-info.java`, `module-info.java`, the test roots beside
`src/main` (`src/test`, `src/integrationTest`, `src/testFixtures`), and
paths under `target` and `build`. `extends` is `:inheritance`, for a
class or an interface; `implements` is `:implements`. An import of a
project type is a dependency, and so is a static import of its member.
Java needs no import inside a package, so a mention of a type from the
same package, or from a `.*` import, is a dependency too. A fully
qualified `com.acme.model.Animal` is a dependency. A single-type import
shadows the package, as in Java. An import that names no project type is
foreign (`java.util.List` stays `java.util.List`, and a listed
`:foreign` prefix such as `java.util` collapses it). Public methods of
the type named after the file are `:ops`. Interface and annotation
methods are public unless `private`. Constructors are not ops. An
interface or annotation is `:stereotype :interface`, an enum
`:enumeration`, and an abstract class `:abstract`.

**Haskell** (`uml-viewer.haskell-language.graph-haskell`) emits one class
per module and `import` dependencies. Module headers, indented/grouped/
operator signatures, tabs, standalone pragmas, and CPP lines are read
without running GHC. `dist-newstyle`, `.stack-work`, and `test` trees are
skipped. Two build units that both define `Main` need separate
`:sources` entries with distinct `:prefix` values.

**C++** has no source scanner in the viewer. A separate converter reads
the JSON class diagram produced by [clang-uml](https://clang-uml.github.io/)
and writes a hierarchical EDN document with namespaces, classes,
inheritance, dependency, association, and aggregation relationships,
overload identities, and exact source locations. See
[C++ support](#c-support).

CRAP and mutation for TypeScript, Rust, Python, Go, Java, Kotlin, and .NET
come from separate tools. The overlay joins a snapshot to the class whose
`:ns` equals that namespace. Otherwise the class id owns that name (`bookwriter.model`
owns `model`), a dotted child rolls up (`pdf` owns `pdf.Layout`), and
the policy prefix belongs to the single undotted Rust class. `::` is
read as `.`. A source file drawn inside its directory keeps its own
CRAP and mutation scores. The directory box pools the functions under
it, one weight per function. A file with no snapshot stays red, and in
that pool it counts as one red function per op, or one function when it
lists none. A class with no CRAP or mutation data is red.

**Python, deep scan** (`uml-viewer.python-language.graph-python-external`)
is opt-in: set `:lang :python-external`. It shells out to the
`uml_viewer_python` package (Python 3.9+, `pip install -e .[quality]`),
reports every member with complexity and exact source spans, and marks
the IR `:metrics-mode :verified`. Use it when you want the Python
measurement workflow; the built-in `:python` scanner needs no tool.


## C++ support

The C++ path converts [clang-uml](https://clang-uml.github.io/) JSON; it
does not parse C++ in the viewer. From a project with a `.clang-uml`
configuration:

```bash
clang-uml -c .clang-uml -n class_diagram -g json
clj -M:cpp-ir compile_commands.json.cxx.json diagram.edn /path/to/project
clj -M:run diagram.edn
```

`clj -M:cpp-ir <clang-uml.json> <out.edn> <source-root>` keeps qualified
class identities, distinct overloads, relationship labels/access, and
source file/line/column. Unsupported or unresolved entities are reported
in `:diagnostics`. The converter does not infer missing relationships. See
[docs/cpp-clang-uml.md](docs/cpp-clang-uml.md) for setup and limits.

Coverage is opt-in: build with `--coverage`, run `gcov --json-format`, then
turn those files into the overlay snapshot the viewer already reads:

```bash
clj -M:cpp-metrics --gcov target/coverage --source-root . \
  --out .metrics/crap.edn
clj -M:run diagram.edn
```

Rows carry `:class` and the function's source line, so overloads do not
collapse and metrics from one class do not leak into another. CRAP uses an
**estimated** cyclomatic complexity (decision keywords plus `&&`, `||`, and
`?:`) — useful for ranking, not a certified metric. See
[docs/cpp-clang-uml.md](docs/cpp-clang-uml.md#coverage-and-crap).

## Go metrics

`tools/go-metrics` turns a Go coverprofile and
[gremlins](https://github.com/go-gremlins/gremlins) reports into the same
`.metrics/crap.edn` and `.metrics/mutate/<namespace>.edn` snapshots the
overlay reads. It takes package names from the generated IR, so it agrees
with the Go scanner about what a package is. Methods are `Type.Method`;
unexported names stay private. A repeated name such as one `init` per file
becomes `init (file.go:12)` and opens that one.

```bash
go -C tools/go-metrics install .
clj -M:ir                         # writes :classes, including :ns
go test -coverpkg=./... -coverprofile=cover.out ./...
(cd internal/team && GOFLAGS=-count=1 gremlins unleash --timeout-coefficient 5 -o /tmp/team.json)
go-metrics -ir examples/shop.edn -cover cover.out -mutation internal/team=/tmp/team.json
```

Rows written by another language's tool are kept; go-metrics replaces only
its own rows (`:tool "go-metrics"`).

The Go scanner now also links interface implementations and cross-package
embedding: a package whose type has every method of a two-or-more-method interface
implements that package, and an embedded `other.Type` adds an inheritance
edge. Single-method interfaces are ignored so `Error`/`String` do not link
unrelated packages. The work follows `Conte777`'s fork, adapted to the
package-level class model.

## IR

A hierarchical policy writes one EDN document of all classes and edges
(`:hierarchical true`). The viewer builds each screen from the namespace tree
at the current drill level. A hand-written IR with `:packages` (or
`:diagrams`) is still a static diagram, e.g. `examples/library.edn`.

Metrics on the class card do not have to be authored. If `.metrics/` is
present, the overlay fills CC, coverage, CRAP, killed/survived/uncovered,
and any functions found in the snapshots (including privates). Authored
`:crap` / `:coverage` / `:ops` are the fallback when no snapshot exists.

The overlay joins a snapshot to the class whose `:ns` equals that
namespace. The generator writes `:ns` from the scanned ns. Hand-written
IR must set `:ns` the same way. When no class has that `:ns`, the class
id, a dotted child of that id, and the policy prefix on the single Rust
crate root are the fallbacks.

```edn
{:title "Lending library"
 :direction :tb
 :packages
 [{:id :domain
   :label "Domain"
   :classes
   [{:id :book
     :name "Book"
     :stereotype :class          ;; optional: :interface :enumeration :abstract
     :fields [{:name "isbn" :type "String"}]
     :ops [{:name "find" :args ["isbn"] :returns "Book"}]}]}
  {:id :app
   :label "Application"
   :classes
   [{:id :repo
     :name "CatalogRepo"
     :stereotype :interface
     :ops [{:name "get" :args ["isbn"] :returns "Book"}]}]}]
 :edges
 [{:from :sql-repo :to :repo :kind :implements}
  {:from :loan :to :book :kind :association :label "borrows"}]}
```

Optional authored metrics, used when snapshots are missing:

- `:crap` — a number (`μ`) or `{:mu :max :sigma}`
- `:coverage` — ratio 0–1 on a class or op
- `:cc`, `:killed`, `:survived`, `:uncovered`, `:private` on ops
- `:hide-members true` — compact box

Package and class **color** maps CRAP (`μ + σ`) and mutation score each onto
1–10 using `uml-viewer.domain.config` cutoffs, averages them, and paints a
0–10 red–green fill. Missing CRAP or mutation data counts as red (grade 1),
not unknown. A parent pools CRAP by function count: μ and σ of the
functions underneath, then the same grade. It pools mutation by summing
killed and survived. A child with no data tints that pool once per
function. It does not replace the parent. A **C** and **M** dot in
the upper-right show the two scores. The boxes no longer print μ / max / σ.

On the class card, a `Crap μ … max … σ …` line sits above the table (max is
the worst function in the namespace, not a sum). Column groups are labeled
`--crap--` (Crap, CC, Cov) and `--mutation--` (killed, survived, uncovered).
The class row shows average CRAP with a `μ` suffix and omits CC. Killed is
white. Survived and uncovered are green at 0 and red when nonzero. A row
with no mutation operators (`:sites` 0) shows `---no mutation sites---`.
A form with operators still shows killed/survived/uncovered, including zeros.

Edge `:kind` values:

| kind | line | head |
|------|------|------|
| `:inheritance` | solid grey | empty triangle |
| `:implements` | solid grey | empty triangle |
| `:association` | solid grey | open arrow |
| `:dependency` | solid grey | open arrow |
| `:aggregation` | solid grey | empty diamond |
| `:composition` | solid grey | filled diamond |

Layout follows Mermaid's three stages:

1. **Size** each class from its text (padding 12).
2. **Place** packages in document order; classes Sugiyama-ranked inside a
   package; ~40px spacing.
3. **Route** like Mermaid/ELK: ports on facing sides, orthogonal tracks in
   the rank gap, short same-rank connections through the stack gap (local U
   only when a sibling sits in the way), then stroke with D3 `curveBasis`
   cubics. Ordinary arrows are solid grey; violating dependencies are red
   (bold red when selected). Component titles draw after the arrows so
   names stay visible. Paths that pass the target and reverse are rejected.

## Source extractors

Clicking a member asks `uml-viewer.source` for the **whole file** and a
**start line**. The IR (and the class card) only supply an **identity
map**; a language-specific extractor turns that into
`{:title :file :body :line}`. The source window opens on that file and
scrolls to the member (highlighted). Clicking the module name opens the same
file at the top (`:line` omitted).

```clojure
(source/member-source {:lang :clojure
                       :ns "uml-viewer.engine.layout"
                       :name "layout"})
```

`:lang` selects the extractor (default `:clojure`). Register another
implementation with `(source/register! :java my-java-extractor)`. The
extractor must satisfy `LanguageSource`:

| method | role |
|--------|------|
| `locate` | path to the file that should contain the member |
| `extract` | slice that member out of the file text |
| `title` | window title |

**Clojure** (`uml-viewer.clojure-language.source-clojure`) maps `:ns` to
`src/...clj` (or `.cljc` / `.cljs`) and finds the top-level `(defn name …)` /
`(defn- name …)` so the window can jump to that line. It does not read
`:file`.

**TypeScript** (`uml-viewer.typescript-language.source-typescript`),
**Rust** (`uml-viewer.rust-language.source-rust`), and **Python**
(`uml-viewer.python-language.source-python`) open `:file` and find the
exported declaration, the `fn`, or the `def`. The class card passes
`:lang` and `:file` from the class. A class with no `:lang` still uses
the Clojure extractor that Main passes in. The protocol is the seam; do
not special-case languages in the class card.

`:python-external` uses the same `:file` lookup as `:python`, with the
span reported by the Python AST, so nested definitions stay exact.

Quil stays in `adapters.draw` and `adapters.sketch`. The rest of the engine
does not depend on Processing.
