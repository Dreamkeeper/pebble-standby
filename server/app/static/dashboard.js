// Standby dashboard: the one script besides htmx. Served same-origin so the
// Content-Security-Policy can stay "script-src 'self'" with no eval.
//
// The live section of a wearer page refreshes on a timer. While the
// operator is mid-interaction inside it (typing a resolve reason, picking
// an option) the swap must not eat their input. htmx could express this as
// an event filter in hx-trigger, but filters are evaluated with eval,
// which the CSP forbids; so the same rule lives here.
document.addEventListener("htmx:beforeSwap", function (ev) {
  var target = ev.detail && ev.detail.target;
  if (!target || target.id !== "live") return;
  var active = document.activeElement;
  if (active && active.closest && active.closest("#live")) {
    ev.detail.shouldSwap = false;
  }
});
