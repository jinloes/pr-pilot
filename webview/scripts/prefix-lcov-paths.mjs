import { readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const files = process.argv.slice(2)
if (files.length === 0) {
  throw new Error('Usage: node scripts/prefix-lcov-paths.mjs <lcov.info> [...]')
}

for (const file of files) {
  const lcovPath = path.resolve(file)
  const updated = readFileSync(lcovPath, 'utf8').replace(/^SF:(.+)$/gm, (_, source) => {
    const normalized = source.replaceAll('\\', '/')
    if (path.isAbsolute(source) || normalized.startsWith('webview/')) {
      return `SF:${normalized}`
    }
    return `SF:webview/${normalized}`
  })
  writeFileSync(lcovPath, updated)
}
