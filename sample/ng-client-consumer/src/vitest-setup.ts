// Partially-compiled Angular libraries (APF) fall back to JIT in tests. This
// setup file runs before any test module so the compiler is registered before
// @angular/common's module initializers evaluate partial declarations.
import '@angular/compiler';
