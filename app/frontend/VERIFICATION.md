# BTF-10 verification

Verified 24 September 2026 against the Orbit HTML/CSS attachment on BTF-10 (byte-identical to the local design reference).

- TypeScript strict type checking: passed.
- Angular production build: passed; within configured bundle budgets.
- Chromium desktop and mobile visual review: passed.
- Viewport widths 320, 390, 768, and 1440 pixels: no horizontal overflow.
- First keyboard focus reaches the visible skip link; Enter transfers focus to main content.
- Every anchor targets an existing section; “Explore the vision” reaches `#workflow`.
- Reduced-motion preference disables smooth scrolling.
- Preview explicitly identifies its content as illustrative; there are no live form controls or registration links.
- No browser errors after favicon correction. The page requires no backend or login.

Reproduce build checks with the commands in README.md. For browser checks, run the local server, test each viewport above, tab from a fresh page load, activate the skip link and primary action, and inspect all page sections. No Firefox or Safari validation was performed.

Architecture baseline consulted: `ab3aa138af3f1b0d23d12661cc1c5849ab54a5ab`.
