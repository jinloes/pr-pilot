import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

type Rgb = [number, number, number]

function hslToRgb(value: string): Rgb {
  const [hue, saturation, lightness] = value.split(/\s+/).map(Number.parseFloat)
  const s = saturation / 100
  const l = lightness / 100
  const chroma = (1 - Math.abs(2 * l - 1)) * s
  const part = hue / 60
  const x = chroma * (1 - Math.abs((part % 2) - 1))
  const [r, g, b]: Rgb = part < 1 ? [chroma, x, 0]
    : part < 2 ? [x, chroma, 0]
      : part < 3 ? [0, chroma, x]
        : part < 4 ? [0, x, chroma]
          : part < 5 ? [x, 0, chroma]
            : [chroma, 0, x]
  const match = l - chroma / 2
  return [r + match, g + match, b + match]
}

function luminance([r, g, b]: Rgb): number {
  const linear = [r, g, b].map((channel) =>
    channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
  )
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrastRgb(first: Rgb, second: Rgb): number {
  const values = [luminance(first), luminance(second)].sort((a, b) => b - a)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

function contrast(first: string, second: string): number {
  return contrastRgb(hslToRgb(first), hslToRgb(second))
}

/** Source-over composites `top` at `alpha` over an opaque, possibly already composited, `base`. */
function compositeRgb(top: Rgb, alpha: number, base: Rgb): Rgb {
  return [0, 1, 2].map((i) => top[i] * alpha + base[i] * (1 - alpha)) as Rgb
}

/** Composites `overlay` at `alpha` over an opaque `base`, as `bg-token/10` renders. */
function composite(overlay: string, alpha: number, base: string | Rgb): Rgb {
  return compositeRgb(hslToRgb(overlay), alpha, typeof base === 'string' ? hslToRgb(base) : base)
}

function indexCss(): string {
  return readFileSync(new URL('../index.css', import.meta.url), 'utf8')
}

function themeToken(selector: RegExp, theme: string, name: string): string {
  const block = indexCss().match(selector)?.[1]
  const value = block?.match(new RegExp(`--${name}:\\s*([^;]+);`))?.[1].trim()
  if (!value) throw new Error(`Missing ${theme} --${name} token`)
  return value
}

function darkToken(name: string): string {
  return themeToken(/\.dark\s*\{([\s\S]*?)\n\s*\}/, 'dark', name)
}

function lightToken(name: string): string {
  return themeToken(/:root\s*\{([\s\S]*?)\n\s*\}/, 'light', name)
}

/**
 * Every settled surface on which the webview places red status text (`text-status-issue` or
 * `text-status-changes`): flat surfaces, `accent` highlights, `muted`/`primary` state tints, and the
 * token's own chip tints. A new state tint or token value must keep this whole matrix at 4.5:1.
 */
function redTextSurfaces(token: (name: string) => string, red: string): Array<[string, Rgb]> {
  const focusedRow = composite(token('primary'), 0.08, token('card'))
  return [
    ['background', hslToRgb(token('background'))],
    ['card', hslToRgb(token('card'))],
    ['popover', hslToRgb(token('popover'))],
    ['accent (menu highlight and hover)', hslToRgb(token('accent'))],
    ['secondary', hslToRgb(token('secondary'))],
    ['muted 20% over background', composite(token('muted'), 0.2, token('background'))],
    ['muted 45% over card (navigation hover)', composite(token('muted'), 0.45, token('card'))],
    ['primary 8% over card (focused finding row)', focusedRow],
    ['primary 12% over card (active navigation item)', composite(token('primary'), 0.12, token('card'))],
    ['its own 5% tint over card (blocker badge)', composite(red, 0.05, token('card'))],
    ['its own 10% tint over card (issue chip)', composite(red, 0.1, token('card'))],
    ['its own 10% tint over background (suggested verdict chip)', composite(red, 0.1, token('background'))],
    ['its own 10% tint over secondary (chat verify chip)', composite(red, 0.1, token('secondary'))],
    ['its own 10% tint over the focused finding row (focused major badge)', composite(red, 0.1, focusedRow)],
    ['its own 5% tint over the focused finding row (focused blocker badge)', composite(red, 0.05, focusedRow)],
    [
      'its own 10% tint over status-suggestion 5% over background (orphan badge)',
      composite(red, 0.1, composite(token('status-suggestion'), 0.05, token('background'))),
    ],
  ]
}

void test('dark primary tokens keep normal control labels and accent text at WCAG AA contrast', () => {
  const primary = darkToken('primary')
  const primaryForeground = darkToken('primary-foreground')
  const background = darkToken('background')

  assert.ok(contrast(primaryForeground, primary) >= 4.5)
  assert.ok(contrast(primary, background) >= 4.5)
})

for (const [theme, token] of [['light', lightToken], ['dark', darkToken]] as const) {
  void test(`${theme} destructive-meaning text on neutral surfaces keeps WCAG AA contrast`, () => {
    const statusIssue = token('status-issue')
    for (const surface of ['background', 'card', 'popover']) {
      assert.ok(
        contrast(statusIssue, token(surface)) >= 4.5,
        `${theme} status-issue on ${surface} is ${contrast(statusIssue, token(surface)).toFixed(2)}:1`,
      )
    }
  })

  void test(`${theme} chat error text stays readable on its tinted bubble`, () => {
    const bubble = composite(token('status-issue'), 0.1, token('card'))
    assert.ok(contrastRgb(hslToRgb(token('foreground')), bubble) >= 4.5)
  })

  for (const redToken of ['status-issue', 'status-changes']) {
    void test(`${theme} ${redToken} text keeps WCAG AA contrast on every settled surface it is placed on`, () => {
      const red = token(redToken)
      for (const [surface, background] of redTextSurfaces(token, red)) {
        const ratio = contrastRgb(hslToRgb(red), background)
        assert.ok(ratio >= 4.5, `${theme} ${redToken} on ${surface} is ${ratio.toFixed(2)}:1`)
      }
    })

    void test(`${theme} background text on a solid ${redToken} fill keeps WCAG AA contrast`, () => {
      const ratio = contrast(token('background'), token(redToken))
      assert.ok(ratio >= 4.5, `${theme} background on solid ${redToken} is ${ratio.toFixed(2)}:1`)
    })
  }
}

void test('high-contrast themes inherit the status tokens of their light or dark base', () => {
  const block = indexCss().match(/\.high-contrast\s*\{([\s\S]*?)\n\s*\}/)?.[1]
  assert.ok(block, 'Missing .high-contrast token block')
  assert.doesNotMatch(block, /--status-/)
})
