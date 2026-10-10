// @find: eslint config, lint rules, react hooks rules, typescript lint, code style checks
// @what: Lint rules for the web app.
// @flow: Read by pnpm lint
import js from '@eslint/js'
import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended, reactHooks.configs.flat.recommended],
  },
  {
    files: ['*.js'],
    extends: [js.configs.recommended],
    languageOptions: { ecmaVersion: 'latest', sourceType: 'module' },
  },
)
