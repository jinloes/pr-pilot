// Header of the collapsed block that "Add patch to comment" appends. Re-attaching replaces the block
// from its last occurrence, so the header must stay byte-identical across releases.
const PATCH_BLOCK_HEADER = '<details>\n<summary>Suggested patch</summary>\n'

/**
 * Every draft comment body is also base64-encoded into the draft review's own body, which GitHub
 * caps at 65,536 characters, so one attached patch must leave room for the rest of the draft.
 */
export const MAX_ATTACHED_PATCH_CHARS = 12_000

function unfence(patch: string): { language: string; code: string } {
  const trimmed = patch.trim()
  const fenced = /^(`{3,}|~{3,})([^\n]*)\n([\s\S]*?)\n?\1\s*$/.exec(trimmed)
  const code = fenced ? fenced[3] : trimmed
  const tag = fenced ? fenced[2].trim() : ''
  const language = tag || (/^(?:---|\+\+\+|@@)/m.test(code) ? 'diff' : '')
  return { language, code }
}

function longestBacktickRun(text: string): number {
  return Math.max(0, ...(text.match(/`+/g) ?? []).map((run) => run.length))
}

/**
 * Separates a patch block appended by {@link withAttachedPatch} from the rest of the comment, so
 * the webview can render it as a real `<details>` element; its Markdown renderer drops raw HTML.
 * `patch` is the fenced code block inside the block, or null when none is attached.
 */
export function splitAttachedPatch(body: string): { text: string; patch: string | null } {
  const start = body.lastIndexOf(PATCH_BLOCK_HEADER)
  if (start < 0) return { text: body, patch: null }
  const rest = body.slice(start + PATCH_BLOCK_HEADER.length)
  const end = rest.lastIndexOf('\n</details>')
  return {
    text: body.slice(0, start).trimEnd(),
    patch: (end < 0 ? rest : rest.slice(0, end)).trim(),
  }
}

/** Removes a patch block previously appended by {@link withAttachedPatch}. */
export function stripAttachedPatch(body: string): string {
  return splitAttachedPatch(body).text
}

/** Why a patch cannot be attached, or null when it can. */
export function attachPatchBlocker(examplePatch: string | null): 'empty' | 'tooLarge' | null {
  if (!examplePatch) return 'empty'
  const { code } = unfence(examplePatch)
  if (!code.trim()) return 'empty'
  return code.length > MAX_ATTACHED_PATCH_CHARS ? 'tooLarge' : null
}

/**
 * Appends an AI example patch to a draft comment body as a collapsed `<details>` block, replacing
 * any patch attached earlier. GitHub suggestion blocks can only replace the anchored line range, so
 * multi-hunk patches travel as a readable diff instead. Returns null when the patch is empty or
 * over {@link MAX_ATTACHED_PATCH_CHARS}.
 */
export function withAttachedPatch(body: string, examplePatch: string): string | null {
  if (attachPatchBlocker(examplePatch) !== null) return null
  const { language, code } = unfence(examplePatch)
  // The fence must outrun any backtick run inside the patch or the code block would end early.
  const fence = '`'.repeat(Math.max(3, longestBacktickRun(code) + 1))
  const block = [PATCH_BLOCK_HEADER, fence + language, code, fence, '', '</details>'].join('\n')
  const base = stripAttachedPatch(body).trimEnd()
  return base ? `${base}\n\n${block}` : block
}
