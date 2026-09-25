import { Button } from '@/components/ui/button'

interface Props {
  onShowList?: () => void
}

export function ReviewEmptyState({ onShowList }: Props) {
  return (
    <div className="flex min-h-0 flex-1 flex-col items-start justify-center gap-2 p-6">
      <h2 className="text-base font-semibold">Choose a pull request to begin</h2>
      <p className="max-w-prose text-sm text-muted-foreground">
        Select a pull request to open its diff and review actions.
      </p>
      {onShowList && (
        <Button className="sm:hidden" variant="outline" size="sm" onClick={onShowList}>
          Show pull requests
        </Button>
      )}
    </div>
  )
}
