# Third-party licenses

| Component | Version | Source revision | License | Usage |
|---|---:|---|---|---|
| Flowable Engine | 8.0.0 | Maven release | Apache-2.0 | Workflow execution engine |
| Spring Boot | 4.0.2 | Maven release | Apache-2.0 | Server application framework |
| Vben Admin | 5.7.0 | `63a38dce49ba109f61607994e21ba921d8e970e9` | MIT | Generated PC engineering workspace and `web-ele` application |
| Unibest | 4.4.1 | `f05992eb9897158cb9c8031efd1ff8ca8db50403` | MIT | Generated UniApp engineering workspace |
| Wot Design Uni | 1.14.0 | Exact npm release | MIT | Mobile UI components and Form Schema renderer foundation |
| Element Plus shell icons | `@iconify/json` 2.2.476 (family 2.3.2) | Pinned npm data; hashes in linked notice | MIT | Bundled `ep:fold` and `ep:expand` |
| Fluent UI MDL2 shell icon | `@iconify/json` 2.2.476 | Pinned npm data; hashes in linked notice | MIT | Bundled `fluent-mdl2:world-clock` |
| Lucide shell icons | `@iconify/json` 2.2.476 | Pinned npm data; hashes in linked notice | ISC; inherited Feather MIT notice retained | Bundled `lucide:inbox` and `lucide:workflow` |
| LogicFlow | To be locked during import | To be locked | Apache-2.0 | Process visualization |

Vben is fetched from its official GitHub repository by `scripts/upstream/bootstrap-vben.mjs`. Unibest is fetched by `scripts/upstream/bootstrap-unibest.mjs`, which also injects the exact Wot Design Uni dependency and Volar global component types. Generated upstream workspaces are not committed; local approval overlays and exact source revisions are committed. Original upstream license files remain inside generated workspaces.

This file must be updated whenever source code or dependencies are imported or pinned. Generated dependency notices will be added before public releases.

The five bundled approval shell definitions retain exact SVG bodies and geometry from the already pinned Iconify JSON data. Their [source hashes and full family notices](apps/web/overlay/apps/web-ele/src/platform/approval/local-icons.NOTICE.md) distinguish the data source, pinned same-family component notices, and the separately retrieved official Fluent license text. Existing icon runtime dependencies and custom provider behavior are unchanged.
