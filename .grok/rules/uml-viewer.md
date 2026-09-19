# UML viewer

The diagram is already on screen.

- Do **not** edit `examples/uml-viewer.edn`. It is generated from
  `examples/uml-viewer.policy.edn` via `clj -M:ir`.
- Policy follows **namespace nesting**, not invented layers/components.
  Dots after the prefix are the tree. Do not add Domain/Engine-style
  packages. **Layer** and **component** mean the same thing.
- `:proposals` are named groupings that are **not** in the source. Preserve
  them when rewriting policy. Do not invent them on launch. The inspector
  lists them; **P** returns to the namespace tree. If instructed, add a
  named proposal (default name is a timestamp) and regenerate the IR.
- After source or policy change: `clj -M:crap`, `clj -M:mutate` on changed
  `src/` files (differential), then `clj -M:ir`. Uncovered mutants are
  coverage gaps: keep the snapshot; do not re-run the file or force a
  full mutation because mutate exited non-zero.
- Right-click mail (`to-agent.edn`): `:refresh-crap` → `clj -M:crap` for
  that class or component then IR. `:refresh-mutate` → differential
  `clj -M:mutate` on those src files. `:refresh-mutate-all` →
  `clj -M:mutate --mutate-all` on those files. `:omit` → add `:id` to
  the current proposal's `:omit`, or to policy `:omit` on the real
  diagram, then IR.
- `:context` mail: `{:context :real}` or `{:context :proposal :proposal-id
  id :name "..."}` is the diagram under discussion. Stay on that context
  until a later `:context` arrives.
- The examined project must have aliases `:uml-viewer` (fresh start:
  spawn this companion, wait for `:display`) and `:uml-viewer-restart`
  (new JVM, keep this session, load the EDN immediately). Add them if
  they are missing.
- Do not start the viewer on launch; EDN reloads when the file mtime
  changes. To restart it: write `:quit-for-restart` to
  `.uml-viewer/to-viewer.edn`, wait for the JVM to exit, then
  `clj -M:uml-viewer-restart`. Do not pass `--restart` except through
  that alias. Do not SIGKILL; closing the window still kills Grok.

Do not commit or push unless asked. Esc interrupts a turn in this terminal;
do not kill the process on interrupt.
