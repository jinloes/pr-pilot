import { describe, expect, it } from 'vitest'
import type { LineComment } from '@/bridge/types'
import {
  buildDiffFileNavItems,
  buildDiffFileTree,
  findActiveFileIndex,
  middleTruncateFileName,
} from './fileNavigation'

const comments: LineComment[] = [
  { file: 'src/auth.ts', line: 2, type: 'issue', body: 'Check the auth branch.' },
  { file: 'src/ui/button.tsx', line: 4, type: 'suggestion', body: 'Rename this prop.' },
]

const files = [
  { oldPath: 'src/auth.ts', newPath: 'src/auth.ts', type: 'modify' },
  { oldPath: 'src/ui/button.tsx', newPath: 'src/ui/button.tsx', type: 'rename' },
  { oldPath: 'docs/old.md', newPath: '/dev/null', type: 'delete' },
]

describe('buildDiffFileTree', () => {
  it('groups files by path segments and keeps comment counts/status metadata', () => {
    const items = buildDiffFileNavItems(files, comments)
    const tree = buildDiffFileTree(items)

    expect(items.map((item) => item.displayPath)).toEqual([
      'src/auth.ts',
      'src/ui/button.tsx',
      'docs/old.md',
    ])
    expect(items.map((item) => item.commentCount)).toEqual([1, 1, 0])

    expect(tree.map((node) => node.name)).toEqual(['docs', 'src'])
    expect(tree[0].name).toBe('docs')
    expect(tree[0].children[0].file?.displayPath).toBe('docs/old.md')
    expect(tree[1].name).toBe('src')
    expect(tree[1].children.map((node) => node.name)).toEqual(['ui', 'auth.ts'])
    expect(tree[1].children[0].children[0].file?.status).toBe('rename')

    const deepItems = buildDiffFileNavItems(
      [{ oldPath: 'example-grpc-api/src/main/proto/com/example/widgets/grpc/api/crm/decorated.proto', newPath: 'example-grpc-api/src/main/proto/com/example/widgets/grpc/api/crm/decorated.proto', type: 'modify' }],
      [],
    )
    const deepTree = buildDiffFileTree(deepItems)
    expect(deepTree[0].name).toContain('/')
    expect(deepTree[0].name).toContain('example-grpc-api')
    expect(deepTree[0].children[0].file?.displayPath).toContain('decorated.proto')
  })
})

describe('findActiveFileIndex', () => {
  it('defaults to the first file when the next section has not reached the sticky threshold', () => {
    expect(findActiveFileIndex([120, 420, 760], 72)).toBe(0)
  })

  it('switches to the last file that has crossed the sticky threshold', () => {
    expect(findActiveFileIndex([-240, 48, 300], 72)).toBe(1)
    expect(findActiveFileIndex([-420, -60, 28], 72)).toBe(2)
  })

  it('ignores non-rendered sections when computing the active file', () => {
    expect(findActiveFileIndex([Number.POSITIVE_INFINITY, -16, 180], 72)).toBe(1)
  })
})

describe('middleTruncateFileName', () => {
  it('keeps short names intact and preserves long-name extensions and distinguishing suffixes', () => {
    expect(middleTruncateFileName('AuthService.java')).toBe('AuthService.java')
    expect(middleTruncateFileName('SharedGeneratedAuthenticationAuthorizationService.java', 32))
      .toBe('SharedGe...orizationService.java')
    expect(middleTruncateFileName('LongName.ts', 10)).toBe('Long....ts')
  })
})
