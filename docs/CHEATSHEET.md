# Viewer cheatsheet

Every control in the desktop viewer, what it does, and what you should see
after clicking it. The canvas is on the left; the **Inspector** is the
sidebar on the right.

**Standalone vs companion.** With `--standalone` the viewer opens without an
agent. Agent actions (right-click ops, proposal context, and **Regen** in
companion mode) only append to `.uml-viewer/to-agent.edn`; nothing changes on
screen until an agent handles the queue and regenerates. **Regen itself runs
locally in standalone mode** and reloads the diagram without an agent.

## Canvas — left click

| You click… | What it does | What you should see |
|---|---|---|
| A component/layer box (once) | Selects it | Inspector shows the package card: label, CRAP line, mutation line, `N classes` |
| A component (double-click) | Drills into the next namespace level | The box is replaced by its nested boxes; a **←** label appears; `Esc` goes back |
| A leaf module/class box (double-click) | Opens the **class card** (separate window) | Module `:ns`, `Level n`, `Crap μ … max … σ …`, member table with `--crap--` (Crap, CC, Cov) and `--mutation--` (killed, survived, uncovered) columns |
| A member line on the class card | Opens the source file | Source window on that file, scrolled to and highlighting the `defn`/method |
| The module name at the top of the class card | Opens the source file | Same file, at the top (no line highlight) |
| A port or triangle ("Remove arrows" mode) | Same as double-clicking the class | Class card opens |
| Empty canvas | Deselects | Inspector returns to the help text; a pinned class card unpins |
| Hover an arrow | Shows the bundle popup | Every `from -> to` pair that arrow collapses; violating pairs in red |
| Hover a triangle ("Remove arrows" mode) | Shows the dependency list | `from -> to` list; red if any bundled pair violates the dependency rule |

## Canvas — right click (on a class or component)

The Swing menu offers **Refresh CRAP**, **Refresh Mutation**, **Refresh All
Mutation**, and **Omit**. Each writes a command to `.uml-viewer/to-agent.edn`
with `:target {:id :ns :kind :class|:component}`.

| Option | What the companion runs | What you should see after it is handled |
|---|---|---|
| Refresh CRAP | `clj -M:crap` for that class or the component's files, then IR | Box fill/scores and the **C** dot update; class-card CRAP numbers change |
| Refresh Mutation | Differential `clj -M:mutate` on those `src/` files, then IR | **M** dot and killed/survived/uncovered numbers update |
| Refresh All Mutation | Same, with `--mutate-all` (full campaign, slower) | Same, but a complete mutation re-run |
| Omit | Adds the id to the current proposal's `:omit`, or to policy `:omit` on the real diagram, then IR | The clicked box (and its children) disappears from the diagram |

In standalone mode the diagram stays unchanged and only the mailbox grows.
The Inspector may say the session is not attached.

## Inspector (right sidebar)

| Control | What it does | What you should see |
|---|---|---|
| **Real diagram** row (top, shows the document title) | Returns to the namespace tree; writes `:context :real` | Row highlighted gold; the canvas shows the real ns tree again |
| A **proposal** row | Shows that proposed architecture; writes `:context` with its id | Canvas marked **PROPOSAL — not instantiated in code**; violating arrows re-evaluated with that proposal's component order |
| **New Proposal** | Adds an empty proposal named with a timestamp and shows it | New row under "Proposals", selected |
| **Declutter** (label shows the current mode; click cycles it) | `Declutter none` → `Declutter arrows` → `Remove arrows` → `Declutter elements` → `Declutter classes` → back to none | arrows: one arrow per component pair per direction; Remove arrows: triangles on each box instead of lines, hover for deps; elements: nested names, members, and ports hidden; classes: class boxes inside components hidden |
| A proposal row, **right-click** | Swing menu: **Rename** (input dialog) and **Delete** | Rename changes the label; Delete removes the row |
| **Regen** | Standalone: re-runs the generator locally. Companion: queues `:regen` and wakes the agent | Standalone: status "Regenerated from source." and the canvas reloads. Companion: "Regen requested." or "Regen queued; Grok session not attached." |
| Inspector body, nothing selected | Shows the key hints | Click/double-click/`Esc`/scroll/zoom/`R` cheat text |
| Inspector body, class or package selected | Details of the selection | Class: package, `Level n`, CRAP, mutation, members. Package: label, CRAP, mutation, class count |

## Keyboard and mouse

| Input | Effect |
|---|---|
| `Esc` | Go up one level; at the top level, deselect. On the class card, close the card. Never quits. |
| `←` / `→` | Scroll horizontally |
| `↑` / `↓` | Scroll vertically |
| Scroll wheel | Pan vertically; **Shift**+scroll pans horizontally |
| `Ctrl+` / `Ctrl+=` | Zoom in 10% (view center stays fixed) |
| `Ctrl-` | Zoom out 10% |
| `Ctrl+0` | Reset zoom to 100% |
| `R` | Reload the current EDN immediately (the watcher also reloads on save and when `.metrics/` changes) |
| Close window | Exits the app; in companion mode kills only this project's tmux agent session |

## How to read the diagram

- **Color fill** combines the CRAP and mutation grades, red→green. Missing
  CRAP or mutation data counts as red (grade 1), not unknown. A parent takes
  the worst CRAP and worst mutation of its children.
- **C** and **M** dots in a box's upper-right are the two separate scores.
- **Red arrows** are dependency-rule violations (inner/higher level pointing
  outward). Bold red when the current selection highlights the arrow.
- **Level** numbers on class boxes: innermost is 0, and level 0 is drawn at
  the bottom. Good arrows (outer → inner) point down.
- **Italic** names are not classes (components/layers, interfaces,
  enumerations, package banners). **α** marks abstract classes, **I**
  interfaces. **Ovals** are foreign libraries listed in the policy.
- Missing, stale, partial, or failed measurements are not passing results.

## Keeping this current

If a control changes, update this file in the same commit:

- Inspector labels and drawing: `src/uml_viewer/adapters/draw.clj`
- Click hits, selection, keys, scroll/zoom: `src/uml_viewer/application/events.clj`
- Context menus, mailbox actions, window behavior: `src/uml_viewer/adapters/sketch.clj`
- Declutter cycle: `src/uml_viewer/domain/hierarchy.clj`
