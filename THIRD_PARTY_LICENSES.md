# Third-party licenses

| Component | Version | Source revision | License | Usage |
|---|---:|---|---|---|
| Flowable Engine | 8.0.0 | Maven release | Apache-2.0 | Workflow execution engine |
| Spring Boot | 4.0.2 | Maven release | Apache-2.0 | Server application framework |
| Vben Admin | 5.7.0 | `63a38dce49ba109f61607994e21ba921d8e970e9` | MIT | Generated PC engineering workspace and `web-ele` application |
| Unibest | 4.4.1 | `f05992eb9897158cb9c8031efd1ff8ca8db50403` | MIT | Generated UniApp engineering workspace |
| Wot Design Uni | 1.14.0 | Exact npm release | MIT | Mobile UI components and Form Schema renderer foundation |
| LogicFlow | To be locked during import | To be locked | Apache-2.0 | Process visualization |

Vben is fetched from its official GitHub repository by `scripts/upstream/bootstrap-vben.mjs`. Unibest is fetched by `scripts/upstream/bootstrap-unibest.mjs`, which also injects the exact Wot Design Uni dependency and Volar global component types. Generated upstream workspaces are not committed; local approval overlays and exact source revisions are committed. Original upstream license files remain inside generated workspaces.

This file must be updated whenever source code or dependencies are imported or pinned. Generated dependency notices will be added before public releases.
