# Git Workflow

Rules for agents working inside the shared workspace repo.

- Commit only when asked or when the change is complete and verified.
- Write conventional commit messages: `feat:`, `fix:`, `docs:`, `refactor:`.
- Never force-push shared branches. Prefer squash/rebase locally on feature branches.
- Keep commits small and focused; one logical change per commit.
- Run tests/lint before committing; never commit build artifacts or secrets.
