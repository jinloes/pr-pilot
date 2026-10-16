export const SETTINGS_VIEW_STYLES = `  body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 16px 20px; font-size: 13px; }
  h1 { font-size: 16px; font-weight: 600; margin: 0 0 4px; }
  p.sub { color: var(--vscode-descriptionForeground); margin: 0 0 18px; }
  .section { margin: 0 0 18px; max-width: 560px; }
  .section-title { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: .03em; color: var(--vscode-descriptionForeground); margin: 0 0 10px; }
  .field { margin-bottom: 14px; }
  label { display: block; font-weight: 600; margin-bottom: 4px; }
  .hint { color: var(--vscode-descriptionForeground); font-size: 12px; margin-top: 4px; }
  .status { min-height: 18px; margin: 0 0 14px; font-size: 12px; color: var(--vscode-descriptionForeground); }
  .status.ok { color: var(--vscode-testing-iconPassed); }
  .status.error { color: var(--vscode-errorForeground); }
  select, input[type=text], input[type=number], textarea {
    width: 100%; box-sizing: border-box; padding: 5px 8px; font-size: 13px;
    color: var(--vscode-input-foreground); background: var(--vscode-input-background);
    border: 1px solid var(--vscode-input-border, transparent); border-radius: 2px;
  }
  select:focus-visible, input:focus-visible, textarea:focus-visible {
    outline: 1px solid var(--vscode-focusBorder); outline-offset: -1px;
  }
  .row { display: flex; gap: 8px; align-items: center; }
  .row select, .row input { flex: 1; }
  .row.wrap { flex-wrap: wrap; }
  .row input.short-field, input.short-field { width: 88px; flex: 0 0 88px; }
  button {
    padding: 5px 12px; font-size: 13px; cursor: pointer; border: none; border-radius: 2px;
    color: var(--vscode-button-foreground); background: var(--vscode-button-background);
  }
  button:hover { background: var(--vscode-button-hoverBackground); }
  button.secondary {
    color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground);
  }
  button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
  button:disabled {
    cursor: not-allowed; opacity: .6;
  }
  button:disabled:hover { background: var(--vscode-button-background); }
  button.secondary:disabled:hover { background: var(--vscode-button-secondaryBackground); }
  .field-error { margin: 4px 0 0; color: var(--vscode-errorForeground); font-size: 12px; }
  input[aria-invalid=true] { border-color: var(--vscode-inputValidation-errorBorder, var(--vscode-errorForeground)); }
  .hidden { display: none; }
  .modal[hidden] { display: none; }
  .modal {
    position: fixed; inset: 0; z-index: 10; display: grid; place-items: center;
    padding: 20px; background: rgba(0, 0, 0, .45);
  }
  .modal-content {
    width: min(420px, 100%); box-sizing: border-box; padding: 18px;
    color: var(--vscode-foreground); background: var(--vscode-editor-background);
    border: 1px solid var(--vscode-panel-border, var(--vscode-input-border, transparent));
    border-radius: 4px; box-shadow: 0 8px 24px rgba(0, 0, 0, .3);
  }
  .modal-content h2 { margin: 0 0 10px; font-size: 15px; }
  .modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
  details.advanced {
    margin: 0 0 8px;
    border: 1px solid var(--vscode-panel-border, var(--vscode-input-border, transparent));
    border-radius: 4px;
    padding: 8px 10px;
  }
  details.advanced > summary {
    cursor: pointer;
    font-weight: 600;
    margin-bottom: 8px;
  }
  details.advanced > summary::marker {
    color: var(--vscode-descriptionForeground);
  }
  .advanced-fields { margin-top: 8px; }`;
