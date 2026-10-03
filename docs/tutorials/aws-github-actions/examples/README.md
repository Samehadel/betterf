# Learning snapshots

These files mirror the current local deployment configuration, including the
versioning improvement following first deployment commit `d7157b7`. The version
check requires the root `VERSION` and `scripts/version*.mjs` files as well.
The active workflow is at the repository root in `.github/workflows/ci.yml`;
its inputs are in `deploy/aws/`. GitHub does not execute this directory.

Use the linked live files in the tutorial when making changes. The policies
contain our account and repository identifiers, not portable defaults. No
passwords or tokens are included. Refresh these snapshots deliberately when
updating the course so they do not drift from its described baseline.
