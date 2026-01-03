import { r as f, b as n, E as g, B as p, w as b, a2 as m, Z as t, G as v } from "./copilot-CFYtxMkO.js";
import { B as $ } from "./base-panel-K2XDAPh8.js";
import { i as e } from "./icons-BeZYrHi8.js";
const y = 'copilot-shortcuts-panel{display:flex;flex-direction:column;padding:var(--space-150)}copilot-shortcuts-panel h3{font:var(--font-xsmall-semibold);margin-bottom:var(--space-100);margin-top:0}copilot-shortcuts-panel h3:not(:first-of-type){margin-top:var(--space-200)}copilot-shortcuts-panel ul{display:flex;flex-direction:column;list-style:none;margin:0;padding:0}copilot-shortcuts-panel ul li{display:flex;align-items:center;gap:var(--space-50);position:relative}copilot-shortcuts-panel ul li:not(:last-of-type):before{border-bottom:1px dashed var(--border-color);content:"";inset:auto 0 0 calc(var(--size-m) + var(--space-50));position:absolute}copilot-shortcuts-panel ul li span:has(svg){align-items:center;display:flex;height:var(--size-m);justify-content:center;width:var(--size-m)}copilot-shortcuts-panel .kbds{margin-inline-start:auto}copilot-shortcuts-panel kbd{align-items:center;border:1px solid var(--border-color);border-radius:var(--radius-2);box-sizing:border-box;display:inline-flex;font-family:var(--font-family);font-size:var(--font-size-1);line-height:var(--line-height-1);padding:0 var(--space-50)}', u = window.Vaadin.copilot.tree;
if (!u)
  throw new Error("Tried to access copilot tree before it was initialized.");
var w = Object.getOwnPropertyDescriptor, x = (a, i, h, r) => {
  for (var o = r > 1 ? void 0 : r ? w(i, h) : i, l = a.length - 1, c; l >= 0; l--)
    (c = a[l]) && (o = c(o) || o);
  return o;
};
let d = class extends $ {
  constructor() {
    super(), this.onKeyPressedEvent = (a) => {
      a.detail.event.defaultPrevented || this.close();
    }, this.onTreeUpdated = () => {
      this.requestUpdate();
    };
  }
  connectedCallback() {
    super.connectedCallback(), n.on("copilot-tree-created", this.onTreeUpdated), n.on("escape-key-pressed", this.onKeyPressedEvent);
  }
  disconnectedCallback() {
    super.disconnectedCallback(), n.off("copilot-tree-created", this.onTreeUpdated), n.off("escape-key-pressed", this.onKeyPressedEvent);
  }
  render() {
    const a = u.hasFlowComponents();
    return p`<style>
        ${y}
      </style>
      <h3>Global</h3>
      <ul>
        <li>
          <span>${e.vaadinLogo}</span>
          <span>Copilot</span>
          ${s(t.toggleCopilot)}
        </li>
        <li>
          <span>${e.terminal}</span>
          <span>Command window</span>
          ${s(t.toggleCommandWindow)}
        </li>
        <li>
          <span>${e.flipBack}</span>
          <span>Undo</span>
          ${s(t.undo)}
        </li>
        <li>
          <span>${e.flipForward}</span>
          <span>Redo</span>
          ${s(t.redo)}
        </li>
      </ul>
      <h3>Selected component</h3>
      <ul>
        <li>
          <span>${e.fileCodeAlt}</span>
          <span>Go to source</span>
          ${s(t.goToSource)}
        </li>
        ${a ? p`<li>
              <span>${e.code}</span>
              <span>Go to attach source</span>
              ${s(t.goToAttachSource)}
            </li>` : g}
        <li>
          <span>${e.copy}</span>
          <span>Copy</span>
          ${s(t.copy)}
        </li>
        <li>
          <span>${e.clipboard}</span>
          <span>Paste</span>
          ${s(t.paste)}
        </li>
        <li>
          <span>${e.copyAlt}</span>
          <span>Duplicate</span>
          ${s(t.duplicate)}
        </li>
        <li>
          <span>${e.userUp}</span>
          <span>Select parent</span>
          ${s(t.selectParent)}
        </li>
        <li>
          <span>${e.userLeft}</span>
          <span>Select previous sibling</span>
          ${s(t.selectPreviousSibling)}
        </li>
        <li>
          <span>${e.userRight}</span>
          <span>Select first child / next sibling</span>
          ${s(t.selectNextSibling)}
        </li>
        <li>
          <span>${e.trash}</span>
          <span>Delete</span>
          ${s(t.delete)}
        </li>
        <li>
          <span>${e.zap}</span>
          <span>Quick add from palette</span>
          ${s("<kbd>A ... Z</kbd>")}
        </li>
      </ul>`;
  }
  /**
   * Closes the panel. Used from shortcuts
   */
  close() {
    b.updatePanel("copilot-shortcuts-panel", {
      floating: !1
    });
  }
};
d = x([
  f("copilot-shortcuts-panel")
], d);
function s(a) {
  return p`<span class="kbds">${m(a)}</span>`;
}
const P = v({
  header: "Keyboard Shortcuts",
  tag: "copilot-shortcuts-panel",
  width: 400,
  height: 550,
  floatingPosition: {
    top: 50,
    left: 50
  }
}), k = {
  init(a) {
    a.addPanel(P);
  }
};
window.Vaadin.copilot.plugins.push(k);
