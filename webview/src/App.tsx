import { useEffect } from 'react'
import { PRList } from './components/PRList'
import { type PR } from './bridge/types'

// Dev-mode fixture data — replaced by real bridge messages in production
const DEV_PRS: PR[] = [
  {
    number: 4821,
    title: 'Migrate auth middleware to JWT RS256 — remove legacy HMAC fallback',
    owner: 'acme',
    repo: 'platform',
    author: 'jsmith',
    createdAt: '2026-04-28T14:32:00Z',
    htmlUrl: '#',
    hasDraft: true,
  },
  {
    number: 4819,
    title: 'Add rate limiting to /api/v2/search endpoint',
    owner: 'acme',
    repo: 'platform',
    author: 'mchen',
    createdAt: '2026-04-27T09:11:00Z',
    htmlUrl: '#',
    hasDraft: false,
  },
  {
    number: 312,
    title: 'Switch CI pipeline from CircleCI to GitHub Actions',
    owner: 'acme',
    repo: 'infra',
    author: 'rlopez',
    createdAt: '2026-04-26T17:45:00Z',
    htmlUrl: '#',
    hasDraft: false,
  },
  {
    number: 4815,
    title: 'Refactor session store: extract Redis adapter behind interface',
    owner: 'acme',
    repo: 'platform',
    author: 'jsmith',
    createdAt: '2026-04-25T11:00:00Z',
    htmlUrl: '#',
    hasDraft: true,
  },
  {
    number: 88,
    title: 'Bump jackson-databind 2.17.1 → 2.18.0 (CVE-2024-38817)',
    owner: 'acme',
    repo: 'dependencies',
    author: 'bot',
    createdAt: '2026-04-24T08:00:00Z',
    htmlUrl: '#',
    hasDraft: false,
  },
]

// In production, the Java side fires a 'prListLoaded' message via the bridge.
// In dev (no JCEF), we seed fixture data directly after mount.
function seedDevData() {
  const w = window as unknown as { __handleMessage?: (json: string) => void }
  if (!w.__handleMessage) return
  w.__handleMessage(JSON.stringify({ type: 'prListLoaded', prs: DEV_PRS }))
}

export default function App() {
  useEffect(() => {
    // Give the bridge handler time to register, then seed dev data
    const id = setTimeout(seedDevData, 100)
    return () => clearTimeout(id)
  }, [])

  function handleSelect(pr: PR) {
    console.log('[App] selected PR', pr.number, pr.title)
  }

  return (
    <div style={{ height: '100%' }}>
      <PRList onSelect={handleSelect} />
    </div>
  )
}
