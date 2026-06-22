# FinanceStreamAI / StockWizAI — Documentation

Living documentation for the FinanceStreamAI backend and StockWizAI Android client.

| Document | What's inside |
| --- | --- |
| [architecture.md](architecture.md) | High-level system architecture (backend, Android, Firestore, schedulers, cron) |
| [features.md](features.md) | Full feature catalogue, by area |
| [recommendation-rules.md](recommendation-rules.md) | Exact rules, thresholds and guards used to recommend stock and option trades |
| [api-reference.md](api-reference.md) | All HTTP endpoints exposed by the backend |
| [CHANGELOG.md](CHANGELOG.md) | Per-release summary of behaviour changes |

## Repositories

| Repo | Path | Remote |
| --- | --- | --- |
| Backend (FastAPI) | `C:\Users\jpgudipa\Downloads\FinanceStreamAI_Backend` | `https://github.com/prakash421/FinanceStreamAI_Backend.git` |
| Android client (Kotlin / Compose) | `C:\Users\jpgudipa\AndroidStudioProjects\FinanceStreamAI` | `https://github.com/prakash421/StockWizAI.git` |

## Convention for future updates

When a feature is added or a rule changes:

1. Add an entry to [CHANGELOG.md](CHANGELOG.md) under an **Unreleased** section (date once shipped).
2. If the change introduces or modifies a rule/threshold, update [recommendation-rules.md](recommendation-rules.md).
3. If it adds a new endpoint, update [api-reference.md](api-reference.md).
4. If it touches a new component or data flow, update [architecture.md](architecture.md).
5. If it's a user-visible capability, add or update the entry in [features.md](features.md).

The legacy HTML drafts (`architecture.html`, `web-app-plan.html`, `google-signin-plan.html`) remain in this folder for reference, but markdown is the source of truth going forward.
