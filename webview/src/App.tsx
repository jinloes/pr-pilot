import { useEffect, useState } from 'react'
import { onHostMessage, type ReviewLoadedMessage } from './bridge/types'

export default function App() {
  const [review, setReview] = useState<ReviewLoadedMessage | null>(null)

  useEffect(() => {
    const cleanup = onHostMessage((msg) => {
      if (msg.type === 'reviewLoaded') setReview(msg)
    })
    return cleanup
  }, [])

  return (
    <div style={{ fontFamily: 'sans-serif', padding: 16 }}>
      {review ? (
        <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(review, null, 2)}</pre>
      ) : (
        <p>Waiting for review data from IntelliJ…</p>
      )}
    </div>
  )
}
