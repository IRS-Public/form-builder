// Lint config for the browser assets this library ships — `website-static/flow-runtime/js/**`.
//
// A deliberate near-copy of taxpert's: the flow runtime was taxpert's until it moved here, and it is
// the same kind of code (raw ESM custom elements, no build step, ADR-001), so holding it to a
// different standard would only mean the rules stopped being enforced on the half that moved.
//
// Smaller in one way: no React config, since the React adapters are taxpert's `react/` directory.
// The innerHTML restriction is kept, because the runtime's two `eslint-disable-next-line
// no-restricted-syntax` comments were written against it — dropping the rule would turn two
// deliberate, justified exceptions into dead comments and stop catching the next one.

import globals from 'globals'
import { defineConfig } from 'eslint/config'
import neostandard from 'neostandard'
import security from 'eslint-plugin-security'

export default defineConfig([
  ...neostandard(),
  security.configs.recommended,
  {
    // Scala output and the Ivy/sbt working directories, none of which is ours to lint.
    ignores: ['target/**', 'project/target/**'],
  },
  {
    languageOptions: {
      globals: {
        ...globals.browser,
      },
      parserOptions: {
        ecmaVersion: 2022,
        sourceType: 'module',
      },
    },
    rules: {
      'no-eval': 'error',
      'no-new-func': 'error',
      'no-implied-eval': 'error',
      'no-implicit-globals': 'error',
      eqeqeq: 'error',
    },
  },
  {
    // The flow runtime's markup comes from the Thymeleaf node templates in
    // `resources/formative/templates/nodes/`, rendered server-side, so a client-side HTML string is
    // the exception here rather than the default. The two that exist are data-derived and each
    // carries an inline disable naming why:
    //   • fg-display.js — a list of formatted fact values, with no fixed markup to hold
    //   • modals.js — moving the flow's own already-parsed content inside a generated <a>
    files: ['src/main/resources/formative/website-static/**/js/*.js'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "AssignmentExpression > MemberExpression[property.name='innerHTML'], AssignmentExpression > MemberExpression[property.name='outerHTML']",
          message:
            'Render the markup from a Thymeleaf node template instead, or build nodes. If the output is genuinely data-derived, disable this rule on the line with a reason.',
        },
        {
          selector: "CallExpression > MemberExpression[property.name='insertAdjacentHTML']",
          message:
            'Render the markup from a Thymeleaf node template instead, or append nodes. For a host-supplied HTML string, parse it with DOMParser.',
        },
      ],
    },
  },
  {
    files: ['tests/**/*.mjs'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
])
