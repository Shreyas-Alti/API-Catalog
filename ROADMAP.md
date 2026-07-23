# API Catalog — Final Build Spec (Repository → OpenAPI, Scalar-rendered)

Hand this to the coding agent as-is. It supersedes every earlier draft. It is
written to be as small as it can be while still doing the job — where an
earlier discussion proposed something and this doc leaves it out, that was
deliberate, not an oversight (see "Explicitly NOT building" at the bottom).

## Architecture (final)

```
Git Repository
     |
     v
Framework Detection (existing, unchanged)
     |
     v
Static Parser (existing, unchanged — this is still the only source of facts:
     |          method, path, params, DTO fields, status codes)
     v
ApiEndpoint rows in Postgres  <-- stays the source of truth, stays queryable
     |
     v
LLM Enrichment — writes directly onto those rows (summary, description,
     |            tags, examples). Never touches method/path/params/types.
     v
Review / Edit UI — facts and AI content both editable, clearly labeled
     |
     v
OpenAPI Generator — reads the rows, builds one JSON document, caches it
     |
     v
Validation (once, Java library, before caching)
     |
     v
Scalar Viewer (@scalar/api-reference-react) + Search page (existing, DB-backed)
```

`ApiEndpoint` never gets replaced by an OpenAPI blob as the storage layer —
that was floated and correctly walked back, because cross-repo search
(`SearchService`) needs relational rows, not per-repo JSON files to
re-parse on every query.

---

## 1. Database changes

### 1.1 `ApiEndpoint` — add columns, no new table

No separate "enrichment" table, no content-hash cache table. Just add
nullable columns to the entity you already have:

```java
// model/ApiEndpoint.java — additions only, everything else unchanged
@Column private String summary;                 // short human title, e.g. "Create User"

@JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_example", columnDefinition = "jsonb")
private java.util.Map<String, Object> requestExample;

@JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_example", columnDefinition = "jsonb")
private java.util.Map<String, Object> responseExample;

@Column(name = "ai_generated") private boolean aiGenerated = false;
@Column(name = "needs_review") private boolean needsReview = false;
@Column(name = "llm_model") private String llmModel;       // e.g. "claude-sonnet-4-6" — which model wrote this, for future debugging/migration
@Column(name = "manually_edited") private boolean manuallyEdited = false; // set true when a human edits an AI-owned field in Review
```

Plus getters/setters for each. `description` and `tags` already exist on
this entity — the LLM writes into those same columns, nothing new needed
for them.

### 1.2 `ApiParameter` / `ApiField` — add one field each

Both already exist as JSON-embedded objects (not separate tables, per the
current `@JdbcTypeCode(SqlTypes.JSON)` pattern on `ApiEndpoint`). Add:

```java
private String description;
// + getter/setter
```

### 1.3 `Repository` — add OpenAPI cache columns

```java
@Column(name = "openapi_cache", columnDefinition = "TEXT")
private String openapiCache;          // the generated JSON document, as a string

@Column(name = "openapi_dirty", nullable = false)
private boolean openapiDirty = true;  // set true whenever an endpoint changes

@Column(name = "openapi_generated_at")
private java.time.LocalDateTime openapiGeneratedAt;

@Column(name = "commit_sha", length = 64)
private String commitSha;             // capture at clone time, for future versioning — store only, don't build versioning UI now
```

`CloneService`: after cloning, resolve and store `HEAD` SHA the same way as
before; persist it onto `Repository` at save time.

Set `openapiDirty = true` any time: an endpoint is saved/edited in the
Review UI, or the enrichment/regenerate step updates a row. Nothing fancier
than a boolean flip.

---

## 2. LLM Enrichment

### 2.1 `LlmClient` interface

Keep this — it's one interface and one implementation, not a framework:

```java
package com.apicatalog.service.llm;

public interface LlmClient {
    String complete(String systemPrompt, String userPrompt) throws Exception;
}
```

`AnthropicClient implements LlmClient`, using `java.net.http.HttpClient` +
Jackson (already on the classpath, no new Maven dependency):

```java
@Component
public class AnthropicClient implements LlmClient {
    // POST to {baseUrl}/v1/messages with x-api-key, anthropic-version headers
    // model, max_tokens, system, messages — standard Anthropic Messages API shape
    // return the concatenated text of any "type": "text" content blocks
}
```

Config:

```yaml
llm:
  enabled: false
  api-key: ${ANTHROPIC_API_KEY:}
  model: claude-sonnet-4-6
  base-url: https://api.anthropic.com
```

Don't build a multi-provider factory/registry now — one interface, one
implementation is enough until a second provider is actually needed.

### 2.2 Context Builder (the accuracy-critical piece)

Replace any fixed-line-window snippet extraction with:

**`HandlerMethodExtractor`** — given the file and the `sourceLine` the
parser already recorded, return the *complete* handler method body:

- Java / TypeScript / JS / Go / C#: brace-matching — find the first `{`
  after the anchor line, count `{`/`}` (stripping comments/string contents
  first) until the matching close.
- Python: indentation-based — from the `def`/`async def` line, include
  every following line until one appears at the same or lower indentation.

**`TypeIndexBuilder`** — built once per repository submission, not per
endpoint: walk the repo once, regex-match type declarations per language
(`class X`, `record X`, `interface X`, `class X(BaseModel)`, `type X
struct`, etc.), producing a `Map<String, Path>` from type name to declaring
file. Generalizes the ad-hoc index `FastAPIParser` already has.

For each endpoint, resolve `requestBodyType`/`responseBodyType` through the
index and extract the full DTO source (same extraction technique, anchored
on the `class`/`type`/`struct` line). This is what fixes the
"controller just calls a service, there's nothing to describe" and
"method got cut off" problems from earlier testing.

Context sent to the LLM per endpoint = handler method source + resolved
request DTO source + resolved response DTO source. Don't chase further
into service-layer methods — if that turns out to still be needed later,
extend it then, don't build it preemptively.

### 2.3 Batching and the skip rule

One LLM call **per source file**, not per endpoint:

1. Group `ApiEndpoint`s by `sourceFile`.
2. **Skip any endpoint that already has a non-blank `description`** (e.g.
   from a docstring/JSDoc the parser already captured, or from a previous
   enrichment run). This replaces any notion of parser-confidence scoring
   — it's the one condition that's actually worth checking, and it's a
   single null/blank check, not a scoring system or a cache table.
3. For everything left, build one prompt per file listing each endpoint
   (tagged with a stable index), its already-known facts — now including
   **repository name and package/module path**, not just method/path/DTOs
   (e.g. "Repository: Inventory Service, Package: inventory.product") —
   and its context from §2.2. Cheap to add, meaningfully improves summary
   quality since the model isn't guessing the domain from a bare method
   signature. System prompt requires a JSON array back, one object per
   endpoint, matched by index:

```json
[
  {
    "id": 0,
    "summary": "Create User",
    "description": "Creates a new user account from the supplied name and email.",
    "tags": ["Users"],
    "parameters": [{"name": "id", "description": "The user's unique identifier."}],
    "requestBodyFields": [{"name": "name", "description": "The user's full name."}],
    "responseBodyFields": [{"name": "id", "description": "The generated user ID."}],
    "requestExample": {"name": "Jane Doe", "email": "jane@example.com"},
    "responseExample": {"id": 101, "name": "Jane Doe", "email": "jane@example.com"}
  }
]
```

System prompt rules (unchanged from earlier drafts, still correct): never
invent parameters/fields/status codes not already present, never change
method/path/types, omit rather than guess. Merge logic only ever writes
into fields matched by name — it can't create new ones. Two additions:

- **Tags get seeded before the LLM sees them, not invented from scratch.**
  The parser already has signals worth using — a controller name
  (`UserController`) or a base path (`/users`) is a reasonable starting
  tag. Set `ApiEndpoint.tags` from that during static extraction; the
  prompt instruction becomes "improve or confirm this tag if one is
  given, propose one only if none exists" rather than inventing blind.
- **Examples are constrained to the smallest valid case.** System prompt
  says: "Generate the smallest valid example matching the extracted
  schema" — not "generate an example" — to stop the model from padding
  payloads with unnecessary optional fields.

4. After merging, set `aiGenerated = true`, `needsReview = true`, record
   `llmModel` (the model string used, e.g. `claude-sonnet-4-6`), and mark
   the parent `Repository.openapiDirty = true`.

**No `summary` vs. `businessAction` split.** An earlier draft proposed a
separate "business action" field to drive a custom sidebar tree — drop
that. OpenAPI's `summary` field already is that short human label, and
Scalar's own sidebar already groups by `tags` and displays `summary` as the
leaf label. A second field doing the same job would just be duplicate data
to keep in sync.

### 2.4 Regenerate

`POST /api/endpoints/{id}/regenerate` — rebuild context for that one
endpoint, call the LLM ignoring the "skip if description exists" rule,
overwrite `summary`/`description`/`tags`/examples/field descriptions on
that row, set `needsReview = true`, update `llmModel`, set the parent repo
dirty. That's the whole feature — no version history, no diffing, no
separate storage for "previous" vs. "current" AI content.

One guard: if `manuallyEdited = true` on that endpoint (set when a human
edits an AI-owned field in the Review UI, see §5), the endpoint requires
`?force=true` on the request and the frontend shows a confirmation dialog
first ("this endpoint has manual edits — overwrite them?"). Without
`force=true`, return a 409 instead of silently overwriting. Prevents an
accidental click from erasing a hand-written description.

---

## 3. OpenAPI Generator

New service, `service/OpenApiGeneratorService.java`:

```java
public String generate(Repository repo) {
    // if (!repo.isOpenapiDirty() && repo.getOpenapiCache() != null) return repo.getOpenapiCache();
    // build a Map<String,Object> shaped like an OpenAPI 3.1 document:
    //   info: { title: repo.getName(), version: "1.0.0" }
    //   paths: one entry per distinct path, one operation per ApiEndpoint
    //     (summary, description, tags, parameters, requestBody, responses,
    //      examples — straight field-for-field mapping from ApiEndpoint)
    //   components.schemas: one schema per distinct requestBodyType/responseBodyType,
    //     built from that endpoint's ApiField list (name/type/description)
    // serialize with Jackson, validate (§4), cache on repo.openapiCache,
    // set openapiDirty = false, openapiGeneratedAt = now, save, return the string
}
```

Each generated operation also carries a few `x-` vendor extensions —
metadata that doesn't belong in standard OpenAPI fields but is genuinely
useful to anyone (including your own frontend plugin, §6.1) consuming the
raw document:

```json
{
  "x-source-file": "src/main/java/com/example/UserController.java",
  "x-source-line": 42,
  "x-ai-generated": true,
  "x-needs-review": true
}
```

Scalar (and any other OpenAPI-compliant tool) ignores unrecognized `x-`
fields by default, so this costs nothing for viewers that don't care about
it, while giving your own plugin-rendered badges (§6.1) something to key
off directly from the spec instead of a separate API call.

Serve it:

```
GET /api/repositories/{id}/openapi.json
```

returning `repo.getOpenapiCache()` (regenerating first if `openapiDirty`).
Target OpenAPI **3.1.0** — matches what Scalar's own examples use.

Store JSON only. Don't also generate/store a YAML copy — if someone wants
YAML, convert on export request, don't maintain two serialized copies of
the same data.

---

## 4. Validation — Java, not the JS package

`@scalar/openapi-parser` is a TypeScript/npm package. Your backend is
Spring Boot. **Don't** shell out to Node from Java to run it — that's
exactly the kind of cross-language plumbing that counts as
overengineering here. Use a Java OpenAPI validator instead:

```xml
<dependency>
  <groupId>io.swagger.parser.v3</groupId>
  <artifactId>swagger-parser</artifactId>
  <version>2.1.22</version>
</dependency>
```

```java
SwaggerParseResult result = new OpenAPIV3Parser().readContents(generatedJson);
if (!result.getMessages().isEmpty()) {
    log.warn("Generated OpenAPI has validation issues: {}", result.getMessages());
    // log and still serve it — a malformed doc from your own generator is a bug to fix,
    // not a runtime condition to build recovery logic around
}
```

One validation pass, at generation time, right before caching. Nothing
before it, nothing after it.

---

## Extraction Report (shown right after parsing, before Review)

A small summary screen between "repo finished cloning" and the Review
table — cheap to build, since every number on it already exists from the
parse + enrichment step, nothing new to compute:

```
Repository        Inventory Service
Framework         Spring Boot
Files scanned     128
Controllers       12
Endpoints found   47
Warnings          2
```

`framework` from detection, `filesScanned`/`controllers`/`endpoints found`
from the tree-sitter CLI's `routes[]` (count + distinct `controller`),
`warnings` straight from the CLI's `warnings[]` array (§ parsing spec).
One response DTO aggregating numbers already in memory at the end of
extraction — no new backend logic, just a landing screen so the person
submitting a repo has confidence the extraction actually worked before
they dive into reviewing 47 individual endpoints one at a time.

---

## 5. Review / Edit UI

Two clearly labeled sections per endpoint, both editable:

- **Extracted** (method, path, parameters, request/response schema) — the
  parser has been wrong before (a real case surfaced during testing), so
  these must stay editable, just visually marked as "from source" rather
  than "from AI."
- **AI-generated** (summary, description, tags, examples) — editable, with
  a "Generated by AI — Needs Review" label and a "Regenerate" button
  (calls §2.4) when `aiGenerated && needsReview`.

Saving an endpoint in this screen clears `needsReview` and sets the parent
repo's `openapiDirty = true`.

---

## 6. Frontend

- **Repository list / submit page** — unchanged.
- **Search page** — unchanged, still backed by `SearchService` against the
  DB rows (this is exactly why §1.1 kept `ApiEndpoint` relational). Result
  links go to the Scalar viewer for that repo, deep-linked to the specific
  operation if Scalar's routing supports it; otherwise just open the repo's
  viewer.
- **Review page** — updated per §5.
- **Viewer page** — replace the custom Catalog tree/endpoint-details
  components entirely with:

```bash
npm install @scalar/api-reference-react
```

```tsx
import { ApiReferenceReact } from '@scalar/api-reference-react'

<ApiReferenceReact
  configuration={{ url: `/api/repositories/${repoId}/openapi.json` }}
/>
```

Delete (don't just deprecate) `groupEndpoints.ts`, `humanReadable.ts`'s
endpoint-labeling logic, and the custom endpoint-tree component — Scalar's
sidebar (grouped by `tags`, labeled by `summary`) replaces all of it.
`EndpointDetails.tsx` is also fully replaced by Scalar — delete it.

### 6.1 UI customization (final decision — Scalar, not forked)

Considered and rejected: cloning/forking Scalar, cloning/forking Stoplight
Elements, building a from-scratch custom viewer. All three cost more than
they're worth right now — see §"Explicitly NOT doing" below. Decision:
stay on `@scalar/api-reference-react` from npm, customize through its two
supported extension points.

**Color scheme** — `@scalar/themes`:

```bash
npm install @scalar/themes
```

```tsx
import '@scalar/themes/style.css'
```

Override the `--scalar-*` CSS custom properties from your own app's global
stylesheet for brand colors. One dependency, one clean layer — no forking,
no separate design-system package involved.

**Structural additions** (e.g. a "Generated by AI — Needs Review" badge on
enriched operations) — the plugin system, not a fork:

```bash
npm install @scalar/react-renderer
```

Render a custom React component tied to an `x-` OpenAPI extension (e.g.
`x-needs-review`, set to `true` by the enrichment step in §2.3 when
`aiGenerated && needsReview`). This is how AI-content flags, or any other
per-operation UI addition, get built going forward — never by editing
Scalar's own source.

**Only reconsider a custom-built viewer if** a real, encountered need
can't be done through theme variables or a plugin — not preemptively, and
not for color/branding, which §6.1 already covers.

---

## 7. Config additions (`application.yml`)

```yaml
llm:
  enabled: false
  api-key: ${ANTHROPIC_API_KEY:}
  model: claude-sonnet-4-6
  base-url: https://api.anthropic.com
```

---

## 8. Build order

1. Add the columns from §1 (migration + entity fields). No behavior change yet.
2. `LlmClient`/`AnthropicClient` (§2.1).
3. `HandlerMethodExtractor` + `TypeIndexBuilder` (§2.2) — get this right
   before wiring up calls, it's the accuracy-critical part.
4. `EndpointEnrichmentService` with file-level batching + the skip rule
   (§2.3), wired into `ExtractionService` after parsing.
5. `/regenerate` endpoint (§2.4).
6. `OpenApiGeneratorService` + `swagger-parser` validation + the
   `/openapi.json` endpoint (§3, §4).
7. Extraction Report screen — trivial once step 4's output exists.
8. Review UI field grouping (§5).
9. Swap the frontend viewer for `@scalar/api-reference-react`, delete the
   custom tree/detail components (§6).

Steps 1–7 are backend-only and testable via the existing submit-and-extract
flow before touching the frontend at all.

---

## Explicitly NOT building (raised in earlier discussion, deliberately cut)

- A separate `endpoint_enrichment` table with SHA-256 content-hash caching
  — replaced by the one-line "skip if description already present" check.
- A `businessAction` field distinct from `summary` — redundant with what
  Scalar already renders from `summary` + `tags`.
- A multi-provider `LlmClient` factory/registry — one interface, one
  implementation, until a second provider is actually needed.
- Two-stage OpenAPI validation (structural + post-generation) — one pass,
  at generation time.
- Calling `@scalar/openapi-parser` (JS) from the Java backend — use
  `swagger-parser` (Java) instead.
- Storing OpenAPI as both JSON and YAML — JSON only, convert on export if
  ever needed.
- Full version history / API diffing across commits — `commitSha` is
  captured and stored on `Repository` now so this is possible later
  without a schema change, but no versioning UI or diff logic is built in
  this pass.
- Repository-level LLM summary ("what does this whole service do") — a
  genuinely separate feature, fine to add later, not part of this spec.
- Forking or cloning Scalar, forking or cloning Stoplight Elements, or
  building a fully custom documentation viewer from scratch — decided
  against. Color and structural customization needs are met by
  `@scalar/themes` and Scalar's plugin system (§6.1) at a fraction of the
  cost of owning a UI library's source. Revisit only if a specific,
  encountered need turns out not to be achievable through either.
- Embeddings, a vector database, or semantic search — plain relational
  search (`SearchService` against `ApiEndpoint` columns) covers "find an
  endpoint" fine. Revisit only if that stops being true in practice.
- A workflow engine, job queue, Redis, or background workers — extraction
  and enrichment run synchronously as part of the submit request. Nothing
  in this spec is slow or unreliable enough yet to need an async job
  system; introducing one now would be solving a scaling problem before
  there's evidence of one.
- AI Q&A over the API catalog — a distinct feature built on top of a
  working catalog, not part of getting the catalog itself working.