import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import globals from 'globals'

export default tseslint.config(
  {
    ignores: [
      'dist',
      'node_modules',
      'src/components/ui/**',
      // Root config files — Node land, not part of the src TS project.
      'vite.config.ts',
      'postcss.config.js',
      'tailwind.config.js',
      'eslint.config.js',
    ],
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommendedTypeChecked,
    ],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: {
        project: ['./tsconfig.json'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,

      // Classic Rules-of-Hooks bug catchers — keep strict.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',

      // react-hooks v7 added stricter hygiene rules that flag many pre-existing
      // patterns in this codebase. Downgrade to warn so the strict checks above
      // still error out; clean these up incrementally.
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/immutability': 'warn',
      'react-hooks/error-boundaries': 'warn',
      'react-hooks/refs': 'warn',

      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],

      // The bridge intentionally uses untyped `any`/index signatures because
      // messages cross the JS↔Java boundary as JSON. Tighten case-by-case.
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',
      '@typescript-eslint/no-unsafe-return': 'off',

      // tsc with noUnusedLocals already enforces this; let it own the check.
      '@typescript-eslint/no-unused-vars': 'off',
    },
  },
)
