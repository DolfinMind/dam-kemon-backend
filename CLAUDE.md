## gstack (REQUIRED — global install)

**Before doing ANY work, verify gstack is installed:**

```bash
test -d ~/.claude/skills/gstack/bin && echo "GSTACK_OK" || echo "GSTACK_MISSING"
```

If GSTACK_MISSING: STOP. Do not proceed. Tell the user:

> gstack is required for all AI-assisted work in this repo.
> Install it:
> ```bash
> git clone --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack
> cd ~/.claude/skills/gstack && ./setup --team
> ```
> Then restart your AI coding tool.

Do not skip skills, ignore gstack errors, or work around missing gstack.

Using gstack skills: After install, skills like /qa, /ship, /review, /investigate,
and /browse are available. Use /browse for all web browsing.
Use ~/.claude/skills/gstack/... for gstack file paths (the global path).

## graphify (REQUIRED — always on)

This repo has a committed knowledge graph at `graphify-out/` (god nodes, community structure, cross-file edges). A post-commit git hook auto-rebuilds it on every commit — never remove or bypass that hook.

STRICT rules, always, no exceptions:
- Before answering ANY codebase question, run `graphify query "<question>"` first (`graphify-out/graph.json` is committed). Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for one concept. Do not grep or browse source until the graph has been consulted.
- If `graphify-out/wiki/index.md` exists, use it for broad navigation instead of raw source browsing.
- Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review, when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## ponytail (REQUIRED — always on)

Default to the laziest solution that actually works: YAGNI first, stdlib and native platform features before dependencies, one line before fifty, deletion over addition, shortest working diff. No speculative abstractions, no boilerplate "for later". Mark deliberate shortcuts with a `ponytail:` comment naming the ceiling and the upgrade path. Never simplify away input validation at trust boundaries, error handling that prevents data loss, security, or accessibility.
