# Nexflow Backend

A no-code workflow automation platform. Users build visual flows in **Studio**, connect API calls via **Nexus**, and fire them via **Pulse**.

---

## Architecture Overview

```
Postman / HTTP Client
        ↓
   POST /api/pulse/{flowId}
        ↓
   FlowService → creates Execution record
        ↓
   FlowExecutionEngine → walks the DAG
        ↓
   NodeExecutorRegistry → dispatches each node
        ↓
   WebSocket → Studio gets live node status updates
        ↓
   Execution saved to PostgreSQL
```

---

## The 3 Pillars

| Pillar  | What it is |
|---------|------------|
| **Pulse**  | HTTP trigger that starts a flow. Hit from Postman or any client via `POST /api/pulse/{flowId}` |
| **Nexus**  | Where users configure API calls (URL, method, headers, body) as reusable connector nodes |
| **Studio** | The visual canvas (frontend) where users drag, drop, and stitch nodes together |

---

## Node Types

| Node | Description |
|------|-------------|
| `START` | Entry point. Injected with the Pulse payload automatically |
| `PULSE` | Fires an HTTP call. Has two outputs: `SUCCESS` and `FAILURE` |
| `VARIABLE` | Defines static or referenced variables available to all subsequent nodes |
| `MAPPER` | Shapes a new request payload by picking fields from any previous node output |
| `DECISION` | Evaluates a condition (e.g. amount > 500). Routes `SUCCESS` or `FAILURE` edge |
| `SUCCESS` | Terminal node. Marks flow as successful, returns a shaped response |
| `FAILURE` | Terminal node. Marks flow as failed, returns error info |

### Adding a New Node Type (e.g. Kafka)
1. Add `KAFKA_PRODUCER` to `NodeType` enum
2. Create `KafkaProducerExecutor implements NodeExecutor`
3. Annotate with `@Component` — the registry auto-picks it up
4. No changes to the engine needed

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
    "pulse_002": {
      "status": "SUCCESS",
      "successOutput": { "statusCode": 200, "body": { "plan": "premium" } }
    }
  }
}
```

### Reference Syntax
Any node config value can reference any previously resolved data using `{{...}}`:

```
{{variables.userId}}
{{nodes.pulse_002.successOutput.body.plan}}
{{nodes.start_001.output.body.amount}}
{{meta.executionId}}
```

---

## Project Structure

```
src/main/java/com/nexflow/
│
├── NexflowApplication.java
│
├── model/
│   ├── domain/          # JPA entities (Flow, FlowNode, FlowEdge, Execution)
│   └── nco/             # NCO classes (NexflowContextObject, NodeContext, enums)
│
├── executor/
│   ├── NodeExecutor.java          # Interface every executor implements
│   ├── NodeExecutorRegistry.java  # Auto-registers all @Component executors
│   ├── ReferenceResolver.java     # Resolves {{ref}} expressions from NCO
│   └── impl/
│       ├── StartExecutor.java
│       ├── PulseExecutor.java
│       ├── VariableExecutor.java
│       ├── MapperExecutor.java
│       ├── DecisionExecutor.java
│       └── TerminalExecutors.java  # SuccessExecutor + FailureExecutor
│
├── engine/
│   ├── FlowExecutionEngine.java    # Core DAG walker
│   └── ExecutionEventPublisher.java # WebSocket broadcaster
│
├── repository/           # Spring Data JPA repositories
├── service/
│   └── FlowService.java  # Execution orchestration + persistence
├── controller/
│   ├── PulseController.java  # POST /api/pulse/{flowId}
│   └── FlowController.java   # CRUD + canvas save/load
└── config/
    └── AppConfig.java    # RestTemplate, WebSocket, ObjectMapper beans
```

---

## API Reference

### Trigger a Flow
```
POST /api/pulse/{flowId}
Content-Type: application/json

{
  "userId": "abc123",
  "amount": 1000
}
```

### Create a Flow
```
POST /api/flows
{ "name": "My First Flow", "description": "..." }
```

### Save Canvas (nodes + edges)
```
POST /api/flows/{flowId}/canvas
{
  "nodes": [ { "nodeType": "START", "positionX": 100, "positionY": 200, ... } ],
  "edges": [ { "sourceNodeId": "...", "targetNodeId": "...", "conditionType": "SUCCESS" } ]
}
```

### Load Canvas
```
GET /api/flows/{flowId}/canvas
```

### Get Execution History
```
GET /api/flows/{flowId}/executions
```

---

## Node Config Shapes

### PULSE node
```json
{
  "url":     "https://api.example.com/users",
  "method":  "POST",
  "headers": { "Authorization": "Bearer {{variables.token}}" },
  "body":    { "userId": "{{variables.userId}}" }
}
```

### VARIABLE node
```json
{
  "variables": {
    "userId":    "static-value",
    "userPlan":  "{{nodes.pulse_001.successOutput.body.plan}}"
  }
}
```

### MAPPER node
```json
{
  "output": {
    "email":   "{{variables.email}}",
    "amount":  "{{nodes.start_001.output.body.amount}}",
    "source":  "nexflow"
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

Studio connects to `ws://localhost:8080/ws` and subscribes to:
```
/topic/execution/{executionId}
```

Each message:
```json
{ "nodeId": "node_002", "status": "SUCCESS", "error": "" }
```

Studio uses this to highlight nodes on the canvas in real time.

---

## Setup

### Prerequisites
- Java 17+
- PostgreSQL running on port 5432
- Redis running on port 6379

### Database
```sql
CREATE DATABASE nexflow;
```
JPA will auto-create tables on first run (`ddl-auto: update`).

### Run
```bash
./mvnw spring-boot:run
```

---

## What's Next (Future Sprints)

| Feature | Notes |
|---------|-------|
| `KafkaProducerExecutor` | Produce to a topic — just add `@Component`, zero engine changes |
| `SqlScriptExecutor` | Run a SQL query, output rows into NCO |
| `DelayExecutor` | Wait N seconds between nodes |
| Immutable NCO / Replay | Store NCO snapshot per node for full execution replay |
| Scheduled Pulse | Cron-based trigger instead of HTTP |
| Authentication | JWT-based user auth |
| Multi-tenancy | Scope flows and executions per user/org |
