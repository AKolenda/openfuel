# What consolidation does not solve

| Area | Current input source | Follow-on work |
| --- | --- | --- |
| Android/API | Compose uses six local brand-reference samples | Implement a typed API adapter and tests before enabling live reads/writes |
| iOS visual interface | Earlier SwiftUI/API list/detail starter | Port approved map-first layout with actual simulator screenshots |
| Shared fixture | Swift/API fixture canonical; Android samples differ | Deliberately map or unify data and time/grade semantics, not copy values blindly |
| Station contributions | Registry policy model + local prototype suggestions | Authenticated review API, evidence checks, source licensing, abuse controls |
| Database | Executable SQLite example + unexecuted PostGIS draft | Apply/test migrations, grants/RLS and transactional projection worker |
| Native builds | Core tests execute; no full platform builds here | Run prepared Android/iOS workflows in configured runners, then device tests |
| Release | Source archive, no remote or store identity | Create remote, assign maintainers, review legal/privacy/asset terms and signing |

One repository keeps these changes reviewable together. It does not close them just by moving files.
