type AnyMessage = { type?: unknown } & Record<string, unknown>;

const MESSAGE_TYPES = new Set([
  'refreshPRs',
  'selectPR',
  'generateReview',
  'cancelReview',
  'saveDraft',
  'submitReview',
  'deleteDraft',
  'askClaude',
  'clearChat',
  'openUrl',
  'openSettings',
]);

function hasValidPrIdentity(msg: AnyMessage): boolean {
  return typeof msg.number === 'number'
    && Number.isInteger(msg.number)
    && msg.number > 0
    && typeof msg.owner === 'string'
    && msg.owner.trim().length > 0
    && typeof msg.repo === 'string'
    && msg.repo.trim().length > 0;
}

export function isValidBridgeRequest(msg: AnyMessage | null | undefined): msg is AnyMessage {
  if (!msg || typeof msg.type !== 'string' || !MESSAGE_TYPES.has(msg.type)) {
    return false;
  }
  switch (msg.type) {
    case 'refreshPRs':
    case 'cancelReview':
    case 'clearChat':
    case 'openSettings':
      return true;
    case 'openUrl':
      return typeof msg.url === 'string';
    case 'askClaude':
      return typeof msg.question === 'string';
    case 'selectPR':
    case 'generateReview':
    case 'saveDraft':
    case 'submitReview':
    case 'deleteDraft':
      return hasValidPrIdentity(msg);
    default:
      return false;
  }
}

