import type { IncomingMessage, LineComment, PR, ReviewResult } from './types'

export const BRIDGE_PROTOCOL_VERSION = 1 as const

const MAX_TEXT = 100_000
const MAX_DIFF = 1_000_000
const MAX_COMMENTS = 1_000
const MAX_PRS = 100

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isString(value: unknown, maxLength = MAX_TEXT): value is string {
  return typeof value === 'string' && value.length <= maxLength
}

function isOptionalString(value: unknown, maxLength = MAX_TEXT): boolean {
  return value === undefined || isString(value, maxLength)
}

function isPrKey(value: unknown): boolean {
  return value === undefined || (isString(value, 512) && /^[^/\s]+\/[^#\s]+#[1-9]\d*$/.test(value))
}


function isRequiredPrKey(value: unknown): boolean {
  return isString(value, 512) && /^[^/\s]+\/[^#\s]+#[1-9]\d*$/.test(value)
}

const PR_SCOPED_TYPES = new Set([
  'draftLoading', 'draftLoaded', 'reviewGenerating', 'reviewChunk', 'reviewResult',
  'reviewError', 'validationDiffUpdated', 'draftSaved', 'draftSaveError',
  'reviewSubmitted', 'reviewSubmitError', 'draftDeleted', 'draftDeleteError',
  'deepReviewPrepared',
])

function isLineComment(value: unknown): value is LineComment {
  if (!isRecord(value)) return false
  return isString(value.file, 4_096)
    && Number.isInteger(value.line)
    && (value.line as number) > 0
    && ['issue', 'suggestion', 'note'].includes(value.type as string)
    && isString(value.body)
    && (value.severity === undefined || ['blocker', 'major', 'minor', 'nit'].includes(value.severity as string))
    && (value.category === undefined || ['correctness', 'security', 'performance', 'tests', 'maintainability', 'compatibility', 'style'].includes(value.category as string))
    && (value.confidence === undefined || ['low', 'medium', 'high'].includes(value.confidence as string))
    && isOptionalString(value.rationale)
}

function isReviewResult(value: unknown): value is ReviewResult {
  if (!isRecord(value) || !Array.isArray(value.lineComments) || value.lineComments.length > MAX_COMMENTS) return false
  return isString(value.summary)
    && ['APPROVE', 'REQUEST_CHANGES', 'COMMENT'].includes(value.verdict as string)
    && value.lineComments.every(isLineComment)
}

function isPR(value: unknown): value is PR {
  if (!isRecord(value)) return false
  return Number.isInteger(value.number)
    && (value.number as number) > 0
    && isString(value.title)
    && isString(value.owner, 256)
    && isString(value.repo, 256)
    && isString(value.author, 256)
    && isString(value.createdAt, 128)
    && isString(value.htmlUrl, 4_096)
    && typeof value.isDraft === 'boolean'
    && typeof value.hasReviewDraft === 'boolean'
    && ['UNREVIEWED', 'REVIEWED', 'UPDATED_SINCE_REVIEW', 'UNAVAILABLE'].includes(value.reviewStatus as string)
}

function isListStatus(value: unknown): boolean {
  if (!isRecord(value)) return false
  return ['currentRepo', 'authored', 'assigned', 'reviewRequested'].includes(value.searchScope as string)
    && isOptionalString(value.currentRepo, 512)
    && Number.isInteger(value.resultLimit)
    && (value.resultLimit as number) > 0
    && (value.resultLimit as number) <= MAX_PRS
    && typeof value.limited === 'boolean'
    && typeof value.reviewStatusAvailable === 'boolean'
}

function isProviderReadiness(value: unknown): boolean {
  if (!isRecord(value)) return false
  return ['claude', 'copilot'].includes(value.provider as string)
    && typeof value.available === 'boolean'
    && isString(value.detail)
    && (value.binaryStatus === undefined || ['ready', 'missing'].includes(value.binaryStatus as string))
    && (value.authenticationStatus === undefined
      || ['ready', 'unavailable', 'unverified'].includes(value.authenticationStatus as string))
    && (value.authCommand === undefined || isString(value.authCommand))
}

function hasMessage(value: Record<string, unknown>): boolean {
  return isString(value.message)
}

export function parseIncomingMessage(value: unknown): IncomingMessage | null {
  if (!isRecord(value) || value.protocolVersion !== BRIDGE_PROTOCOL_VERSION || !isString(value.type, 64)) {
    return null
  }
  if (!isPrKey(value.prKey)) return null
  if (PR_SCOPED_TYPES.has(value.type) && !isRequiredPrKey(value.prKey)) return null

  let valid = false
  switch (value.type) {
    case 'prLoading':
    case 'draftLoading':
    case 'reviewSubmitted':
    case 'draftDeleted':
      valid = true
      break
    case 'prListLoaded':
      valid = Array.isArray(value.prs)
        && value.prs.length <= MAX_PRS
        && value.prs.every(isPR)
        && isOptionalString(value.defaultRepo, 512)
        && (value.listStatus === undefined || isListStatus(value.listStatus))
        && (value.providerReadiness === undefined || isProviderReadiness(value.providerReadiness))
        && (value.intellijAssistedEnabled === undefined || typeof value.intellijAssistedEnabled === 'boolean')
      break
    case 'draftLoaded':
      valid = ['NO_DRAFT', 'DRAFT_PRESENT', 'MERGED'].includes(value.prState as string)
        && isOptionalString(value.reviewId, 256)
        && (value.result === undefined || isReviewResult(value.result))
        && isOptionalString(value.diff, MAX_DIFF)
        && isOptionalString(value.validationDiff, MAX_DIFF)
        && (value.staleCommits === undefined || typeof value.staleCommits === 'boolean')
        && (value.importedFromGitHub === undefined || typeof value.importedFromGitHub === 'boolean')
        && (value.recoveryPending === undefined || typeof value.recoveryPending === 'boolean')
        && isOptionalString(value.status)
        && (value.providerReadiness === undefined || isProviderReadiness(value.providerReadiness))
      break
    case 'reviewGenerating':
    case 'reviewError':
    case 'reviewSubmitError':
    case 'draftDeleteError':
    case 'chatError':
      valid = hasMessage(value)
      break
    case 'deepReviewPrepared':
      valid = isString(value.operationId, 128) && !!value.operationId
        && isString(value.retainedId, 36) && /^[0-9a-f-]{36}$/.test(value.retainedId)
        && isString(value.worktree, 4096) && value.worktree.startsWith('/')
        && isString(value.head, 64) && /^[0-9a-f]{40}(?:[0-9a-f]{24})?$/.test(value.head)
        && Array.isArray(value.servers) && value.servers.length > 0 && value.servers.length <= 100
        && value.servers.every(s => isString(s, 255) && s.length > 0)
        && new Set(value.servers).size === value.servers.length && hasMessage(value)
      break
    case 'retainedDeepReviews':
      valid = isString(value.operationId, 128) && !!value.operationId
        && Array.isArray(value.retained) && value.retained.length <= 1000
        && value.retained.every(r => isRecord(r) && isString(r.id, 36) && /^[0-9a-f-]{36}$/.test(r.id)
          && isString(r.worktree, 4096) && r.worktree.startsWith('/')
          && isString(r.repository, 4096) && r.repository.startsWith('/')
          && isString(r.head, 64) && /^[0-9a-f]{40}(?:[0-9a-f]{24})?$/.test(r.head)
          && Number.isSafeInteger(r.createdAt) && (r.createdAt as number) > 0)
      break
    case 'deepReviewMaintenanceError':
      valid = isString(value.operationId, 128) && !!value.operationId && hasMessage(value)
      break
    case 'reviewChunk':
      valid = ['text', 'thinking'].includes(value.kind as string) && isString(value.chunk)
      break
    case 'reviewResult':
      valid = isReviewResult(value.result)
        && isString(value.diff, MAX_DIFF)
        && isOptionalString(value.validationDiff, MAX_DIFF)
      break
    case 'validationDiffUpdated':
      valid = isString(value.validationDiff, MAX_DIFF)
      break
    case 'draftSaved':
      valid = isString(value.reviewId, 256)
        && typeof value.commentsDropped === 'boolean'
        && Number.isSafeInteger(value.saveId)
        && (value.saveId as number) > 0
      break
    case 'draftSaveError':
      valid = hasMessage(value)
        && Number.isSafeInteger(value.saveId)
        && (value.saveId as number) > 0
      break
    case 'prDraftStatusUpdated':
      valid = Number.isInteger(value.number) && (value.number as number) > 0
        && isString(value.owner, 256) && isString(value.repo, 256)
        && typeof value.hasReviewDraft === 'boolean'
      break
    case 'activatePR':
      valid = isPR(value.pr) && (value.source === undefined || value.source === 'notification')
      break
    case 'chatChunk':
      valid = isString(value.chunk)
      break
    case 'chatResponse':
      valid = isString(value.response)
      break
    case 'setupRequired':
      valid = ['gh_not_installed', 'gh_not_authenticated', 'provider_not_installed', 'provider_not_authenticated', 'load_failed', 'draft_index_unavailable'].includes(value.reason as string)
        && isString(value.detail)
        && (value.providerReadiness === undefined || isProviderReadiness(value.providerReadiness))
      break
    case 'themeChanged':
      valid = ['light', 'dark', 'highContrastLight', 'highContrastDark'].includes(value.theme as string)
      break
  }
  return valid ? value as unknown as IncomingMessage : null
}
