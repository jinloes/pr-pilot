import type * as vscode from 'vscode';
import type { ChatMessage } from './claude';
import type { PreparedDeepReview } from './deepReview';
import type { DraftRecoveryStore } from './draftRecovery';
import type { SidecarClient, SidecarPrDetailResult } from './sidecar';
import type { ResolvedReviewGuidance } from './reviewGuidanceProfiles';
import type { DeepReviewFlow } from './deepReview';
import type { PRSearchScope, ReviewResult } from './models';

export type Provider = 'claude' | 'copilot';

export interface ActivePR {
    number: number;
    owner: string;
    repo: string;
    title: string;
    body: string;
}

export interface ProviderReadiness {
    provider: Provider;
    available: boolean;
    detail: string;
    binaryStatus: 'ready' | 'missing';
    authenticationStatus: 'ready' | 'unavailable' | 'unverified';
    authCommand: string;
}

export interface ReviewGenerationSettings {
    provider: Provider;
    model: string;
    effort: string;
    inheritMcp: boolean;
    configDir: string;
    selfCritique: boolean;
    supervisorEnabled: boolean;
    secondReviewerModel: string;
    rulesDirectory: string;
    githubBaseUrl: string;
    guidance: ResolvedReviewGuidance;
}

export interface ViewState {
    webview: vscode.Webview;
    prStateFilter: string;
    searchScope: PRSearchScope;
    activePR: ActivePR | null;
    activeDiff: string;
    activeValidationDiff: string;
    activeReviewResult: ReviewResult | null;
    pendingReviewId: string | null;
    pendingReviewKey: string | null;
    selectionRevision: number;
    refreshRevision: number;
    generationRevision: number;
    chatRevision: number;
    activeProviderOperation: { kind: 'review' | 'chat'; revision: number; operationId: string } | null;
    generatedReviews: Map<string, {
        result: ReviewResult;
        editedResult: ReviewResult | null;
        attribution: { provider: Provider; model: string; reviewSupervisorEnabled: boolean };
    }>;
    mutationQueue: Promise<void>;
    chatHistory: Map<string, ChatMessage[]>;
    worktreeDir: string | null;
    gitRoot: string | null;
    worktreeKey: string | null;
    worktreeEpoch: number;
    worktreeCreation: { key: string; promise: Promise<string> } | null;
    disposed: boolean;
    deepReview: DeepReviewFlow;
    draftRecoveryStore: DraftRecoveryStore;
}

export interface HandlerDependencies {
    client: () => SidecarClient;
    config: () => vscode.WorkspaceConfiguration;
    githubBaseUrl: () => string;
    provider: () => Provider;
    workingDir: () => string;
    providerReadiness: () => ProviderReadiness;
    providerSetupReadiness: () => Promise<ProviderReadiness>;
    intellijAssistedEnabled: () => boolean;
    rememberedRepositoryInstructions: (owner: string, repo: string) => string;
    snapshotReviewGenerationSettings: () => ReviewGenerationSettings;
    formatPriorReview: (result: ReviewResult | null) => string;
    prKey: (pr: ActivePR | null) => string | null;
    prKeyFromParts: (number: number, owner: string, repo: string) => string;
    push: (state: ViewState, message: object) => void;
    enqueueMutation: (state: ViewState, action: () => Promise<void>) => Promise<void>;
    clearWorktree: (state: ViewState) => void;
    resolveWorkingDir: (
        state: ViewState,
        pr: ActivePR,
        emitStatus: boolean,
        bridgePrKey?: string,
    ) => Promise<string>;
    cancelActiveProvider: (operationId: string) => Promise<void>;
    freshDeepPr: (
        number: number,
        owner: string,
        repo: string,
    ) => Promise<{
        detail: NonNullable<SidecarPrDetailResult['detail']>;
        diff: string;
    }>;
    handleGenerateReview: (
        state: ViewState,
        msg: Record<string, unknown>,
        deep?: { pending: PreparedDeepReview; server: string },
    ) => Promise<void>;
}
