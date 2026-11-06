/**
 * Host-side diagnostic log. The extension routes it to the "PR Pilot" output channel so sidecar
 * stderr and the raw cause behind each user-facing error survive after the UI shows its summary.
 * Pure (no `vscode` import) so transport and error mapping stay testable.
 */

export type LogSink = (line: string) => void;

let sink: LogSink = () => {};

/** Installs the destination for log lines; returns the previous sink so tests can restore it. */
export function setLogSink(next: LogSink): LogSink {
    const previous = sink;
    sink = next;
    return previous;
}

const TOKEN_PATTERN = /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g;
const BEARER_PATTERN = /(\bBearer\s+)[A-Za-z0-9._~+/=-]{8,}/gi;

/** Masks GitHub tokens and bearer credentials so a log line never carries one. */
export function redact(text: string): string {
    return text.replace(TOKEN_PATTERN, '<redacted>').replace(BEARER_PATTERN, '$1<redacted>');
}

export function log(line: string): void {
    try {
        sink(redact(line));
    } catch {
        // Logging must never break the operation being logged.
    }
}

/** Splits streamed output into whole lines, carrying a trailing partial line to the next chunk. */
export function createLineLogger(prefix: string): { write(chunk: string): void; flush(): void } {
    let pending = '';
    return {
        write(chunk: string) {
            pending += chunk;
            const lines = pending.split(/\r?\n/);
            pending = lines.pop() ?? '';
            for (const line of lines) if (line.trim()) log(`${prefix}${line}`);
        },
        flush() {
            if (pending.trim()) log(`${prefix}${pending}`);
            pending = '';
        },
    };
}
