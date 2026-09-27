# AI Workforce OS — Test and Fix Plan

**Version 1 · 25 September 2026**

## Executive summary

- The backend works end to end. All seven business services run, authenticate each other, enforce
  role-based access inside each service, and complete the core workflow: a goal is given to an
  agent, the agent runs on the sandbox model, reaches for an outbound tool, parks for approval, and
  resumes or cancels on the decision.
- The web client does not yet use that backend. Fourteen screens render hard-coded sample data,
  twelve buttons do nothing, and one link is dead. This is the root cause of the product feeling
  disconnected, and it is the first thing to fix.
- Frontend foundation work is half finished: the data layer, router, toasts, shared states and a
  real navbar are written, but the screens are not yet connected, so the web client currently has
  three type errors and two failing design tests.
- This plan lists every journey to walk as a first-time user, the exact expected result for each
  role, every known defect with its file, and the order to fix and verify them. The work is done
  when every journey passes for every role, every screen has all four data states, and the defect
  register is empty.

---

## 1. Current baseline

Verified on 25 September 2026.

| Area | State | Evidence |
|---|---|---|
| Backend build | Green, 14 modules | `mvn install` |
| Backend tests | 54 passing | router 10, adapter 16, gateway 21, permissions 5, token verification 2 |
| Services running | 7 of 8 | identity, org, orchestrator, memory, knowledge, integrations, analytics |
| Gateway | Not running | Needs Redis, which is not installed on this machine |
| Sign-in and demo accounts | Working | All five roles sign in; token carries name, email, role, permissions |
| Cross-service auth | Working | ES256 token verified independently by every service |
| RBAC | Working in the API | Employee gets 403 on `/api/roles`, `/api/providers`, approval decisions |
| Goals and agent runs | Working in the API | Sandbox runs complete, or park for approval |
| Approvals | Working in the API | Approve resumes the run; reject cancels; a second decision returns 409 |
| Knowledge ingestion and search | Working in the API | Two PDFs indexed as 60 passages; cited search; no-evidence query returns nothing |
| Web type check | **3 errors** | Detail screens do not yet accept an `id` prop |
| Web design tests | **10 of 12 passing** | See D02, D03 |
| Web screens on real data | **1 of 17** | Only Profile reads live data; Landing and 404 are static by nature |

---

## 2. How to run the platform for testing

```bash
# PostgreSQL 16 on port 55432 (Homebrew install, data in the session scratchpad)
make build          # compile and package every service
make dev-backend    # start the seven services from their jars
make dev-status     # confirm all seven are up
make web-dev        # web client on http://localhost:5173
```

Every demo account shares the password `demo-workspace-2026`. The sign-in screen offers each one as
a single click.

| Account | Role | Permissions |
|---|---|---|
| `owner@demo.aiworkforce.os` | owner | 46, everything |
| `admin@demo.aiworkforce.os` | admin | 45, everything except closing the workspace |
| `manager@demo.aiworkforce.os` | manager | 25 |
| `employee@demo.aiworkforce.os` | employee | 12 |
| `viewer@demo.aiworkforce.os` | viewer | 8 |

To reset to a clean database between test rounds, drop the service schemas and restart; Flyway and
the seeders rebuild everything, including the four demo agents.

---

## 3. Expected behaviour by role

This table is the oracle for every journey below. "Hidden" means the control or menu entry must not
appear. "Refused" means the screen shows the permission state and the API returns 403.

| Capability | Permission | owner | admin | manager | employee | viewer |
|---|---|---|---|---|---|---|
| See agents | `agent:read` | yes | yes | yes | yes | yes |
| Create an agent | `agent:create` | yes | yes | yes | hidden | hidden |
| Edit an agent's persona | `agent:update` | yes | yes | yes | hidden | hidden |
| Give an agent a task | `agent:run`, `task:create` | yes | yes | yes | yes | hidden |
| See goals, tasks and runs | `task:read`, `run:read` | yes | yes | yes | yes | yes |
| Cancel a run | `run:cancel` | yes | yes | yes | hidden | hidden |
| See the approvals queue | `approval:read` | yes | yes | yes | yes | hidden |
| Approve or reject | `approval:decide` | yes | yes | yes | hidden | hidden |
| Chat with citations | `chat:use`, `knowledge:query` | yes | yes | yes | yes | hidden |
| See knowledge sources | `knowledge:read` | yes | yes | yes | yes | yes |
| Upload documents | `knowledge:source_manage` | yes | yes | yes | hidden | hidden |
| See integrations | `integration:read` | yes | yes | yes | yes | yes |
| See model routing | `provider:read` | yes | yes | yes | hidden | hidden |
| Enable providers, store keys | `provider:manage` | yes | yes | hidden | hidden | hidden |
| See members | `member:read` | yes | yes | yes | yes | yes |
| See and edit roles | `role:read`, `role:update` | yes | yes | read only | refused | refused |
| See analytics | `analytics:read` | yes | yes | yes | hidden | yes |
| See the audit log | `audit:read` | yes | yes | hidden | hidden | hidden |

Every "hidden" must also be verified as **refused by the API** (section 8), because hiding a button
is a convenience and never a control.

---

## 4. Phase 0 — Restore a green baseline

Blocking. Nothing else is verified until the web client type-checks and its tests pass.

| # | Task | Files |
|---|---|---|
| 0.1 | Make `AgentDetail`, `RunDetail` and `SourceDetail` accept an `id` prop and load that record | `web/src/routes/AgentDetail.tsx`, `RunDetail.tsx`, `SourceDetail.tsx` |
| 0.2 | Use `<Eyebrow>` on the 404 screen instead of a class | `web/src/routes/NotFound.tsx` |
| 0.3 | Tighten the exclamation-mark check to JSX text only, so `!==` in TypeScript is not flagged | `web/src/styles/design-system.test.ts` |
| 0.4 | Confirm `pnpm exec tsc -b`, `pnpm test`, `pnpm build` and `mvn install` all pass | — |

---

## 5. Phase 1 — End-to-end journeys

Walk each journey as a first-time user, in the browser, for the roles listed. Record the result in
the defect register. Fix, then walk it again. A journey passes only when every expected result
holds for every listed role.

### J1 · Understand what the product does

**Roles:** signed out.

1. Open `http://localhost:5173/`.
2. Read the page top to bottom; follow "How it works" and "Use cases".
3. Press "Try a demo account" and "Create a workspace".

**Expected:** the public page appears, not the dashboard. Anchor links scroll to their sections.
"Try a demo account" opens sign-in. "Create a workspace" leads somewhere that works (see D20).
No sample numbers are presented as real.

### J2 · Sign in, stay signed in, sign out

**Roles:** all five.

1. Sign in with a demo button. Sign in again by typing the email and password.
2. Sign in with a wrong password; then with an unknown email.
3. Reload the page. Open a second tab.
4. Open the account menu; sign out.
5. While signed out, visit `/approvals` directly; sign in.
6. Expire the session (clear `aiwos.accessToken` or wait 15 minutes) and act.
7. Visit `/sign-in?next=https://example.com`.

**Expected:** the avatar, name, email and role are the signed-in account's own. Wrong password and
unknown email show the identical message. Reload keeps the session; a new tab asks to sign in
(session storage is per tab, which is intended). Sign-out lands on sign-in with "You have signed
out", and the refresh cookie is cleared on the server. Step 5 returns to `/approvals` after sign-in.
Step 6 returns to sign-in with "Your session ended" and back to the same page afterwards. Step 7
ignores the external address and goes to the Command Map.

### J3 · Navigate between sections

**Roles:** manager, employee, viewer.

1. Use every capsule item, the bell, the gear menu and the avatar menu.
2. Use the browser back and forward buttons across five screens.
3. Open a detail screen by pasting its URL into a new tab.
4. Visit `/does-not-exist`.
5. Repeat 1–4 at 375 px wide (the capsule becomes a menu).

**Expected:** each destination opens with its own page title and starts at the top. The capsule
highlights the current section. The bell count equals the number of pending approvals and opens the
queue. Menus close on Escape, on an outside click and on navigation, and never run off the screen.
Menu entries the role cannot use are not shown (section 3). Back and forward restore each screen.
A pasted URL works after sign-in. An unknown address shows the 404 screen with a way home. No page
scrolls sideways at any width.

### J4 · Agents: browse, create, configure

**Roles:** manager (full), employee (read only), viewer (read only).

1. Open Agents. Open each of the four demo agents.
2. As manager, create an agent: name, key, category and instructions. Try a duplicate key.
3. Edit an agent's instructions and save. Open its history.
4. As employee and viewer, confirm create and edit are not offered.

**Expected:** the list shows the four seeded agents from the API with their category and revision.
Detail shows the real instructions, tool grants (server, tools, scopes) and which tools always need
approval. Creating succeeds with a confirmation and opens the new agent. A duplicate key shows the
field error from the API without losing what was typed. Saving creates a new revision; a revision a
run has used is shown as sealed and cannot be edited in place. Loading, empty and error states
appear where appropriate.

### J5 · Give an agent work and see the outcome

**Roles:** manager, employee. Viewer must not be offered this.

1. From the Command Map, from Agents, from an agent's page and from Tasks, open "Give it a task".
2. Submit with an empty description; then with a real one.
3. Repeat until each outcome has been seen: completed, waiting for approval, failed.
4. Open the resulting run.

**Expected:** one dialog, identical from all four entry points. Empty input is refused inline. While
running, the button shows progress and cannot be pressed twice. The outcome is stated plainly:
the answer for a completed task, "waiting for approval to use gmail.send_message" with a link to the
queue, or the failure reason. Every outcome links to its run trace. Tasks, the Command Map and the
bell update without a reload.

### J6 · Approvals: review and decide

**Roles:** manager (decides), employee (reads), viewer (hidden).

1. Create work until at least two approvals are pending.
2. Read an approval: agent, tool, what it will do, when it expires.
3. Approve one. Reject one, with a note.
4. Try to decide an approval that is already decided (two tabs).
5. As employee, open the queue.

**Expected:** each card shows exactly what will be sent (see D38; today the payload is not stored).
Approve resumes the run and reports the run's new state. Reject asks for confirmation, cancels the
run and says so. A second decision shows "already decided" without an error page. The employee
sees the queue with no approve or reject controls. The empty queue explains what will appear there.

### J7 · Run trace

**Roles:** manager, employee, viewer.

1. Open a completed run, a waiting run and a cancelled run.
2. As manager, cancel a running or waiting run.

**Expected:** steps in order with their kind, model, provider attempts (including skipped
candidates), tool calls with outcome, and approval steps. Figures carry units. Cancelling asks for
confirmation, removes its pending approval from the queue and updates the status.

### J8 · Knowledge: add and inspect documents

**Roles:** manager (uploads), employee and viewer (read).

1. Create a source. Upload each of: a text PDF, a DOCX, a scanned PDF, a password-protected PDF, an
   empty file, a file over 25 MB, and the same PDF a second time.
2. Open the source and read the document list.

**Expected:** each upload reports its own outcome in words a person can act on: indexed with a
passage count; not indexable because it is a scan (run OCR); password protected; empty; too large
(before uploading, not after); unchanged since last time. While the vector store is unavailable the
screen says keyword search is working and meaning-based search is not. Counts on the list match the
document table.

### J9 · Chat with citations

**Roles:** manager, employee. Viewer must not be offered Chat.

1. Ask "How does the approval queue work?" and "What technology does the backend use?".
2. Ask something the documents cannot answer.
3. Submit an empty question; press Enter to submit.

**Expected:** answers list the passages they rely on, each with document title and page. The
unanswerable question says no document supports an answer, rather than showing anything. Empty
questions cannot be sent; Enter submits; the input keeps focus.

### J10 · Integrations

**Roles:** all.

1. Open Integrations. Read each server's tools.

**Expected:** the six servers and their tools come from the API. Tools that always need approval are
marked. "Connect an account" does not pretend to work: until OAuth exists it explains that the
server runs against a sandbox and live connection is not yet available (D22).

### J11 · Model routing

**Roles:** owner (manages), manager (reads), employee (hidden).

1. Open Model routing. Read providers and models.
2. As owner, store a provider key; enable and disable a provider.

**Expected:** eight providers and their models from the API, with credential status and circuit
state. Storing a key never shows the key again, only a fingerprint. Enabling a provider without a
key explains that the router will skip it. Manager sees everything read-only.

### J12 · Members and roles

**Roles:** owner and admin (manage), manager (read), employee and viewer (members only).

1. Open Members and roles. Read members and roles.
2. As admin, create a role from permissions, edit it, delete it.
3. Try to delete a role somebody holds, and to edit a built-in role.
4. As employee, open the page.

**Expected:** the five real members with their roles. Roles show permission counts and which
permissions can widen authority. Creating and editing use the real permission catalogue. Deleting a
held role and editing a built-in role are refused with an explanation. The employee sees members
and a permission state in place of roles, never fabricated data.

### J13 · Profile

**Roles:** all.

**Expected:** the signed-in account's name, email, role and every permission it holds, grouped and
described. This is the screen that answers "is this broken, or is it my role?".

### J14 · Audit log and analytics

**Roles:** owner (audit), manager and viewer (analytics).

**Expected:** no fabricated numbers anywhere. Analytics is computed from real runs, goals and
approvals, and every figure states its source. The audit log shows real activity with the person
accountable for each action (D19). If a figure cannot be computed from real data, it is removed.

### J15 · Create a workspace

**Roles:** signed out.

**Expected:** either the flow creates a real workspace and signs the person in as its owner, or it is
removed from sign-in and the landing page until it can (D20). A three-step form that ends on a button
that does nothing is not acceptable.

---

## 6. Phase 2 — Consistency across every screen

Apply to every screen. A screen passes when every column is ticked.

| Screen | Real data | Loading | Empty | Error with retry | Permission state | Every action gives feedback | No dead control |
|---|---|---|---|---|---|---|---|
| Command Map | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Agents | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Agent detail | ☐ | ☐ | — | ☐ | ☐ | ☐ | ☐ |
| Tasks | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Run detail | ☐ | ☐ | — | ☐ | ☐ | ☐ | ☐ |
| Approvals | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Chat | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Knowledge | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Source detail | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Integrations | ☐ | ☐ | — | ☐ | ☐ | ☐ | ☐ |
| Model routing | ☐ | ☐ | — | ☐ | ☐ | ☐ | ☐ |
| Members and roles | ☐ | ☐ | — | ☐ | ☐ | ☐ | ☐ |
| Profile | ☑ | ☑ | — | ☐ | — | — | ☑ |
| Audit log | ☐ | ☐ | ☐ | ☐ | ☐ | — | ☐ |
| Analytics | ☐ | ☐ | ☐ | ☐ | ☐ | — | ☐ |

Rules that apply everywhere, taken from the design brief:

- One page header pattern: eyebrow, title, one-line description, primary action on the right.
- One primary action per screen, in the accent colour; everything else outline or quiet.
- Buttons show a spinner and disable while working; a destructive action asks for confirmation.
- Every completed action produces a toast; errors stay until dismissed.
- Status words and colours come from one mapping (`StatusTag`) so "waiting for approval" looks the
  same on every screen.
- Every figure carries its unit outside the number, and a source caption.
- Borders only `--line`; radii from the two families; hover state on every clickable card; no
  exclamation marks; no fabricated sample data.
- Forms keep what was typed when the server rejects them, and show the field-level error from the
  server beside the field.
- Detail screens have a back link to their list.

---

## 7. Phase 3 — Responsiveness and accessibility

| Check | Widths or tool | Expected |
|---|---|---|
| Layout | 375, 768, 1024, 1280, 1440 px | No sideways scroll; capsule becomes a menu below 900 px; tables scroll within their card; dialogs fit |
| Assistant launcher | 375 px | Never covers content or the last row of a table |
| Keyboard | Tab, Shift-Tab, Enter, Escape | Every control reachable in order; visible focus ring; dialogs trap focus and return it on close; skip link works |
| Screen reader | VoiceOver | Page titles change; toasts announced; status never conveyed by colour alone; icon buttons labelled |
| Contrast | axe-core | Zero violations; muted text never lighter than `#5c6a80` on white |
| Reduced motion | OS setting | No animation beyond an instant change |

---

## 8. Phase 4 — Backend and API verification

Run for every role. Every "hidden" in section 3 must return 403 here.

| Area | Checks |
|---|---|
| Authentication | Sign-in, refresh with rotation, reuse detection revokes the family, sign-out clears the cookie, lockout after repeated failures |
| RBAC | For each endpoint, call with every role's token and with none; compare to section 3 |
| Tenancy | A token for one workspace cannot read another's agents, runs, sources or approvals |
| Validation | Empty, oversized and malformed bodies return 422 with field errors, never 500 |
| Not found | Unknown ids return 404; unknown routes return 404 |
| Concurrency | Two approvals of the same request: one succeeds, one returns 409 |
| Upload limits | 25 MB enforced consistently in the controller, Spring multipart and the ingress |
| Internal endpoints | `/internal/credentials`, `/internal/embeddings` refuse user tokens; `/internal/tokens` requires the secret |
| Resilience | Stop knowledge's vector store dependency: search degrades to keyword with a message, never fails |

---

## 9. Phase 5 — Automated regression tests to add

| Test | Tool | Covers |
|---|---|---|
| J2 sign-in, expiry, sign-out, `next` redirect | Playwright | Every role |
| J5 give a task, all three outcomes | Playwright | manager |
| J6 approve and reject | Playwright | manager and employee |
| J8 upload each file type | Playwright | manager |
| J9 cited answer and no-evidence answer | Playwright | employee |
| Role matrix for navigation and controls | Playwright | all five roles, section 3 |
| `QueryState` renders all four states | Vitest | component |
| `api()` maps 401, 403, 422 and network failure | Vitest | unit |
| RBAC matrix per endpoint | JUnit with Testcontainers | every service |
| Goal creation, approval resume and reject | JUnit integration | orchestrator |
| Ingestion outcomes per file type | JUnit | knowledge |

---

## 10. Defect register

Severity: **P0** blocks a journey · **P1** degrades a journey · **P2** polish.
Status: **Open** · **Fixed, verify** (changed in code, not yet walked in the browser) · **Verified**.

| ID | Sev | Area | Defect | File | Status |
|---|---|---|---|---|---|
| D01 | P0 | Web | Type check fails: detail screens do not accept `id` | `App.tsx:45,47,51` | Fixed |
| D02 | P2 | Web | 404 screen uses a class, not `<Eyebrow>` | `routes/NotFound.tsx` | Fixed |
| D03 | P2 | Tests | Exclamation check flags `!==` in TypeScript | `styles/design-system.test.ts` | Fixed |
| D04 | P0 | Web | 14 screens show hard-coded data despite a live API | `routes/*.tsx` | Fixed |
| D05 | P0 | Web | "Add an agent" does nothing | `routes/Agents.tsx:73` | Fixed |
| D06 | P0 | Web | "Approve and send" does nothing | `routes/Approvals.tsx:140` | Fixed |
| D07 | P0 | Web | "Reject" does nothing | `routes/Approvals.tsx:141` | Fixed |
| D08 | P1 | Web | "View recent decisions" does nothing | `routes/Approvals.tsx:90` | Fixed |
| D09 | P0 | Web | "Give it a task" does nothing | `routes/AgentDetail.tsx:75` | Fixed |
| D10 | P0 | Web | Chat "Ask" does nothing | `routes/Chat.tsx:150` | Fixed |
| D11 | P1 | Web | "Create workspace" final button does nothing | `routes/CreateWorkspace.tsx:105` | Fixed |
| D12 | P1 | Web | "Connect a source" does nothing | `routes/Knowledge.tsx:106` | Fixed |
| D13 | P1 | Web | "Invite someone" does nothing | `routes/Members.tsx:85` | Fixed |
| D14 | P1 | Web | "Connect an account" does nothing | `routes/Integrations.tsx:152` | Fixed |
| D15 | P1 | Web | "Index again" does nothing | `routes/SourceDetail.tsx:91` | Fixed |
| D16 | P0 | Web | "New goal" does nothing | `routes/Tasks.tsx:110` | Fixed |
| D17 | P0 | Web | Navbar showed one hard-coded person for every account | `components/layout/Navbar.tsx` | Fixed |
| D18 | P1 | Web | Menus ran off the left edge; page scrolled sideways under 900 px | `styles/components.css` | Fixed |
| D19 | P1 | Web | Approvals badge fixed at 3 | `components/layout/Navbar.tsx` | Fixed |
| D20 | P1 | Web | Menus offered screens the role cannot open | `components/layout/Navbar.tsx` | Fixed |
| D21 | P1 | Web | `/profile` link led nowhere | `routes/Profile.tsx` | Fixed |
| D22 | P1 | Web | Unknown addresses silently showed the Command Map | `routes/NotFound.tsx` | Fixed |
| D23 | P1 | Web | Private screens rendered for signed-out visitors | `App.tsx` | Fixed |
| D24 | P1 | Web | Expired session gave no explanation or way back | `lib/api.ts` | Fixed |
| D25 | P1 | Web | Sign-in ignored where the person was going | `routes/SignIn.tsx` | Fixed |
| D26 | P1 | Web | Every agent, run and source link opened the same sample record | `App.tsx`, detail routes | Fixed |
| D27 | P0 | Web | Approval card linked to a run using the approval's id | `routes/Approvals.tsx` | Fixed |
| D28 | P1 | Web | Command Map figures fabricated (184 tasks, 47.5 hours) | `routes/CommandMap.tsx` | Fixed |
| D29 | P1 | Web | Audit log and analytics show fabricated data | `routes/AuditLog.tsx`, `Analytics.tsx` | Fixed |
| D30 | P2 | Web | Assistant launcher opens nothing | `components/ui/index.tsx` | Fixed |
| D31 | P1 | Web | Role editing exists in the API but not the interface | `routes/Members.tsx` | Fixed |
| D32 | P1 | Web | Agent instructions cannot be edited in the interface | `routes/AgentDetail.tsx` | Fixed |
| D33 | P1 | Web | Runs and goals cannot be cancelled in the interface | `routes/RunDetail.tsx`, `Tasks.tsx` | Fixed |
| D34 | P1 | Web | Providers cannot be enabled, and keys cannot be stored, in the interface | `routes/ModelRouting.tsx` | Fixed |
| D35 | P1 | Web | No action anywhere confirms it worked | all screens | Fixed |
| D36 | P1 | Web | No loading, empty, error or permission states on data screens | all screens | Fixed |
| D37 | P1 | Web | Capsule shows Chat to a viewer, who lacks `chat:use` | `components/layout/Navbar.tsx` | Fixed |
| D38 | P1 | API | Approvals do not store what will be sent, so the approver cannot see it | `orchestrator/service/ApprovalService.java` | Fixed |
| D39 | P1 | API | Tasks do not expose their run, so a task cannot link to its trace | `orchestrator/web/GoalController.java` | Fixed |
| D40 | P1 | API | No workspace-creation endpoint for the create-workspace flow | `org-service` | Fixed |
| D41 | P1 | API | No invitation endpoint, though the table exists | `org-service` | Fixed |
| D42 | P1 | API | No audit or analytics endpoints; the audit table is never written | `analytics-service` | Fixed |
| D43 | P2 | API | No reindex endpoint for a source | `knowledge/web/KnowledgeController.java` | Fixed |
| D44 | P2 | Platform | Gateway not running (needs Redis); production routing path untested | `services/gateway` | Fixed |
| D45 | P1 | Web | `TaskDialog` sent an empty `agentId` when opened from Command Map or Tasks, failing validation with no explanation | `components/ui/TaskDialog.tsx` | Fixed |
| D46 | P1 | Web | Command Map's Live Activity table always showed a raw agent UUID instead of its name (`agents.data` read off an already-unwrapped map) | `routes/CommandMap.tsx` | Fixed |
| D47 | P0 | API | The demo workspace has no row in org-service's own `organisations` table (identity and orchestrator seed against a fixed org id org-service never created), so every org-service feature scoped to it - credentials, settings, working hours, budgets - failed with a foreign-key violation | `org-service` | Fixed |
| D48 | P1 | API/Web | No endpoint or screen sets an agent's or workspace's model policy candidates; with none configured, every run is hard-coded to the sandbox regardless of which providers are enabled or have credentials | `orchestrator/web/ModelPolicyController.java` (new), `routes/ModelRouting.tsx` | Fixed |
| D49 | P1 | Web | `hasProviderManage` in Model routing was hard-coded `false` (`// TODO: check actual permission`), so Enable/Disable and Store key were unusable for every role including owner; the "Store key" dialog also claimed the backend endpoint "has not been implemented yet" although `PUT /api/credentials/{ref}` already existed and worked | `routes/ModelRouting.tsx` | Fixed |
| D50 | P0 | Platform | `spring-boot-starter-webflux` was scoped `test` in the gateway's own `pom.xml`, so the reactive Netty server never shipped in the runtime jar - the gateway could not start regardless of Redis | `services/gateway/pom.xml` | Fixed |
| D51 | P0 | Platform | The gateway had zero Java source beyond its bootstrap class - no `KeyResolver` beans for the rate limiter SpEL references in `application.yml`, no reactive `SecurityWebFilterChain`/JWT decoder at all, so even with D50 fixed it failed to start and then let every request through unauthenticated | `services/gateway/src/main/java/os/aiworkforce/gateway/security/` (new) | Fixed |
| D52 | P1 | API | `AuditEvent.sequence` (the hash chain's `BIGSERIAL`) was mapped with `@GeneratedValue`, which JPA only permits on the identifier property - analytics-service failed to start entirely once real code exercised the entity, despite `mvn clean install` passing (its tests never boot the full JPA context) | `analytics-service/domain/AuditEvent.java` | Fixed |
| D53 | P0 | Web | The Knowledge route table gated `/knowledge` and `/knowledge/:id` on the permission code `source:read`, which does not exist anywhere in the permission catalogue (the real code is `knowledge:read`, used correctly inside `Knowledge.tsx` itself) - the entire Knowledge section was permanently unreachable for every role, including owner, since the route-level check runs before the screen renders | `App.tsx` | Fixed |
| D54 | P2 | API | No reindex endpoint ever existed in `knowledge-service` (a prior status of "Fixed, verify" for this row was wrong); the frontend's `useReindexSource` hook called `/api/sources/{id}/reindex`, which 404'd. Added `IngestionService.reindex(...)`, re-embedding a source's already-stored chunks without needing the original file, and the controller endpoint | `knowledge/service/IngestionService.java`, `knowledge/web/KnowledgeController.java` | Fixed |
| D55 | P2 | Web | "View recent decisions" on the empty Approvals screen linked to `/runs`, a path with no registered route (only `/runs/:id` exists), so it 404'd instead of showing anything | `routes/Approvals.tsx` | Fixed |
| D56 | P1 | Platform | The default 256KB WebClient response buffer is too small for a batch of embedding vectors (96 passages × 1536 dimensions), so any embed call past a handful of chunks failed with a buffer-limit error that presented as "the vector store is unavailable" - masked until now because Qdrant itself had never been run against this build | `platform-core/platform-defaults.yml` | Fixed |
| D57 | P0 | Web | The Members screen's outer `QueryState` (labelled "member:read") checked a combined `error = membersError \|\| rolesError`, so any role holding `member:read` but not `role:read`/`role:manage` (manager, employee, viewer - everyone but owner and admin) saw a false "your role does not include this" instead of the member list they actually had access to, because the *roles* query's real 403 leaked into the *members* gate | `routes/Members.tsx` | Fixed |
| D58 | P0 | Web | The Roles section of Members gated on `permission="role:manage"`, a string that does not exist anywhere in the permission catalogue (the real code is `role:read`) - the same defect class as D53, and just as total: nobody, including the owner, could ever see the Roles table | `routes/Members.tsx` | Fixed |
| D59 | P1 | API | `GET /api/roles/permissions` (the permission-code catalogue, non-sensitive build-time reference data) was gated behind `role:read`, so the Profile page's "what your role allows" - the one page every signed-in person should be able to open regardless of role - silently rendered empty for manager, employee and viewer. Loosened to `workspace:read`, which every role holds | `identity/web/RoleController.java` | Fixed |
| D60 | P1 | Web | Several mutating buttons rendered unconditionally regardless of the signed-in role's actual permission, so a person without the right to do something saw a fully clickable control that only failed after they filled out a form and submitted: "Add an agent" (`Agents.tsx`), "Edit instructions"/"Give it a task" (`AgentDetail.tsx`), "New goal"/"Cancel goal" (`Tasks.tsx`), "New run" (`CommandMap.tsx`), "Cancel run" (`RunDetail.tsx`), "Index again"/"Upload document" (`SourceDetail.tsx`), "Connect an account" (`Integrations.tsx`). All now gated on the real permission their backend endpoint requires | `routes/Agents.tsx`, `AgentDetail.tsx`, `Tasks.tsx`, `CommandMap.tsx`, `RunDetail.tsx`, `SourceDetail.tsx`, `Integrations.tsx` | Fixed |
| D61 | P0 | Web | `Approvals.tsx` computed `canDecide={decideApproval.mutate !== undefined}`, which is a `useMutation`'s function reference and is never undefined - so Approve/Reject rendered as fully functional buttons for every role, including `employee` and `viewer`, who hold `approval:read` but not `approval:decide` | `routes/Approvals.tsx` | Fixed |
| D62 | P0 | Web | Every dialog on the site (`<dialog>` shown via `showModal()`) opened pinned to the top-left of the viewport instead of centered - Tailwind's Preflight zeroes every element's default margin, `dialog` included, which breaks the browser's own `margin: auto` centering trick for a fixed, inset-0 modal. User-reported (screenshot), reproduced, and confirmed via computed style (`margin: 0px` instead of `auto`) | `styles/components.css` | Fixed |
| D63 | P0 | Web | The public home page was rebuilt end to end ("Living Console": a glass hero replicating the console, four live interactive demos - approval gate, provider failover, audit chain, cited answers - a role explorer, and a denser bento layout), per the user's explicit request for something "amazing, interactive, beautiful, futuristic, not spacious". Built via a judged design panel (3 directions scored and synthesized) and a 6-agent parallel build against one file plan | `routes/Landing.tsx`, `components/landing/**` (new), `hooks/**` (new), `styles/landing/**` (new) | Fixed |
| D64 | P0 | Web | The cited-answer demo's first citation named the wrong page of `Project_Proposal.pdf` (page 5; the quoted sentence is actually on page 6) - a visitor who opened the source document would find the platform's own flagship "every claim carries the right page" demo wrong on its own terms | `components/landing/answer/citedAnswerModel.ts` | Fixed |
| D65 | P1 | Web | The failover demo claimed a single 401 "opens a breaker"; the real circuit breaker only opens after a run of failures crosses its threshold, and a rejected credential is not actually remembered by the router between separate requests the way the demo implied. Reworded to describe what the demo actually remembers for itself, and what the real breaker does | `components/landing/failover/failoverModel.ts`, `FailoverDemo.tsx` | Fixed |
| D66 | P1 | Web | Three more overstated or invented product claims on the new page: the audit demo said "every decision, tool call and refusal is recorded" when only approval decisions and run outcomes are currently audit-logged (its sample entries were tool-call/refusal examples that do not match either); the approval demo said a run "resumes on the same model" three times, when resume re-resolves routing rather than pinning a model; the approval card showed "Example deadline: 30 min" when the real, non-configurable window is 24 hours | `components/landing/sections/AuditChainDemo.tsx`, `auditChain.ts`, `agent-run/approvalModel.ts`, `ApprovalDemo.tsx` | Fixed |
| D67 | P2 | Web | Two smaller factual slips on the new page: the role explorer said a role change "takes effect the next time somebody signs in" (it actually applies at the next token refresh, no re-sign-in needed); the CTA said live providers are "one stored key and a routing policy away" without mentioning the provider also has to be enabled | `components/landing/roles/RoleSwitcher.tsx`, `sections/LimitsAndCta.tsx` | Fixed |
| D68 | P1 | Web | Delayed `.focus()` calls in the new approval demo and role explorer had no `preventScroll`, so a visitor who had scrolled on to a later demo was yanked back to a card they had already left, 1.2-2s after clicking it | `agent-run/ApprovalDemo.tsx`, `roles/RoleSwitcher.tsx` | Fixed |
| D69 | P2 | Web | The demo tiles (`#approval`, `#failover`, etc.) had no `scroll-margin-top`, so an in-page link to one (from the hero's fact strip or console) landed with its head, including the required "Simulated" tag, hidden under the sticky glass bar; the skip link's target (`<main>`) was not focusable, so a keyboard user got no visible indication of where they landed | `styles/landing/base.css`, `routes/Landing.tsx` | Fixed |
| D70 | P1 | Web | The provider-failover fieldset used the native `disabled` attribute for its "busy while routing" state; disabling a fieldset a visitor's keyboard focus is inside (reached by tabbing a `<select>` into view, which is also what triggers autoplay) force-blurs that control to the document body. Switched to `aria-disabled` plus a guard in each control's own handler | `failover/FailoverDemo.tsx` | Fixed |
| D71 | P2 | Web | Rows dimmed with whole-element `opacity` in the approval demo took their text down with them, well under 4.5:1 in the parked state that reduced-motion visitors land on directly; the "Send a request" button, `aria-disabled` while routing, looked and hovered exactly like an active button, so a sighted visitor pressed it and nothing happened | `styles/landing/agent-run.css`, `styles/landing/base.css` | Fixed |
| D72 | P1 | Web | Selecting a workspace tool in an agent card used `role="group"` and `aria-pressed` (independent-toggle semantics) for what is actually a single-select, roving-tabindex, arrow-key-navigable group - a screen reader announcing "toggle button, pressed" implies picking another leaves this one on too. Changed to `role="radiogroup"` / `role="radio"` / `aria-checked` | `components/landing/sections/AgentsSection.tsx` | Fixed |
| D73 | P1 | Web | With "Viewer" selected, the approval card still showed the real request ("Approval requested · gmail.send_message", "Send the welcome email to the new hire") directly above a note saying a viewer does not see the approval queue - showing the exact thing while saying it cannot be seen. The card now shows a generic "Approval pending" placeholder for a viewer until a decision exists | `agent-run/ApprovalDemo.tsx` | Fixed |
| D74 | P2 | Web | The hero's simulated console had a screen-reader description asserting "four agents running", true of only one possible frame; the routing-trace panel rendered its placeholder rows at `visibility: hidden`, so the panel looked completely blank (not just quiet) for 0.8s at the start of every 10s loop and 1.2s at the end of each one, including on first paint | `hero/HeroConsole.tsx`, `styles/landing/hero.css` | Fixed |
| D75 | P0 | Backend | A resumed run rebuilt its conversation from the trace, but only a tool call's *name* was persisted, never its id or arguments - so the model's own tool-call turn was silently dropped from history and the approval step became a bare "you're approved, continue" message tied to nothing. Live testing found the actual consequence: approving "HR: send a welcome email" produced a second, near-identical send request, and then a third after approving that one too - a real, observed infinite approval loop for any agent that uses a tool needing approval, not a sandbox-only quirk. Fixed by persisting each call's id and arguments (`toolCallRecords` on the `model_call` step, `toolCallId` on `tool_call`/`approval` steps) and reconstructing the assistant's real tool-call turn, with a correctly linked tool-result turn, on resume. Verified live end to end: a fresh HR run drafted, asked to send, parked, and finished in exactly one approval after the fix, where it had looped three times before | `services/orchestrator-service/.../service/AgentRunner.java` | Fixed |
| D76 | P1 | Backend | A model provider occasionally answers 200 with empty content and no recognised finish reason - observed live, once, from OpenRouter's free routed Llama 3.3 70B - and the run completed anyway with a silent blank answer and nothing to say anything had gone wrong. Now treated as a failure with a clear reason ("The model returned no answer..."), so the task's existing retry rule tries again rather than a person seeing a completed run with nothing in it | `services/orchestrator-service/.../service/AgentRunner.java` | Fixed | |

Fixed during the last iteration and verified through the API: persist-versus-merge conflict that
broke creating a goal; internal service tokens rejected by every internal endpoint; members and
identity missing from the session; demo workspace had no agents.

Fixed this iteration and verified live in the browser: D39 (task-to-run linkage, plus new
`DataTable` row-link support so Command Map and Tasks now navigate to `/runs/{id}`), D45
(`TaskDialog` agent picker) and D46 (Command Map agent-name lookup). D26, D27, D28, D33, D34 and
D43 were re-checked against the current source and found already fixed from earlier work this
session; they are marked "Fixed, verify" pending a fresh live walkthrough.

Also fixed and verified this iteration, using a live OpenRouter key end to end: D38 (the
`Approval` entity already had a `payload` JSONB column - the Java code never read or wrote it) and
D47 (the demo workspace org-service row). Storing a real OpenRouter credential, enabling the
provider, inserting a workspace-default `model_policies` row by hand (no endpoint exists yet - see
D48), and running the HR agent produced a real `meta-llama/llama-3.3-70b-instruct` tool call
(`gmail.draft_message`, `gmail.send_message`), parked it for approval with the live arguments
visible in the Approvals screen, and completed on the same provider after approving.

D48 and D49 were then closed in the same iteration: added `ModelPolicyController` (workspace
default and per-agent routing policy, `GET`/`PUT /api/model-policy` and
`GET`/`PUT /api/agents/{id}/model-policy`, validated against known models) and a new "Routing
policy" card in Model routing that edits an ordered candidate chain and saves it. Fixed the
hard-coded `hasProviderManage = false` and wired the "Store key" dialog to the credential endpoint
that already existed. Verified live: signed in as owner, added a second (`anthropic`) candidate
behind the working `openrouter` one through the interface, saved, and confirmed both rows in
Postgres.

**Large parallel pass** (three background agents plus direct work on the gateway): D29/D42 (real
audit + analytics, `InternalAuditController`/`AuditController`/`AnalyticsController` in
analytics-service, a global hash chain, live-emitted from `ApprovalService.decide` and
`AgentRunner`'s terminal states), D30 (wired the orphaned `AssistantLauncher`), D31/D41 (member
role change, member removal, and a full invitation flow - create, copyable accept link, register,
auto-membership, sign in - since there is no SMTP anywhere in this platform), D37/D32 (re-verified
already correct, no change needed), D44 (the gateway: installed and started Redis via Homebrew,
then found and fixed three real, independent bugs that had kept it from ever running - D50
`spring-boot-starter-webflux` scoped `test` in its `pom.xml`, D51 zero security/rate-limit
configuration despite `application.yml` referencing beans that were never defined, D52 a JPA
mapping error in the new `AuditEvent` entity discovered only at runtime since `mvn clean install`
does not boot a full JPA context). Everything above was verified live: real HTTP traffic through
the gateway (200 authenticated, 401 unauthenticated), a real approval producing a real hash-chained
audit row reflected in both `/api/audit` and `/api/analytics`, and a full invite→accept→sign-in
round trip through the actual UI.

**Home page redesign** (D63-D74): built via a judged 3-direction design panel and a 6-agent
parallel build against one file plan (foundation, then hero / agent-run / failover-and-answer /
roles-and-sections in parallel, then integration), then reviewed by four independent lenses
(design-system and factual truth, accessibility, React correctness and performance, layout and
density), each finding checked by a skeptical verifier before being fixed. 39 findings came back;
12 were applied above after independent re-verification (some verifier passes hit a spend limit
mid-run, so several were re-confirmed directly against the real backend code - PDF page numbers,
`ModelRouter`/breaker behaviour, `AuditClient` wiring, `resume()`, token refresh - rather than
taken on the reviewer's word alone). `tsc -b`, `pnpm lint`, all 107 tests and `pnpm build` pass
after every change in this round.

Found but deliberately left open, mostly layout/density refinements rather than defects a visitor
would call broken: the hero drops to one narrow, left-aligned column between roughly 1024 and
1179px instead of a balanced two-column layout; the failover tile is still the tallest on the page
and leaves a blank area in its row neighbour, though its trace is now capped shorter; the role
explorer's role list leaves an empty column beside the longer details panel on wide screens; a few
narrow-band wrapping issues around 1180-1260px (the approval stage, the cited-answer split, the
hero fact-strip labels); the hover-spotlight effect and the role counter's per-frame state each
cause more re-rendering than they need to; Google Fonts is still loaded from a CDN, which is a
whole-app decision from before this redesign, not something introduced by it; and the new
"landing styles" design-system test has a few gaps (a border-longhand regex, no check that every
`--lp-*` custom property is actually defined, no zero-emoji check) that would let a future
regression through undetected. None of these were found to make the page actually break, mislead,
or become unusable - they are recorded here so a later pass can pick them up deliberately rather
than rediscover them.

**Usability pass, 27 September 2026** — the signed-in console, reviewed from a first-time
evaluator's perspective first, then a daily operator's. Seven parallel lenses (evaluator journey,
data presentation, forms and errors, navigation, system-status truthfulness, operator efficiency,
responsive/accessibility) produced 201 raw findings; deduplication and an adversarial verify pass
against the real code kept 67. Applied in dependency order:

- **Shared foundation** (`lib/format.ts`, `lib/labels.ts`, `lib/routing.ts`, `lib/onboarding.ts`,
  `lib/useListFilter.ts`, `components/ui/FilterBar.tsx`): one place for humanised times, durations,
  money and every status vocabulary in the platform (run, task, goal, approval, agent, source,
  document, integration, invitation, outcome, circuit, tool call), a shared computation of whether
  live model routing is really active (used identically by the Command Map banner and Model
  Routing, rather than each screen inventing its own), and a reusable search/filter bar with its
  state kept in the URL.
- **Backend**: `GET /api/runs` gained `status`/`agentId` filters and a new `/runs` list route uses
  them server-side rather than filtering only the page already loaded; a task and its goal now
  follow their run to a real conclusion instead of sticking on "Waiting for approval" forever
  (`TaskProgress`, a new reconciling sweep in `MaintenanceScheduler`); cancelling a goal now stops
  its run and withdraws its approval instead of leaving both stranded; run cost is recorded from
  real usage and backfilled for existing runs (Flyway V3); the instruction a run was given is kept
  in its own trace step; the gateway gained routes for `/api/orgs/**` and `/api/model-policy/**`
  that were missing behind the production gateway.
- **Five screen areas**, each owning a disjoint set of files: the app shell, navigation and the new
  `/runs` page; the Command Map, run trace and task dialog (including the getting-started guide);
  agents, tasks and approvals; knowledge, chat and integrations; and administration (model routing,
  members, audit, analytics). Every screen owner then re-verified its own findings adversarially
  and applied cross-area requests from the other owners.

Concretely, this closed the defects the user had already spotted plus everything the audit added:
raw agent UUIDs and raw status codes replaced by names and plain language everywhere; durations
that read "84817s" now read "23 h 05 min"; the Model Routing cost cells that rendered the literal
text `<span className="stat-unit">USD</span>` now render `US$0.30`; the Circuit column's raw
`CLOSED` now reads "Healthy"; a stored, enabled, working OpenRouter credential no longer produces
"No model provider is configured" banners on the Command Map or Model Routing, both of which now
say what is really routing; Source detail no longer claims documents "need attention" when every
one is indexed; a getting-started guide, filtered to what each role can actually do, appears on
first visit; every long list (runs, tasks, audit, members) can be searched and filtered.

Verifying it live surfaced two further backend defects neither the audit nor the plan had reason to
expect, because they only show up when a real, non-sandbox model is actually driving a multi-step,
approval-gated run rather than the sandbox model or a single-step run — see D75 and D76. D75 in
particular was a real, reproduced infinite approval loop, found by walking the HR agent's
send-an-email flow live with the stored OpenRouter key rather than assuming the sandbox-verified
path generalised.

Verified after every change: `pnpm exec tsc -b`, `pnpm lint`, `pnpm exec vitest run` (192 tests),
`pnpm build`, a full Maven reactor `install` (all modules), and the orchestrator's own test suite
(63 tests, including the two added for D75 and D76) all green. Walked live in the browser as
manager (task creation, an approval that previously would have looped, its resolution, run trace,
Runs list, Knowledge, Chat, Model Routing, Members, Audit Log), as admin (Audit Log content), and
as viewer (getting-started guide correctly narrowed to three read-only steps, no task or approval
actions offered) - plus a deliberate wrong-password sign-in to confirm error handling.

---

## 11. Known platform gaps outside this plan

These are real, stated so nothing here is mistaken for them.

- OAuth for tool servers is not implemented; every server runs against its sandbox. This needs a
  registered OAuth application per real provider (Google, Slack, GitHub, ...), which is a business
  decision outside what a code fix can supply.
- Kafka is wired but the orchestrator drives tasks synchronously. Moving execution onto the bus is
  an architectural change, not a defect fix, and risks the currently-working, tested synchronous
  path for no benefit this project needs yet.
- Only upload ingestion exists; the Drive, Notion, Confluence and GitHub wiki connectors are not
  written.

Closed this iteration, previously listed here as gaps:

- **Live model-provider calls** — a real OpenRouter key was stored, enabled, routed to, and used
  for both a plain completion and a tool-calling run with a live approval in between.
- **Vector search against a live Qdrant** — Docker Desktop and a Qdrant container were started
  locally; reindexing the demo corpus surfaced D56 (a WebClient buffer-size default too small for
  a batch of embedding vectors), and once fixed a real hybrid search returned genuinely-ranked,
  cited passages from both project PDFs.
- **The gateway** — Redis installed locally; D50/D51 fixed and verified with real authenticated
  and unauthenticated traffic.

---

## 12. Exit criteria

The work is complete when all of the following hold:

1. `mvn install`, `pnpm exec tsc -b`, `pnpm test` and `pnpm build` pass with no failures.
2. Journeys J1–J15 pass in the browser for every role listed against them.
3. Every row in the Phase 2 table is ticked.
4. Every check in Phase 3 passes, including zero axe-core violations.
5. The RBAC matrix in Phase 4 matches section 3 for every endpoint and every role.
6. The Playwright journeys in Phase 5 run green.
7. The defect register holds no P0 or P1 items, and every remaining P2 has a stated reason.
8. No screen shows a number, name or record that did not come from the platform.

---

## 13. Execution order

| Step | Work | Size |
|---|---|---|
| 1 | Phase 0: green baseline | S |
| 2 | Shared "give a task" dialog; wire Agents, Agent detail, Tasks, Run detail (J4, J5, J7) | L |
| 3 | Wire Approvals; store and show the approval payload (J6, D38) | M |
| 4 | Wire Command Map with real figures and an onboarding checklist (D28) | M |
| 5 | Wire Knowledge, Source detail and Chat (J8, J9) | M |
| 6 | Wire Integrations, Model routing, Members and roles (J10–J12) | M |
| 7 | Decide and implement audit, analytics and create-workspace, or remove them (J14, J15) | M |
| 8 | Phase 2 consistency pass across every screen | M |
| 9 | Phase 3 responsiveness and accessibility | M |
| 10 | Phase 4 API verification by role | S |
| 11 | Phase 5 automated tests | L |
| 12 | Final walk of every journey for every role; close the register | M |
