import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'

interface Props {
  children: ReactNode
  onReload?: () => void
}

interface State {
  hasError: boolean
}

function formatCaughtError(error: unknown): string {
  if (error instanceof Error) {
    return `${error.name}: ${error.message}`
  }
  if (typeof error === 'string') return error
  if (typeof error === 'number' || typeof error === 'boolean' || typeof error === 'bigint') {
    return error.toString()
  }
  if (typeof error === 'symbol') return error.description ?? 'Symbol'
  if (error == null) return 'Unknown render error'
  return 'Non-Error render failure'
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    console.error('[pr-pilot] Unhandled webview render error:', formatCaughtError(error), info.componentStack)
  }

  private handleReload = (): void => {
    this.setState({ hasError: false })
    this.props.onReload?.()
  }

  render(): ReactNode {
    if (!this.state.hasError) {
      return this.props.children
    }
    return (
      <div
        role="alert"
        data-testid="webview-error-boundary"
        className="flex h-screen flex-col items-center justify-center gap-3 bg-background p-6 text-center text-foreground"
      >
        <h1 className="text-sm font-semibold">Something went wrong displaying PR Pilot</h1>
        <p className="max-w-xs text-xs text-muted-foreground">
          An unexpected rendering error occurred. Reload the view to recover your current session.
        </p>
        <Button variant="outline" size="sm" className="text-xs" onClick={this.handleReload}>
          Reload view
        </Button>
      </div>
    )
  }
}
