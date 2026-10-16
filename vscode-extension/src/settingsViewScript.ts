import { buildSettingsScriptEvents } from './settingsViewScriptEvents';
import { buildSettingsScriptHelpers } from './settingsViewScriptHelpers';

export function buildSettingsScript(baseUrlError: string, profileNameErrorSource: string): string {
    return buildSettingsScriptHelpers(baseUrlError, profileNameErrorSource) + buildSettingsScriptEvents();
}
