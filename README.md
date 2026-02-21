# NexFlow Backend

REST API and execution engine for **NexFlow** — a no-code workflow automation platform. Built with Spring Boot and Java 17.

## Tech Stack

- **Framework:** Spring Boot 3.2.x
- **Java:** 17
- **Data:** Spring Data JPA (Hibernate), H2 (default) / configurable DB
- **Web:** Spring Web, STOMP over WebSocket for execution events

## Prerequisites

- Java 17+
- Maven 3.6+

## Build & Run

```bash
# Compile
mvn compile

# Run
mvn spring-boot:run
```

Server runs by default on **http://localhost:8090**.

## Configuration

Edit `src/main/resources/application.properties`:

- **Server port:** `server.port=8090`
- **Database:** Default is H2 (in-memory or file). Change URL/driver for PostgreSQL, etc.
- **CORS:** Allowed origins are configured for the frontend (see `config/CorsConfig.java`).

## API Overview

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/flows` | List all flows |
| POST | `/api/flows` | Create flow |
| GET | `/api/flows/{flowId}` | Get one flow |
| GET | `/api/flows/{flowId}/canvas` | Load canvas (nodes + edges) |
| POST | `/api/flows/{flowId}/canvas` | Save canvas (body: `{ "nodes": [], "edges": [] }`) |
| GET | `/api/flows/{flowId}/executions` | List executions |
| POST | `/api/pulse/{flowId}` | Trigger execution (body: JSON payload) |

### Canvas Save

- Request body: `CanvasSaveDto` with `nodes` (array of `FlowNodeDto`) and `edges` (array of `FlowEdgeDto`).
- Node IDs and edge IDs are **provided by the frontend** (UUIDs). The backend **must not** overwrite them (see `FlowNode` and `FlowEdge` entities: `@Id` only, no `@GeneratedValue`).
- Edges reference nodes by `sourceNodeId` and `targetNodeId`; these must match stored node IDs.

## Project Structure

```
nexflow-backend/
├── pom.xml
├── src/main/java/com/nexflow/nexflow_backend/
│   ├── NexflowBackendApplication.java
│   ├── EdgeCondition.java          # Enum: SUCCESS, FAILURE, DEFAULT, CUSTOM
│   ├── FlowStatus.java
│   ├── controller/
│   │   ├── FlowController.java     # Flows + canvas + executions
│   │   └── PulseController.java    # Trigger execution
│   ├── model/
│   │   ├── domain/                 # JPA entities
│   │   │   ├── Flow.java
│   │   │   ├── FlowNode.java
│   │   │   ├── FlowEdge.java
│   │   │   ├── Execution.java
│   │   │   └── NodeType.java
│   │   ├── dto/
│   │   │   ├── CanvasSaveDto.java
│   │   │   ├── FlowNodeDto.java
│   │   │   └── FlowEdgeDto.java
│   │   └── nco/                    # Execution context types
│   ├── repository/
│   │   ├── FlowRepository.java
│   │   ├── FlowNodeRepository.java
│   │   ├── FlowEdgeRepository.java
│   │   └── ExecutionRepository.java
│   ├── service/
│   │   └── FlowService.java
│   ├── engine/                    # Flow execution
│   │   ├── FlowExecutionEngine.java
│   │   └── ExecutionEventPublisher.java
│   ├── executor/                  # Node executors (START, PULSE, etc.)
│   │   ├── NodeExecutor.java
│   │   ├── NodeExecutorRegistry.java
│   │   └── ...
│   └── config/
│       ├── AppConfig.java
│       └── CorsConfig.java
└── src/main/resources/
    └── application.properties
```

## Important Notes

- **IDs:** `FlowNode` and `FlowEdge` use `@Id` **without** `@GeneratedValue`. The frontend sends UUIDs for nodes and edges; the backend persists them as-is. Do not add `@GeneratedValue` or canvas edges will no longer match nodes after reload.
- **Canvas save:** Uses DTOs (`CanvasSaveDto`, `FlowNodeDto`, `FlowEdgeDto`) and `@Transactional`. Invalid edges (e.g. null source/target) are skipped; valid ones are saved.

## License

Private / as per project.
