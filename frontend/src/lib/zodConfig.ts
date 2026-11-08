import { z } from 'zod'

/*
 * Zod v4 probes `new Function` once to decide whether it may JIT-compile schemas. Our Content-Security-Policy
 * forbids eval, so the browser reports that probe as a CSP violation even though Zod catches the error.
 * jitless mode skips the probe. It must run before any schema is created, so main.tsx imports this module first.
 */
z.config({ jitless: true })
