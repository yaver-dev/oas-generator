import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/specs/**/*.spec.ts'],
    setupFiles: ['src/vitest-setup.ts'],
  },
  resolve: {
    // The generated library resolves through the installed package (file: or
    // tarball). No source-level aliases are used anywhere.
  },
});
