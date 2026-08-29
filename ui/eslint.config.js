// @ts-check
const tseslint = require('typescript-eslint');

/**
 * Flat config, migrated from `.eslintrc.json` for eslint 9.
 *
 * The rule set is carried over unchanged and deliberately so — this change is
 * the migration, not a re-opinionating of the lint. `typescript-eslint`'s
 * recommended presets are *not* enabled here for the same reason: turning on
 * a few hundred new rules in the same commit that moves the config format
 * would make it impossible to tell a migration break from a new finding.
 */
module.exports = tseslint.config(
  {
    // replaces `ignorePatterns`; in flat config a bare `ignores` block is global
    ignores: ['build/**', 'node_modules/**', 'out-tsc/**', '.angular/**']
  },
  {
    files: ['src/**/*.ts'],
    languageOptions: {
      parser: tseslint.parser,
      parserOptions: {
        // `createDefaultProgram` is gone in v8; `projectService` is its
        // replacement and also covers files the tsconfigs do not list
        projectService: true,
        tsconfigRootDir: __dirname
      }
    },
    plugins: {
      '@typescript-eslint': tseslint.plugin
    },
    rules: {
      'arrow-body-style': 'error',
      'curly': 'error',
      'eqeqeq': ['error', 'always', {null: 'ignore'}],
      'no-console': ['warn', {allow: ['warn', 'error']}],
      'no-debugger': 'error',
      'no-eval': 'error',
      'no-var': 'error',
      'prefer-const': 'error',
      'semi': ['error', 'always'],
      'quotes': ['error', 'single'],
      'max-len': ['warn', {code: 140}],
      '@typescript-eslint/no-unused-expressions': 'error',
      '@typescript-eslint/no-shadow': 'error'
    }
  }
);
