export interface ChatMessage {
    role: 'USER' | 'ASSISTANT';
    content: string;
}

// Review/chat prompts and CLI binary/auth probing live in review-engine and are reached over the
// sidecar (`reviews/generate`, `reviews/chat`, `reviews/probeProviderSetup`); this module holds
// only shared host chat types (AGENTS.md modularity guardrail #5).
