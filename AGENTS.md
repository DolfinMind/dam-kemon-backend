# Understand-Anything workflow

- Use this repository's `.ua/knowledge-graph.json` as the default orientation map. Before broad source exploration, query its relevant nodes, edges, layers, and tour steps, then verify decisions against live source.
- Keep orientation token-efficient: query only the graph fields needed for the task and open only the source files identified by those results. Do not load the entire graph or scan the whole repository when a targeted graph query can answer the question.
- On first analysis, run `$understand . --full`. After substantive source or architecture changes, run `$understand .` for an incremental refresh.
- Before every Git commit that changes source, refresh the graph and include every resulting committable `.ua/` change in the same commit. Never create a source-only commit with stale or unstaged Understand data.
- Commit `.ua/knowledge-graph.json`, `.ua/meta.json`, `.ua/fingerprints.json`, `.ua/config.json`, `.ua/.understandignore`, and `.ua/domain-graph.json` when present.
- Never commit `.ua/intermediate/`, `.ua/diff-overlay.json`, `.ua/tmp/`, or `.ua/.trash-*/`.
- If the refresh fails, report the failure and do not silently commit stale graph data.
