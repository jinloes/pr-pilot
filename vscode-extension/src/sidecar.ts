export * from './sidecarTypes';
export * from './sidecarProtocol';
export { encodeFrame, extractFrames } from './sidecarFraming';
export { SidecarTransport } from './sidecarTransport';
export { SidecarGitHubClient } from './sidecarGitHubClient';
export { SidecarReviewClient } from './sidecarReviewClient';

import { SidecarReviewClient } from './sidecarReviewClient';

/** Public JSON-RPC client facade. Methods are implemented in engine-specific base classes. */
export class SidecarClient extends SidecarReviewClient {}

export { resolveSidecarJarPath } from './sidecarJar';
