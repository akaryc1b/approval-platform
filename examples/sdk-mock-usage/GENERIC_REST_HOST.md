# Generic REST host binding blueprint

This blueprint is intentionally transport-free. It contains no URL, token, usable credential or HTTP client.

A Generic REST host adapter should:

1. construct its server-owned `ServerProfile` from deployed contract metadata;
2. map an untrusted client compatibility body only to `ClientProfile` fields;
3. negotiate compatibility before accepting SDK operations;
4. bind the operation to a bounded transport policy;
5. select a logical endpoint descriptor without resolving an address;
6. derive tenant, operator, permission snapshot and audit reference through a server resolver;
7. acquire a reference-only credential lease bound to endpoint, context and operation;
8. validate lifecycle, response mapping and retry traces with scripted adapters;
9. close the adapter and release the lease exactly once.

Unknown manifest, policy or binding versions; missing capabilities; expired support windows; operation mismatch; expired security evidence; and exhausted budgets fail closed. Address resolution, authentication execution, real clocks and network execution remain outside this slice.
