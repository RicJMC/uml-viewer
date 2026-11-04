# Browser viewer

`--web` renders the same scene as the desktop window, but as SVG in a page.
There is no npm build: the JVM serves three small static files and pushes
frames over server-sent events (SSE). The browser sends pointer, wheel, key,
and resize events back as form posts.

```bash
# local browser, no companion
clj -M:run --web --standalone target/java.edn

# companion still starts, page waits for :display
clj -M:run --web examples/uml-viewer.edn

# reachable from a phone on the same network
clj -M:run --web --standalone --host 0.0.0.0 target/java.edn
```

The launcher prints the URL. It also writes `.uml-viewer/web.edn`:

```clojure
{:port 23456 :host "127.0.0.1" :url "http://127.0.0.1:23456/"}
```

The port is fixed per project (hash of the canonical path, 20000–29999) so an
open tab reconnects after a restart. If that port is taken the server tries
the next 20, then any free port.

| Flag | Effect |
|------|--------|
| `--web` | Serve in the browser instead of opening the Quil window. |
| `--host HOST` | Bind address. Default `127.0.0.1`; `0.0.0.0` exposes the LAN. |
| `--port N` | Override the per-project port. `0` asks the OS. |
| `--no-open` | Do not launch `xdg-open`/`open`. |
| `--standalone` | Load the EDN now and do not start/stop a companion. |

`--restart` still restores depth, pan, zoom, and the selected proposal from
`.uml-viewer/session.edn`.

## Phone and tablet

A window narrower than 720 px drops the 280 px inspector and gives the whole
width to the diagram. One finger pans, two fingers pinch-zoom, and a short tap
presses. Two floating buttons replace the inspector:

- **←** — up a level (same as Esc).
- **Fit** — fit the whole scene.

A tap is the primary gesture on a narrow window: tap a component to open it,
tap a class for its card. On the desktop a single click selects and dims
unrelated arrows; a double click drills or opens the card.

The page is responsive to real viewport changes, so rotating the device
refits the scene. Automatic fit stops at 35% on a phone, so a large diagram
stays readable; the **Fit** button can still show the whole scene. Use the
browser's "Add to Home Screen" for a full-screen shortcut.

## Interactions

| Action | Result |
|--------|--------|
| Click a box | Select it; its arrows stay bright and the others dim. |
| Double-click a component | Drill into it. |
| Double-click a leaf class | Open its card. |
| Click a card row | Open the member's source in the source panel. |
| Click a relationship row | Select that class. |
| Right-click a box | Refresh CRAP / mutation / omit menu. |
| Right-click a proposal | Rename or delete. |
| Wheel | Pan; Shift-wheel pans horizontally. |
| One-finger drag | Pan (touch). |
| Two-finger pinch | Zoom in / out (touch). |
| Ctrl + / − / 0 | Zoom in / out / 100%. |
| F | Fit the scene. |
| R | Reload the EDN without waiting for the companion. |
| Esc | Close the card; otherwise go up a level. |
| Quit | Stop the server. |

The Inspector still carries proposals, declutter, Regen, and Fit on desktop
widths. Cards and the source panel are draggable overlays. The source panel is
an iframe with the whole file and the requested line highlighted.

## Security

There is no authentication and no TLS. The default bind is loopback. When you
pass `--host 0.0.0.0`, anyone who can reach the port can read the diagram and
source files, and can post viewer events. Use it on a trusted LAN only, or put
it behind a reverse proxy with auth. The page refuses cross-origin posts by
default (the browser's same-origin policy; the server does not add CORS
headers).

## Architecture

| Piece | File |
|-------|------|
| HTTP server, SSE, state thread, assets | `src/uml_viewer/adapters/web.clj` |
| Page events → viewer state | `src/uml_viewer/adapters/viewer.clj` |
| SVG renderer | `src/uml_viewer/adapters/svg.clj` |
| Static page | `resources/uml_viewer/web/` |

One single-thread executor owns the state. Ticks reload the EDN/metrics and
poll the companion mailbox every 100 ms. When the tab title, frame, or card
changes, the server pushes only the changed part over SSE. Each open tab has
its own stream; closing a tab does not stop the viewer.

## Limits and next steps

- `--screenshot` is desktop-only; use the browser's screenshot for a static
  image of the web page.
- Branch/PR review highlighting is not in this slice. The desktop branch
  viewer from `joshsmoore` remains a separate decision.
- The web page has no relationship-kind filters or "only violations" toggle
  yet; the desktop has neither.
- Metrics are the same as the desktop: CRAP and mutation overlays from
  `.metrics/`. C++ coverage is opt-in via `clj -M:cpp-metrics`.

## Tests

`spec/uml_viewer/adapters/web_spec.clj` starts the server on an ephemeral port
and checks `/` and `/health`. `viewer_spec.clj` covers the compact layout,
frame/card split, resize, and F. Run the full suite with:

```bash
clojure -M:spec
```
