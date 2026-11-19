import React, { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App'
import { sendToHost } from './bridge/types'
import { ErrorBoundary } from './components/ErrorBoundary/ErrorBoundary'
import { I18nProvider, localeFromLocation } from './i18n/I18nProvider'
import { applyHostTheme } from './theme/hostTheme'

applyHostTheme(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')

// Dev-only a11y diagnostics in browser console.
if (import.meta.env.DEV) {
  void import('@axe-core/react').then(({ default: axe }) => {
    void axe(React, createRoot, 1000)
  })
}

const root = createRoot(document.getElementById('root')!)
let reloadKey = 0

function handleBoundaryReload(): void {
  reloadKey += 1
  renderApp()
  sendToHost({ type: 'refreshPRs', state: 'open', searchScope: 'currentRepo' })
}

function renderApp(): void {
  root.render(
    <StrictMode>
      <ErrorBoundary key={reloadKey} onReload={handleBoundaryReload}>
        <I18nProvider locale={localeFromLocation()}>
          <App />
        </I18nProvider>
      </ErrorBoundary>
    </StrictMode>,
  )
}

renderApp()
