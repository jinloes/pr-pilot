type AnyMessage = { type?: unknown } & Record<string, unknown>;

export const BRIDGE_PROTOCOL_VERSION = 1;
const MAX_TEXT = 100_000;
const MAX_REVIEW_DIFF = 1_100_000;
const MAX_COMMENTS = 1_000;

const MESSAGE_TYPES = new Set([
  'refreshPRs',
  'selectPR',
  'generateReview',
  'saveRepositoryInstructions',
  'continueDeepReview',
  'listDeepReviews',
  'cleanupDeepReview',
  'cancelReview',
  'saveDraft',
  'submitReview',
  'deleteDraft',
  'askClaude',
  'clearChat',
  'cancelChat',
  'openUrl',
  'openSettings',
  'runAuthLogin',
  'webviewLayoutChanged',
]);

function hasValidPrIdentity(msg: AnyMessage): boolean {
  return typeof msg.number === 'number'
    && Number.isInteger(msg.number)
    && msg.number > 0
    && typeof msg.owner === 'string'
    && msg.owner.trim().length > 0
    && msg.owner.length <= 256
    && typeof msg.repo === 'string'
    && msg.repo.trim().length > 0
    && msg.repo.length <= 256;
}

function isBoundedString(value: unknown, maxLength = MAX_TEXT): value is string {
  return typeof value === 'string' && value.length <= maxLength;
}

function isOperationId(value: unknown): value is string {
  return isBoundedString(value, 128)
    && value.trim().length > 0
    && [...value].every((character) => {
      const code = character.charCodeAt(0);
      return code > 31 && code !== 127;
    });
}

function isLineComment(value: unknown): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const comment = value as Record<string, unknown>;
  return isBoundedString(comment.file, 4_096)
    && Number.isInteger(comment.line) && (comment.line as number) > 0
    && ['issue', 'suggestion', 'note'].includes(comment.type as string)
    && isBoundedString(comment.body)
    && (comment.severity === undefined || ['blocker', 'major', 'minor', 'nit'].includes(comment.severity as string))
    && (comment.category === undefined || ['correctness', 'security', 'performance', 'tests', 'maintainability', 'compatibility', 'style'].includes(comment.category as string))
    && (comment.confidence === undefined || ['low', 'medium', 'high'].includes(comment.confidence as string))
    && (comment.rationale === undefined || isBoundedString(comment.rationale));
}

function isReviewResult(value: unknown): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const review = value as Record<string, unknown>;
  return isBoundedString(review.summary)
    && ['APPROVE', 'REQUEST_CHANGES', 'COMMENT'].includes(review.verdict as string)
    && Array.isArray(review.lineComments)
    && review.lineComments.length <= MAX_COMMENTS
    && review.lineComments.every(isLineComment);
}

export function isValidBridgeRequest(msg: AnyMessage | null | undefined): msg is AnyMessage {
  if (
    !msg
    || msg.protocolVersion !== BRIDGE_PROTOCOL_VERSION
    || typeof msg.type !== 'string'
    || !MESSAGE_TYPES.has(msg.type)
  ) {
    return false;
  }
  switch (msg.type) {
    case 'refreshPRs':
      return (msg.state === undefined || ['open', 'closed', 'all'].includes(msg.state as string))
        && (msg.searchScope === undefined
          || ['currentRepo', 'authored', 'assigned', 'reviewRequested'].includes(msg.searchScope as string))
        && (msg.assignedToMe === undefined || typeof msg.assignedToMe === 'boolean')
        && (msg.reviewRequested === undefined || typeof msg.reviewRequested === 'boolean');
    case 'cancelReview':
    case 'clearChat':
    case 'cancelChat':
    case 'openSettings':
    case 'runAuthLogin':
      return msg.type === 'openSettings' || msg.type === 'runAuthLogin' || isOperationId(msg.operationId);
    case 'openUrl':
      return isBoundedString(msg.url, 4_096);
    case 'webviewLayoutChanged':
      return isBoundedString(msg.reason, 4_096);
    case 'askClaude':
      return isOperationId(msg.operationId)
        && isBoundedString(msg.question)
        && (msg.context === undefined || isBoundedString(msg.context));
    case 'selectPR':
      return hasValidPrIdentity(msg);
    case 'generateReview':
      return isOperationId(msg.operationId)
        && hasValidPrIdentity(msg)
        && (msg.diff === undefined || isBoundedString(msg.diff, MAX_REVIEW_DIFF))
        && (msg.chunkedReview === undefined || typeof msg.chunkedReview === 'boolean')
        && (msg.intellijAssisted === undefined || typeof msg.intellijAssisted === 'boolean')
        && (msg.focusAreas === undefined || isBoundedString(msg.focusAreas, 10_000))
        && (msg.customInstructions === undefined || isBoundedString(msg.customInstructions, 20_000));
    case 'saveRepositoryInstructions':
      return hasValidPrIdentity(msg) && isBoundedString(msg.instructions, 10_000);
    case 'continueDeepReview':
      return hasValidPrIdentity(msg) && isOperationId(msg.operationId)
        && isOperationId(msg.retainedId) && isBoundedString(msg.server, 255) && !!msg.server;
    case 'listDeepReviews':
      return isOperationId(msg.operationId);
    case 'cleanupDeepReview':
      return isOperationId(msg.operationId) && isOperationId(msg.retainedId)
        && msg.projectClosed === true;
    case 'saveDraft':
      return hasValidPrIdentity(msg)
        && Number.isSafeInteger(msg.saveId) && (msg.saveId as number) > 0
        && (msg.result === undefined || isReviewResult(msg.result))
        && (msg.generatedResult === undefined || isReviewResult(msg.generatedResult))
        && (msg.orphans === undefined
          || (Array.isArray(msg.orphans) && msg.orphans.length <= MAX_COMMENTS && msg.orphans.every(isLineComment)));
    case 'submitReview':
      return hasValidPrIdentity(msg)
        && ['APPROVE', 'REQUEST_CHANGES', 'COMMENT'].includes(msg.verdict as string)
        && isBoundedString(msg.comment);
    case 'deleteDraft':
      return hasValidPrIdentity(msg);
    default:
      return false;
  }
}
