import type { IncomingMessage } from './types'
import { BRIDGE_PROTOCOL_VERSION, isLineComment, isRequiredPrKey, parseIncomingMessage } from './validation'

export const REJECTED_REVIEW_MESSAGE =
  'PR Pilot received a review it could not read. The extension and its UI may be out of sync; reload the window and generate again.'

/** Messages that end a review run; losing one leaves the run spinning forever. */
const TERMINAL_REVIEW_TYPES = new Set(['reviewResult', 'reviewError'])

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function quoted(value: unknown): string {
  return typeof value === 'string' ? `"${value.slice(0, 64)}"` : String(value)
}

function reviewResultProblem(result: unknown): string {
  if (!isRecord(result)) return 'result is not an object'
  if (typeof result.summary !== 'string') return 'summary is not a string'
  if (!Array.isArray(result.lineComments)) return 'lineComments is not an array'
  const index = result.lineComments.findIndex((comment) => !isLineComment(comment))
  if (index < 0) return `verdict ${quoted(result.verdict)}, ${result.lineComments.length} comments, or diff size`
  const comment: Record<string, unknown> = isRecord(result.lineComments[index]) ? result.lineComments[index] : {}
  const fields = ['type', 'severity', 'category', 'confidence']
    .map((field) => `${field}=${quoted(comment[field])}`)
    .join(', ')
  return `comment ${index} (${fields})`
}

/**
 * One-line diagnosis of a host message the bridge rejected. Names the type and, for review
 * results, the first failing comment's enum fields: the usual cause is a host newer than the UI.
 * Never includes comment text, paths, or diffs.
 */
export function describeRejectedHostMessage(value: unknown): string {
  if (!isRecord(value)) return 'payload is not an object'
  const type = typeof value.type === 'string' ? value.type.slice(0, 64) : '(missing type)'
  if (value.protocolVersion !== BRIDGE_PROTOCOL_VERSION) {
    return `"${type}": protocol version ${quoted(value.protocolVersion)}, expected ${BRIDGE_PROTOCOL_VERSION}`
  }
  if (type === 'reviewResult') return `"${type}": ${reviewResultProblem(value.result)}`
  return `"${type}": failed validation`
}

/**
 * The reviewError to dispatch in place of a rejected terminal review message, so the run ends
 * visibly instead of spinning; `null` when the rejected message would not have ended a run.
 */
export function fallbackForRejectedHostMessage(value: unknown): IncomingMessage | null {
  if (!isRecord(value) || !TERMINAL_REVIEW_TYPES.has(value.type as string) || !isRequiredPrKey(value.prKey)) {
    return null
  }
  return parseIncomingMessage({
    protocolVersion: BRIDGE_PROTOCOL_VERSION,
    type: 'reviewError',
    prKey: value.prKey,
    message: REJECTED_REVIEW_MESSAGE,
  })
}
