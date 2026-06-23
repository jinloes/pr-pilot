export function hasStaleCommits(savedCommitId: string | null | undefined, currentHeadSha: string | null | undefined): boolean {
    const saved = savedCommitId?.trim() ?? '';
    const current = currentHeadSha?.trim() ?? '';
    if (!saved || !current) {
        return false;
    }
    return saved !== current;
}

