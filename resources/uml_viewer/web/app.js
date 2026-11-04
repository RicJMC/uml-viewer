(() => {
  const $ = (id) => document.getElementById(id);
  const stage = $("stage");
  const card = $("card");
  const menu = $("menu");
  const source = $("source");

  function post(params) {
    return fetch("/event", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(params).toString(),
    })
      .then((r) => r.json())
      .then(handleReply)
      .catch(() => {});
  }

  let lastMove = null;
  let moveQueued = false;
  function queueMove(x, y) {
    lastMove = { x, y };
    if (moveQueued) return;
    moveQueued = true;
    requestAnimationFrame(() => {
      moveQueued = false;
      post({ type: "move", x: lastMove.x, y: lastMove.y });
    });
  }

  function sendSize() {
    post({ type: "resize", w: window.innerWidth, h: window.innerHeight });
  }

  function hideMenu() {
    menu.hidden = true;
    menu.replaceChildren();
  }

  let pointer = { x: 0, y: 0 };

  function showMenu(m) {
    menu.replaceChildren(
      ...m.items.map((item, index) => {
        const b = document.createElement("button");
        b.type = "button";
        b.textContent = item.label;
        b.addEventListener("click", () => {
          hideMenu();
          let value = "";
          if (item.prompt) {
            value = window.prompt(item.prompt, item.default || "");
            if (value === null) return;
          }
          post({ type: "menu", id: m.id, index, value });
        });
        return b;
      })
    );
    menu.hidden = false;
    const r = menu.getBoundingClientRect();
    menu.style.left = Math.min(pointer.x, window.innerWidth - r.width - 8) + "px";
    menu.style.top = Math.min(pointer.y, window.innerHeight - r.height - 8) + "px";
  }

  function showSource(page) {
    $("source-title").textContent = page.title;
    const frame = $("source-frame");
    frame.onload = () => {
      const here = frame.contentDocument && frame.contentDocument.querySelector("#here");
      if (here) here.scrollIntoView({ block: "center" });
    };
    frame.srcdoc = page.html;
    source.hidden = false;
  }

  function handleReply(reply) {
    if (!reply) return;
    if (reply.menu) showMenu(reply.menu);
    if (reply.source) showSource(reply.source);
  }

  stage.addEventListener("mousemove", (e) => queueMove(e.clientX, e.clientY));

  stage.addEventListener("mousedown", (e) => {
    pointer = { x: e.clientX, y: e.clientY };
    hideMenu();
    post({ type: "press", x: e.clientX, y: e.clientY, button: e.button, count: e.detail });
  });

  // Touch: one finger pans, two fingers pinch-zoom, a short tap presses.
  // Posting every touchmove would flood the server, so pan is batched.
  let pan = { dx: 0, dy: 0, queued: false };
  function flushPan() {
    if (pan.dx) post({ type: "wheel", amount: pan.dx / 48, horizontal: 1 });
    if (pan.dy) post({ type: "wheel", amount: pan.dy / 48, horizontal: 0 });
    pan.dx = 0;
    pan.dy = 0;
  }
  function queuePan(dx, dy) {
    pan.dx += dx;
    pan.dy += dy;
    if (!pan.queued) {
      pan.queued = true;
      setTimeout(() => {
        pan.queued = false;
        flushPan();
      }, 60);
    }
  }
  function touchDistance(touches) {
    const dx = touches[0].clientX - touches[1].clientX;
    const dy = touches[0].clientY - touches[1].clientY;
    return Math.hypot(dx, dy);
  }
  let touch = null;
  stage.addEventListener(
    "touchstart",
    (e) => {
      hideMenu();
      if (e.touches.length === 1) {
        const t = e.touches[0];
        touch = {
          x: t.clientX,
          y: t.clientY,
          startX: t.clientX,
          startY: t.clientY,
          moved: false,
          pinch: null,
          time: Date.now(),
        };
      } else if (e.touches.length === 2) {
        touch = { pinch: touchDistance(e.touches), moved: true };
      }
      e.preventDefault();
    },
    { passive: false }
  );
  stage.addEventListener(
    "touchmove",
    (e) => {
      if (!touch) return;
      if (e.touches.length === 1 && touch.pinch === null) {
        const t = e.touches[0];
        const dx = t.clientX - touch.x;
        const dy = t.clientY - touch.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) touch.moved = true;
        queuePan(dx, dy);
        touch.x = t.clientX;
        touch.y = t.clientY;
      } else if (e.touches.length === 2) {
        const d = touchDistance(e.touches);
        if (touch.pinch) {
          const ratio = d / touch.pinch;
          if (ratio > 1.12) {
            post({ type: "key", key: "+", ctrl: 1 });
            touch.pinch = d;
          } else if (ratio < 0.89) {
            post({ type: "key", key: "-", ctrl: 1 });
            touch.pinch = d;
          }
        } else {
          touch.pinch = d;
        }
      }
      e.preventDefault();
    },
    { passive: false }
  );
  stage.addEventListener(
    "touchend",
    (e) => {
      if (touch && !touch.pinch && !touch.moved && Date.now() - touch.time < 400) {
        post({
          type: "press",
          x: touch.startX,
          y: touch.startY,
          button: 0,
          count: 1,
        });
      }
      if (!e.touches.length) {
        if (pan.dx || pan.dy) flushPan();
        touch = null;
      }
      e.preventDefault();
    },
    { passive: false }
  );

  stage.addEventListener("contextmenu", (e) => e.preventDefault());

  stage.addEventListener(
    "wheel",
    (e) => {
      e.preventDefault();
      if (e.ctrlKey) {
        post({ type: "key", key: e.deltaY < 0 ? "+" : "-", ctrl: 1 });
        return;
      }
      const horizontal = e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY);
      const delta = e.shiftKey && !e.deltaX ? e.deltaY : horizontal ? e.deltaX : e.deltaY;
      const px = e.deltaMode === 1 ? delta * 48 : delta;
      post({ type: "wheel", amount: px / 48, horizontal: horizontal ? 1 : 0 });
    },
    { passive: false }
  );

  window.addEventListener("keydown", (e) => {
    if (e.target && e.target.closest && e.target.closest("input, textarea")) return;
    if (e.key === "Escape" && !menu.hidden) return hideMenu();
    if (e.key === "Escape" && !source.hidden) {
      source.hidden = true;
      return;
    }
    const ctrl = e.ctrlKey || e.metaKey;
    if (ctrl && !["+", "=", "-", "_", "0"].includes(e.key)) return;
    if (e.key.startsWith("Arrow") || ctrl) e.preventDefault();
    post({ type: "key", key: e.key, ctrl: ctrl ? 1 : 0 });
  });

  window.addEventListener("resize", sendSize);
  document.addEventListener("mousedown", (e) => {
    if (!menu.hidden && !menu.contains(e.target)) hideMenu();
  });

  $("card-body").addEventListener("click", (e) => {
    const svg = $("card-body").querySelector("svg");
    if (!svg) return;
    const r = svg.getBoundingClientRect();
    post({ type: "card-press", y: e.clientY - r.top });
  });
  $("card-close").addEventListener("click", () => post({ type: "card-close" }));
  $("source-close").addEventListener("click", () => (source.hidden = true));
  $("back").addEventListener("click", () => post({ type: "key", key: "Escape", ctrl: 0 }));
  $("fit").addEventListener("click", () => post({ type: "key", key: "f", ctrl: 0 }));
  $("quit").addEventListener("click", () => {
    if (window.confirm("Quit the viewer?")) post({ type: "quit" });
  });

  function draggable(panel, handle) {
    handle.addEventListener("mousedown", (e) => {
      if (e.target.closest("button")) return;
      const start = { x: e.clientX, y: e.clientY, left: panel.offsetLeft, top: panel.offsetTop };
      const move = (m) => {
        panel.style.left = start.left + m.clientX - start.x + "px";
        panel.style.top = Math.max(0, start.top + m.clientY - start.y) + "px";
      };
      const up = () => {
        window.removeEventListener("mousemove", move);
        window.removeEventListener("mouseup", up);
      };
      window.addEventListener("mousemove", move);
      window.addEventListener("mouseup", up);
      e.preventDefault();
    });
  }
  draggable(card, $("card-head"));
  draggable(source, source.querySelector("header"));

  let shownCard = "";
  function showCard(c) {
    if (!c.svg) {
      card.hidden = true;
      shownCard = "";
      return;
    }
    $("card-title").textContent = c.title;
    if (c.title !== shownCard) $("card-body").scrollTop = 0;
    shownCard = c.title;
    $("card-body").innerHTML = c.svg;
    card.hidden = false;
  }

  function connect() {
    const events = new EventSource("/events");
    events.addEventListener("open", () => {
      $("status").hidden = true;
      sendSize();
    });
    events.addEventListener("title", (e) => {
      if (e.data) document.title = e.data;
    });
    events.addEventListener("frame", (e) => {
      if (e.data) stage.innerHTML = e.data;
    });
    events.addEventListener("card", (e) => showCard(JSON.parse(e.data || "{}")));
    events.addEventListener("error", () => {
      $("status").hidden = false;
    });
  }

  connect();
})();
