# BetterF public landing page

BTF-10 implements the selected Orbit design at `/`. Run with Node 22.12–22.x and npm 10:

```sh
npm ci
npm start
npm run typecheck
npm run build
```

No backend, authentication, API, or external font service is required. All primary actions link to the illustrative product preview; enterprise registration belongs to BTF-5. The preview is static illustration, including its search and sidebar labels. English copy lives in `src/i18n/en.json`. Shared styles and semantic color tokens live in `src/styles.css`; a light theme is not shipped.

Architecture baseline: betterf-architecture `ab3aa138af3f1b0d23d12661cc1c5849ab54a5ab`. The story branch starts at develop `4851bf4` and includes the frontend bootstrap because no application is committed on that base. Existing staged foundation work is not included.
