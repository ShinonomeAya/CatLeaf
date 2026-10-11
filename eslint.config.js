import js from "@eslint/js";
import tseslint from "typescript-eslint";
import globals from "globals";

export default tseslint.config(
  // Global ignores
  {
    ignores: [
      "dist/",
      "node_modules/",
      ".kilo/",
      ".playwright-mcp/",
      ".wrangler/",
      "drizzle/",
      "test-*.js",      // ad-hoc test scripts
      "scripts/",       // one-off Node.js scripts
      "android-native/tools/", // one-off SVG conversion helper, not app or catalogue source
      "docs/assets/tools/", // CatLeaf SVG 素材静态校验脚本，与 scripts/ 同类
      "docs/validation/", // 历史证据、源码快照和一次性取证脚本，不作为产品源码 lint
      "docs/prototypes/**/browser-check.js", // 浏览器 REPL 取证片段
      "android-native/**/build/",
      "android-native/.gradle/",
      "android-native/app/src/main/assets/",
    ],
  },

  // Base recommended rules
  js.configs.recommended,

  // TypeScript recommended (type-aware rules disabled for speed)
  ...tseslint.configs.recommended,

  // Global settings for all source files
  {
    files: ["**/*.{ts,js}"],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.es2022,
      },
    },
    rules: {
      // Relax rules for existing codebase
      "@typescript-eslint/no-unused-vars": [
        "warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "@typescript-eslint/no-explicit-any": "warn",
      "no-empty": ["warn", { allowEmptyCatch: true }],
    },
  },

  // Node/Cloudflare backend files
  {
    files: ["server/**/*.ts", "functions/**/*.ts"],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },

  // 后端可重跑检查（Node 脚本，非浏览器代码）
  {
    files: ["tests/**/*.{mjs,js,ts}", "tools/catalog/**/*.{mjs,js,ts}"],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },

  // Config files (CJS)
  {
    files: ["*.config.{js,ts}", "drizzle.*.ts"],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
    rules: {
      "@typescript-eslint/no-require-imports": "off",
    },
  }
);
