import * as vscode from 'vscode';

/** Kept for backwards compatibility; model selection now lives in PR Pilot Settings. */
export async function selectCopilotModel(): Promise<void> {
    await vscode.commands.executeCommand('pr-pilot.openSettings');
}
