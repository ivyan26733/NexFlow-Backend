# NexFlow Backend

A no-code workflow automation platform. Users build visual flows in **Studio**, connect API calls via **Nexus**, and fire them via **Pulse**.

---

## Architecture Overview

```
Postman / HTTP Client
        ↓
   POST /api/pulse/{flowSlug}
        ↓
   FlowService → creates Execution record
        ↓
   FlowExecutionEngine → BFS-walks the DAG
        ↓
   NodeExecutorRegistry → dispatches each node
        ↓
   ExecutionEventPublisher → WebSocket (RabbitMQ STOMP)
        → Studio gets live node status updates
        ↓
   Execution + NodeExecution logs saved to PostgreSQL
```

---

## The 3 Pillars

| Pillar  | What it is |
|---------|------------|
| **Pulse**  | HTTP trigger that starts a flow. Hit from any client via `POST /api/pulse/{flowSlug}` |
| **Nexus**  | Where users configure reusable API connectors (URL, method, headers, body template) |
| **Studio** | The visual canvas (frontend) where users drag, drop, and stitch nodes together |

---

## Node Types

| Node | Description |
|------|-------------|
| `START` | Entry point. Injected with the Pulse payload automatically |
| `NEXUS` | Fires an HTTP call via a saved Nexus connector. Has `SUCCESS` and `FAILURE` outputs |
| `VARIABLE` | Defines static or referenced variables available to all downstream nodes |
| `MAPPER` | Shapes a new payload by picking and renaming fields from any previous node output |
| `DECISION` | Evaluates a condition (e.g. `amount > 500`). Routes `SUCCESS` or `FAILURE` edge |
| `SCRIPT` | Runs user-supplied JavaScript or Python in a sandboxed subprocess |
| `FORK` | Splits execution into parallel branches |
| `JOIN` | Waits for all branches to complete, then merges their outputs |
| `AI` | Sends a prompt to a configured LLM provider (OpenAI, Gemini, etc.) |
| `SUB_FLOW` | Executes another saved flow as a nested step |
| `SUCCESS` | Terminal node. Marks flow as successful, returns a shaped response |
| `FAILURE` | Terminal node. Marks flow as failed, returns error info |

### Adding a New Node Type (e.g. Kafka)
1. Add `KAFKA_PRODUCER` to `NodeType` enum
2. Create `KafkaProducerExecutor implements NodeExecutor`
3. Annotate with `@Component` — the registry auto-picks it up
4. No changes to the engine or registry needed

---

## The NCO (Nexflow Context Object)

The NCO is a shared data envelope that travels through the entire flow. Every node reads from it and writes its result back to it.

```json
{
  "meta": {
    "flowId": "...",
    "executionId": "...",
    "status": "RUNNING"
  },
  "variables": {
    "userId": "abc123",
    "threshold": 500
  },
  "nodes": {
    "start_001": {
      "status": "SUCCESS",
      "output": { "body": { "amount": 1000 } }
    },
    "fetchUser_002": {
      "status": "SUCCESS",
      "successOutput": { "statusCode": 200, "body": { "plan": "premium" } }
    }
  },
  "nex": {
    "userId": "abc123",
    "fetchUser": { "statusCode": 200, "body": { "plan": "premium" } },
    "start": { "body": { "amount": 1000 } }
  }
}
```

### Reference Syntax

Any node config value can reference any previously resolved data using `{{...}}`:

```
{{variables.userId}}
{{nodes.fetchUser_002.successOutput.body.plan}}
{{nodes.start_001.output.body.amount}}
{{meta.executionId}}
```

### `nex` Unified Context (preferred)

`nex` is a flat shorthand built on top of the NCO. Prefer this in SCRIPT nodes and MAPPER configs:

```
nex.userId              → trigger field or variable named userId
nex.fetchUser           → full output of the node labelled "Fetch User"
nex.fetchUser.body.plan → nested field access
nex.start               → full trigger payload {body: {...}}
```

---

## Script Sandboxing

SCRIPT nodes run user-supplied JavaScript (`node`) or Python (`python3`) in a subprocess.

**Two-layer infinite loop protection:**

| Layer | Mechanism | What it catches |
|-------|-----------|-----------------|
| **CPU watchdog** | Polls `ProcessHandle.info().totalCpuDuration()` every 2s. Kills if CPU > 90% for 5 consecutive samples (10s) | `while(true) { a++ }` — true infinite loops |
| **Wall-clock timeout** | Per-node configurable, default 10s, max 300s | I/O stuck forever (e.g. TCP connect to unresponsive host) |

**Blocked patterns** — the following module imports/calls are rejected before execution:
- JS: `require('child_process')`, `require('fs')`, `require('net')`, `process.env`, `process.exit`, etc.
- Python: `import subprocess`, `import os`, `import sys`, `exec(`, `eval(`, `open(`, etc.

**Relevant classes:**
- `engine/ScriptRunner.java` — subprocess execution, CPU watchdog, wrappers
- `executor/impl/ScriptExecutor.java` — reads node config, calls `ScriptRunner.run()`

---

## Security

| Feature | Implementation |
|---------|----------------|
| **Authentication** | JWT in HttpOnly cookie (production) or response body (local dev) |
| **Authorization** | `JwtAuthFilter` validates token on every request; user ID extracted from claims |
| **Rate limiting** | `RateLimitFilter` with token bucket (Bucket4j) — 20 req/s per IP |
| **IDOR protection** | All flow/execution/connector endpoints verify ownership against `userId` from JWT |
| **SSRF protection** | NEXUS node rejects private IP ranges (10.x, 172.16–31.x, 192.168.x, 127.x, localhost) |
| **Security headers** | HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy via Spring Security |
| **Script sandbox** | Blocked module imports + CPU watchdog + wall-clock timeout |

---

## Project Structure

```
src/main/java/com/nexflow/
│
├── NexflowBackendApplication.java
│
├── model/
│   ├── domain/          # JPA entities
│   │   ├── Flow.java
│   │   ├── FlowNode.java / FlowEdge.java
│   │   ├── Execution.java / NodeExecution.java
│   │   ├── BranchExecution.java
│   │   ├── NexUser.java / UserGroup.java / GroupMember.java
│   │   ├── NexusConnector.java
│   │   ├── LlmProviderConfig.java
│   │   └── NodeType.java (enum)
│   └── nco/             # NCO classes (NexflowContextObject, NodeContext, enums)
│
├── executor/
│   ├── NodeExecutor.java          # Interface every executor implements
│   ├── NodeExecutorRegistry.java  # Auto-registers all @Component executors
│   ├── ReferenceResolver.java     # Resolves {{ref}} expressions from NCO
│   └── impl/
│       ├── NexusExecutor.java
│       ├── ScriptExecutor.java
│       ├── ForkNodeExecutor.java
│       ├── JoinNodeExecutor.java
│       ├── AiNodeExecutor.java
│       └── SubFlowExecutor.java
│       └── (StartExecutor, VariableExecutor, MapperExecutor, DecisionExecutor, TerminalExecutors)
│
├── engine/
│   ├── FlowExecutionEngine.java      # BFS DAG walker + fork/join orchestration
│   ├── ScriptRunner.java             # Script subprocess + CPU watchdog
│   └── ExecutionEventPublisher.java  # WebSocket broadcaster (RabbitMQ STOMP)
│
├── security/
│   ├── JwtTokenProvider.java    # Sign + verify JWT
│   ├── JwtAuthFilter.java       # Per-request token validation
│   ├── RateLimitFilter.java     # Token-bucket rate limiter (Bucket4j)
│   └── OAuth2SuccessHandler.java
│
├── controller/
│   ├── PulseController.java           # POST /api/pulse/{flowSlug}
│   ├── FlowController.java            # Flow CRUD + canvas save/load
│   ├── ExecutionController.java       # Execution history + detail
│   ├── BranchExecutionController.java # Fork/join branch status
│   ├── NexusController.java           # Nexus connector CRUD
│   ├── AuthController.java            # Login, signup, OTP, refresh
│   ├── AssistantController.java       # AI assistant chat
│   ├── GroupController.java           # User groups + sharing
│   ├── LlmProviderConfigController.java
│   ├── AdminController.java
│   └── GlobalExceptionHandler.java
│
├── service/
│   ├── FlowService.java                      # Execution orchestration + persistence
│   ├── UserService.java
│   ├── GroupService.java
│   ├── AssistantService.java
│   ├── NodeExecutionPersistenceService.java
│   └── BranchExecutionPersistenceService.java
│
├── repository/           # Spring Data JPA repositories
│
└── config/               # AppConfig, WebSocket, Security, RabbitMQ beans
```

---

## API Reference

### Trigger a Flow
```
POST /api/pulse/{flowSlug}
Content-Type: application/json

{ "userId": "abc123", "amount": 1000 }
```

### Flow CRUD
```
GET    /api/flows                    # list flows for current user
POST   /api/flows                    # create flow
GET    /api/flows/{flowId}/canvas    # load canvas (nodes + edges)
POST   /api/flows/{flowId}/canvas    # save canvas
DELETE /api/flows/{flowId}           # delete flow
```

### Execution History
```
GET /api/executions                  # all executions (transactions page)
GET /api/flows/{flowId}/executions   # executions for a specific flow
GET /api/executions/{executionId}    # execution detail + node logs
```

### Nexus Connectors
```
GET    /api/nexus/connectors
POST   /api/nexus/connectors
PUT    /api/nexus/connectors/{id}
DELETE /api/nexus/connectors/{id}
```

### Auth
```
POST /api/auth/signup
POST /api/auth/login
POST /api/auth/verify-otp
POST /api/auth/forgot-password
POST /api/auth/reset-password
POST /api/auth/refresh
POST /api/auth/logout
```

---

## Node Config Shapes

### NEXUS node
```json
{
  "connectorId": "uuid-of-saved-connector",
  "body": { "userId": "{{variables.userId}}" }
}
```

### VARIABLE node
```json
{
  "variables": {
    "userId":   "static-value",
    "userPlan": "{{nodes.fetchUser_001.successOutput.body.plan}}"
  }
}
```

### MAPPER node
```json
{
  "output": {
    "email":  "{{variables.email}}",
    "amount": "{{nodes.start_001.output.body.amount}}",
    "source": "nexflow"
  }
}
```

### DECISION node
```json
{
  "left":     "{{variables.amount}}",
  "operator": "GT",
  "right":    "500"
}
```
Supported operators: `GT`, `LT`, `GTE`, `LTE`, `EQ`, `NEQ`, `CONTAINS`

### SCRIPT node
```json
{
  "language":       "javascript",
  "code":           "const items = nex.fetchOrders?.body?.items ?? []\nreturn items.filter(i => i.active)",
  "timeoutSeconds": 30
}
```
Languages: `javascript` (via `node`), `python` (via `python3`). Default timeout 10s, max 300s.

### FORK node
```json
{ "branches": ["branch-a", "branch-b"] }
```

### JOIN node
```json
{ "strategy": "WAIT_ALL" }
```

### AI node
```json
{
  "providerId": "uuid-of-llm-config",
  "prompt":     "Summarise this order: {{nex.fetchOrder.body}}"
}
```

### SUCCESS / FAILURE node
```json
{
  "response": {
    "message": "Payment processed",
    "userId":  "{{variables.userId}}"
  }
}
```

---

## WebSocket (Live Execution Updates)

The frontend connects to the RabbitMQ STOMP relay and subscribes to:
```
/topic/execution/{executionId}
/topic/branch/{branchExecutionId}
```

Each node status message:
```json
{ "nodeId": "node_002", "status": "SUCCESS", "error": "" }
```

**Ports:**
- AMQP (Spring Boot ↔ RabbitMQ): `5672`
- STOMP relay (frontend WebSocket): `61613`

`ExecutionEventPublisher` publishes events. If the broker is unavailable, events are dropped with a warning log — flow execution continues unaffected.

---

## Setup

### Prerequisites
- Java 17+
- PostgreSQL running on port 5432
- RabbitMQ with STOMP plugin enabled (port 5672 AMQP, 61613 STOMP)

### Database
```sql
CREATE DATABASE nexflow;
```
JPA will auto-create/update tables on first run (`ddl-auto: update`).

### Local Run
```bash
./mvnw spring-boot:run
```
Or with the prod profile:
```bash
java -jar target/nexflow-backend.jar --spring.profiles.active=prod
```

### Environment Variables (prod)
| Variable | Description |
|----------|-------------|
| `DB_URL` | JDBC URL e.g. `jdbc:postgresql://localhost:5432/nexflow` |
| `DB_USERNAME` | Postgres user |
| `DB_PASSWORD` | Postgres password |
| `RABBITMQ_HOST` | RabbitMQ host |
| `RABBITMQ_AMQP_PORT` | AMQP port (default 5672) |
| `RABBITMQ_STOMP_PORT` | STOMP relay port (default 61613) |
| `RABBITMQ_USERNAME` | RabbitMQ user |
| `RABBITMQ_PASSWORD` | RabbitMQ password |
| `JWT_SECRET` | 256-bit secret for JWT signing |
| `APP_LOCAL_DEV` | `true` → token in response body; `false` → HttpOnly cookie |

---

## Design Patterns Used

| Pattern | Where |
|---------|-------|
| **Strategy** | `NodeExecutor` interface — each node type is a strategy |
| **Registry** | `NodeExecutorRegistry` — Spring auto-discovers all `@Component` executors |
| **Builder** | NCO construction before flow execution |
| **Observer** | `ExecutionEventPublisher` broadcasts node status to WebSocket subscribers |
| **Filter Chain** | `JwtAuthFilter` → `RateLimitFilter` in Spring Security filter chain |
| **BFS** | `FlowExecutionEngine` walks the DAG breadth-first |
