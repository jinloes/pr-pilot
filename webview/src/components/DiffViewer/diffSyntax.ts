import hljs from 'highlight.js/lib/core'
import hljsBash from 'highlight.js/lib/languages/bash'
import hljsCss from 'highlight.js/lib/languages/css'
import hljsGo from 'highlight.js/lib/languages/go'
import hljsJava from 'highlight.js/lib/languages/java'
import hljsJson from 'highlight.js/lib/languages/json'
import hljsJs from 'highlight.js/lib/languages/javascript'
import hljsKotlin from 'highlight.js/lib/languages/kotlin'
import hljsProto from 'highlight.js/lib/languages/protobuf'
import hljsPython from 'highlight.js/lib/languages/python'
import hljsRust from 'highlight.js/lib/languages/rust'
import hljsSql from 'highlight.js/lib/languages/sql'
import hljsTs from 'highlight.js/lib/languages/typescript'
import hljsXml from 'highlight.js/lib/languages/xml'
import hljsYaml from 'highlight.js/lib/languages/yaml'

hljs.registerLanguage('bash', hljsBash)
hljs.registerLanguage('css', hljsCss)
hljs.registerLanguage('go', hljsGo)
hljs.registerLanguage('java', hljsJava)
hljs.registerLanguage('json', hljsJson)
hljs.registerLanguage('javascript', hljsJs)
hljs.registerLanguage('kotlin', hljsKotlin)
hljs.registerLanguage('protobuf', hljsProto)
hljs.registerLanguage('python', hljsPython)
hljs.registerLanguage('rust', hljsRust)
hljs.registerLanguage('sql', hljsSql)
hljs.registerLanguage('typescript', hljsTs)
hljs.registerLanguage('xml', hljsXml)
hljs.registerLanguage('yaml', hljsYaml)

const EXT_LANG: Record<string, string> = {
  bash: 'bash', sh: 'bash', zsh: 'bash',
  css: 'css', scss: 'css', less: 'css',
  go: 'go',
  html: 'xml', htm: 'xml', svg: 'xml', xml: 'xml',
  java: 'java',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript',
  json: 'json',
  kt: 'kotlin', kts: 'kotlin',
  proto: 'protobuf',
  py: 'python',
  rs: 'rust',
  sql: 'sql',
  ts: 'typescript', tsx: 'typescript',
  yaml: 'yaml', yml: 'yaml',
}

export function syntaxHighlight(code: string, filePath: string): string {
  const ext = filePath.split('.').pop()?.toLowerCase() ?? ''
  const lang = EXT_LANG[ext]
  if (!lang) return escapeHtml(code)
  return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
}

export function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
