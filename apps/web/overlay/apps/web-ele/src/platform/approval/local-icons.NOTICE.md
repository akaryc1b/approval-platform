# Approval shell icon source and notices

`local-icons.ts` contains five exact SVG definitions from the already pinned
`@iconify/json` 2.2.476, using the existing `@iconify/vue` 5.0.1 registration API.
No icon collection or runtime dependency is added. The original names, dimensions,
transforms and currentColor bodies are preserved.

The governed local-demo user has only the `super` role. Pinned frontend authority
filtering admits the ApprovalPlatform/ApprovalWorkbench route pair, whose icons
are `lucide:workflow` and `lucide:inbox`. The visible shell uses `ep:fold` and
`fluent-mdl2:world-clock`; `ep:expand` is its paired existing sidebar state.
These five definitions are registered synchronously before the first bootstrap
await and before router installation or component mount. Other permissioned
routes and custom icon/provider fallback behavior are unchanged.

All four observed icons have SVG bodies and resolved geometry identical to the
successful Iconify response resources retained in natural CI #1826. Expand is
bound to pinned local data, not claimed to have appeared in that response set.
This is source/asset provenance, not a native WebKit acceptance claim.

## Pinned data provenance

Vben commit: `63a38dce49ba109f61607994e21ba921d8e970e9` (v5.7.0).

@iconify/json package.json SHA-256: `6ec8fa6ecba30ce4da4b9036f49d74cb0c85bc61096f9a9e888812e2c48acc8d`.

- `ep` (fold, expand): Element Plus; metadata license MIT; `json/ep.json` SHA-256 `e8687b610c5fc5cfffdbb7355cb288ab853bfa9bd87cdc37f421e816aeb082cc`.
  Metadata source: https://github.com/element-plus/element-plus-icons
  Metadata license URL: https://github.com/element-plus/element-plus-icons/blob/main/packages/svg/package.json

- `fluent-mdl2` (world-clock): Microsoft Corporation; metadata license MIT; `json/fluent-mdl2.json` SHA-256 `13549c4504ef6806e27f270702fdca061d0026749a27490efce3349e4bf14222`.
  Metadata source: https://github.com/microsoft/fluentui/tree/master/packages/react-icons-mdl2
  Metadata license URL: https://github.com/microsoft/fluentui/blob/master/packages/react-icons-mdl2/LICENSE

- `lucide` (inbox, workflow): Lucide Contributors; metadata license ISC; `json/lucide.json` SHA-256 `f7b10c2ba65f53e8ae0700f1bab235b37ac61a464b6c97814d5e93d68f937e1f`.
  Metadata source: https://github.com/lucide-icons/lucide
  Metadata license URL: https://github.com/lucide-icons/lucide/blob/main/LICENSE

## Full family notices

SVG bodies originate from the pinned Iconify JSON collection above. Element Plus
and Lucide notice text below is copied byte-for-byte from already pinned
same-family component packages. Lucide's included Feather MIT notice is retained
alongside ISC. Fluent had only license metadata locally; its notice was retrieved
from the official metadata-linked file on 2026-10-09 and is identified by its Git
blob below. This records notice provenance and makes no legal-clearance claim.

### Element Plus

Source: @element-plus/icons-vue 2.3.2

Notice SHA-256: `5db4ebb3abd8353774ae148723ccc14087d6dab99473f77a7b799b55defd8dfe`.

```text
The MIT License (MIT)

Copyright (c) 2020-PRESENT Element Plus (https://github.com/element-plus)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Lucide

Source: lucide-vue-next 0.577.0

Notice SHA-256: `668dcc52803480e0a026b31140a4cae668772663cd764e5991d252eef03f98db`.

```text
ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2026 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2026.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

---

The MIT License (MIT) (for portions derived from Feather)

Copyright (c) 2013-2026 Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Fluent UI MDL2

Source: https://github.com/microsoft/fluentui/blob/master/packages/react-icons-mdl2/LICENSE

Notice SHA-256: `4d0c29b5ff7917695e6ed0c1f358624faf4340da1232bb1d684ccc12b5afeffd`.
Official Git blob: `025ad0e53c8036fe2904363d930ef7308a41d2a8`; retrieved 2026-10-09T10:51:31.511Z.

```text
@fluentui/react-icons-mdl2

Copyright (c) Microsoft Corporation

All rights reserved.

MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the ""Software""), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED _AS IS_, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
