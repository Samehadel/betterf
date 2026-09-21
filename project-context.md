# Project context

This file maps project work to its business and architecture sources. Working instructions live in `AGENTS.md`; requirements and architecture remain in their authoritative sources.

## Project and paths

- Project name: `betterf`
- Application repository URL: ``
- Application checkout: `app/`
- Backend implementation: `app/backend/`
- Frontend implementation: `app/frontend/`

All local paths in this file are relative to the workspace folder where this file is exposed, alongside `AGENTS.md`, `architecture/`, and `app/`. If this file is a symlink, use that workspace folder, not the directory containing the symlink target.

## Business requirements

- Provider: `Linear` (replace if using another provider)
- Workspace or organization: `<workspace name or URL>`
- Project URL or identifier: `<business project URL or identifier>`
- Team or issue prefix: `<for example, STORE for STORE-123>`
- Sources: relevant issue descriptions, acceptance criteria, project documents, and linked specifications.
- Access method: `<available connector, CLI, or browser>`

Use the task's issue identifier or link to locate the relevant requirements within the configured project. A prefix helps locate issues; it does not replace the project identifier or define the task's scope. Follow linked sources needed to understand the requested behavior.

## Architecture

- Repository URL: `<architecture Git repository URL>`
- Local checkout: `architecture/`
- Remote name: `origin` (verify against the checkout)
- Tracking branch: `<architecture branch name>`
- Version policy: latest committed version on the configured tracking branch, unless the task specifies a tag or commit.
- Entry point: `architecture/README.md`
- Shared decisions: `architecture/decisions/`

### Backend

- Documentation directory: `architecture/backend/`
- Entry point: `architecture/backend/README.md`
- Scope: domain boundaries, services, APIs, authorization, persistence, integrations, and backend runtime behavior.

### Frontend

- Documentation directory: `architecture/frontend/`
- Entry point: `architecture/frontend/README.md`
- Scope: user journeys, routes, components, state, API consumption, accessibility, and frontend runtime behavior.

The backend and frontend entry points are intended locations; they may not exist during initial setup. Until populated, use relevant documents indexed by `architecture/README.md` and report material documentation gaps.

## Source handling

- Replace angle-bracket placeholders before use. Unfilled values are unknown, not valid configuration.
- Consult business sources for intended behavior, architecture for system design and accepted decisions, and implementation for current behavior and diagnostics.
- For work spanning backend and frontend, consult both architecture areas and relevant shared contracts or decisions.
- Follow `AGENTS.md` when checking architecture freshness; record the commit consulted and disclose unavailable remote access or relevant local changes.
- Identify material source conflicts instead of silently choosing an interpretation. Continue supported work and ask only for information needed to resolve blocked work.
- Reference credentials through the configured access mechanism; do not store tokens or passwords in this file.
