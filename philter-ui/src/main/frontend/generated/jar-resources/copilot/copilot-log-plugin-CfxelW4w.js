import { j as R, ak as S, al as f, a9 as c, B as l, X as p, am as L, a5 as M, M as D, b as I, K as k, m as q, w as y, r as w } from "./copilot-CFYtxMkO.js";
import { r as v } from "./state-CCD0ECyT.js";
import { B as P } from "./base-panel-K2XDAPh8.js";
import { i as r } from "./icons-BeZYrHi8.js";
const A = 'copilot-log-panel ul{list-style-type:none;margin:0;padding:0}copilot-log-panel ul li{align-items:start;display:flex;gap:var(--space-50);padding:var(--space-100) var(--space-50);position:relative}copilot-log-panel ul li:before{border-bottom:1px dashed var(--divider-primary-color);content:"";inset:auto 0 0 calc(var(--size-m) + var(--space-100));position:absolute}copilot-log-panel ul li span.icon{display:flex;flex-shrink:0;justify-content:center;width:var(--size-m)}copilot-log-panel ul li.information span.icon{color:var(--blue-color)}copilot-log-panel ul li.warning span.icon{color:var(--warning-color)}copilot-log-panel ul li.error span.icon{color:var(--error-color)}copilot-log-panel ul li .message{display:flex;flex-direction:column;flex-grow:1;overflow:hidden}copilot-log-panel ul li:not(.expanded) span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}copilot-log-panel ul li button svg{transition:transform .15s cubic-bezier(.2,0,0,1)}copilot-log-panel ul li button[aria-expanded=true] svg{transform:rotate(90deg)}copilot-log-panel ul li code{margin-top:var(--space-50)}copilot-log-panel ul li.expanded .secondary{margin-top:var(--space-100)}copilot-log-panel .secondary a{display:block;margin-bottom:var(--space-50)}', B = () => {
  const e = { hour: "numeric", minute: "numeric", second: "numeric", fractionalSecondDigits: 3 };
  let t;
  const a = navigator.language ?? "", s = a.indexOf("@"), o = s === -1 ? a : a.slice(0, s);
  try {
    t = new Intl.DateTimeFormat(Intl.getCanonicalLocales(o), e);
  } catch (n) {
    console.error("Failed to create date time formatter for ", o, n), t = new Intl.DateTimeFormat("en-US", e);
  }
  return t;
}, C = B();
var _ = Object.defineProperty, b = Object.getOwnPropertyDescriptor, u = (e, t, a, s) => {
  for (var o = s > 1 ? void 0 : s ? b(t, a) : t, n = e.length - 1, i; n >= 0; n--)
    (i = e[n]) && (o = (s ? i(t, a, o) : i(o)) || o);
  return s && o && _(t, a, o), o;
};
class F {
  constructor() {
    this.showTimestamps = !1, q(this);
  }
  toggleShowTimestamps() {
    this.showTimestamps = !this.showTimestamps;
  }
}
const h = new F();
let d = class extends P {
  constructor() {
    super(...arguments), this.unreadErrors = !1, this.messages = [], this.nextMessageId = 1, this.transitionDuration = 0, this.errorHandlersAdded = !1;
  }
  connectedCallback() {
    if (super.connectedCallback(), this.onCommand("log", (e) => {
      this.handleLogEventData({ type: e.data.type, message: e.data.message });
    }), this.onEventBus("log", (e) => this.handleLogEvent(e)), this.onEventBus("update-log", (e) => this.updateLog(e.detail)), this.onEventBus("notification-shown", (e) => this.handleNotification(e)), this.onEventBus("clear-log", () => this.clear()), this.reaction(
      () => R.sectionPanelResizing,
      () => {
        this.requestUpdate();
      }
    ), this.transitionDuration = parseInt(
      window.getComputedStyle(this).getPropertyValue("--dev-tools-transition-duration"),
      10
    ), !this.errorHandlersAdded) {
      const e = (t) => {
        k(() => {
          y.attentionRequiredPanelTag = "copilot-log-panel";
        }), this.log(p.ERROR, t.message, !!t.internal, t.details, t.link);
      };
      S((t) => {
        e(t);
      }), f.forEach((t) => {
        e(t);
      }), f.length = 0, this.errorHandlersAdded = !0;
    }
  }
  clear() {
    this.messages = [];
  }
  handleNotification(e) {
    this.log(e.detail.type, e.detail.message, !0, e.detail.details, e.detail.link);
  }
  handleLogEvent(e) {
    this.handleLogEventData(e.detail);
  }
  handleLogEventData(e) {
    this.log(
      e.type,
      e.message,
      !!e.internal,
      e.details,
      e.link,
      c(e.expandedMessage),
      c(e.expandedDetails),
      e.id
    );
  }
  activate() {
    this.unreadErrors = !1, this.updateComplete.then(() => {
      const e = this.renderRoot.querySelector(".message:last-child");
      e && e.scrollIntoView();
    });
  }
  render() {
    return l`
      <style>
        ${A}
      </style>
      <ul>
        ${this.messages.map((e) => this.renderMessage(e))}
      </ul>
    `;
  }
  renderMessage(e) {
    let t, a;
    return e.type === p.ERROR ? (a = r.alertTriangle, t = "Error") : e.type === p.WARNING ? (a = r.warning, t = "Warning") : (a = r.info, t = "Info"), l`
      <li
        class="${e.type} ${e.expanded ? "expanded" : ""} ${e.details || e.link ? "has-details" : ""}"
        data-id="${e.id}">
        <span aria-label="${t}" class="icon" title="${t}">${a}</span>
        <span class="message" @click=${() => this.toggleExpanded(e)}>
          <span class="timestamp" ?hidden=${!h.showTimestamps}>${N(e.timestamp)}</span>
          <span class="primary">
            ${e.expanded && e.expandedMessage ? e.expandedMessage : e.message}
          </span>
          ${e.expanded ? l` <span class="secondary"> ${e.expandedDetails ?? e.details} </span>` : l` <span class="secondary" ?hidden="${!e.details && !e.link}">
                ${c(e.details)}
                ${e.link ? l` <a href="${e.link}" target="_blank">Learn more</a>` : ""}
              </span>`}
        </span>
        <!-- TODO: a11y, button needs aria-controls with unique ids -->
        <button
          aria-controls="content"
          aria-expanded="${e.expanded}"
          aria-label="Expand details"
          class="icon"
          @click=${() => this.toggleExpanded(e)}
          ?hidden=${!this.canBeExpanded(e)}>
          <span>${r.chevronRight}</span>
        </button>
      </li>
    `;
  }
  log(e, t, a, s, o, n, i, E) {
    const T = this.nextMessageId;
    this.nextMessageId += 1, i || (i = t);
    const g = {
      id: T,
      type: e,
      message: t,
      details: s,
      link: o,
      dontShowAgain: !1,
      deleted: !1,
      expanded: !1,
      expandedMessage: n,
      expandedDetails: i,
      timestamp: /* @__PURE__ */ new Date(),
      internal: a,
      userId: E
    };
    for (this.messages.push(g); this.messages.length > d.MAX_LOG_ROWS; )
      this.messages.shift();
    return this.requestUpdate(), this.updateComplete.then(() => {
      const m = this.renderRoot.querySelector(".message:last-child");
      m ? (setTimeout(() => m.scrollIntoView({ behavior: "smooth" }), this.transitionDuration), this.unreadErrors = !1) : e === p.ERROR && (this.unreadErrors = !0);
    }), g;
  }
  updateLog(e) {
    let t = this.messages.find((a) => a.userId === e.id);
    t || (t = this.log(p.INFORMATION, "<Log message to update was not found>", !1)), Object.assign(t, e), L(t.expandedDetails) && (t.expandedDetails = c(t.expandedDetails)), this.requestUpdate();
  }
  updated() {
    const e = this.querySelector(".row:last-child");
    e && this.isTooLong(e.querySelector(".firstrowmessage")) && e.querySelector("button.expand")?.removeAttribute("hidden");
  }
  toggleExpanded(e) {
    this.canBeExpanded(e) && (e.expanded = !e.expanded, this.requestUpdate()), M("use-log", { source: "toggleExpanded" });
  }
  canBeExpanded(e) {
    if (e.expandedMessage || e.expanded)
      return !0;
    const t = this.querySelector(`[data\\-id="${e.id}"]`)?.querySelector(
      ".firstrowmessage"
    );
    return this.isTooLong(t);
  }
  isTooLong(e) {
    return e && e.offsetWidth < e.scrollWidth;
  }
};
d.MAX_LOG_ROWS = 1e3;
u([
  v()
], d.prototype, "unreadErrors", 2);
u([
  v()
], d.prototype, "messages", 2);
d = u([
  w("copilot-log-panel")
], d);
let x = class extends D {
  createRenderRoot() {
    return this;
  }
  render() {
    return l`
      <style>
        copilot-log-panel-actions {
          display: contents;
        }
      </style>
      <button
        aria-label="Clear log"
        class="icon"
        title="Clear log"
        @click=${() => {
      I.emit("clear-log", {});
    }}>
        <span>${r.trash}</span>
      </button>
      <button
        aria-label="Toggle timestamps"
        class="icon"
        title="Toggle timestamps"
        @click=${() => {
      h.toggleShowTimestamps();
    }}>
        <span class="${h.showTimestamps ? "on" : "off"}"> ${r.clock} </span>
      </button>
    `;
  }
};
x = u([
  w("copilot-log-panel-actions")
], x);
const $ = {
  header: "Log",
  expanded: !0,
  panelOrder: 0,
  panel: "bottom",
  floating: !1,
  tag: "copilot-log-panel",
  actionsTag: "copilot-log-panel-actions",
  individual: !0
}, U = {
  init(e) {
    e.addPanel($);
  }
};
window.Vaadin.copilot.plugins.push(U);
y.addPanel($);
function N(e) {
  return C.format(e);
}
export {
  x as Actions,
  d as CopilotLogPanel
};
