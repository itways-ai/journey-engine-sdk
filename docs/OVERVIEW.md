# Journey Engine SDK — Full Documentation

> **Type:** Internal Java SDK / Library
> **Group:** `com.itways.assistant` | **Artifact:** `journey-engine-sdk` | **Version:** `1.0.0`
> **Java:** 21 | **Spring Boot:** 3.2.0 

---

## 1. High-Level Overview

`journey-engine-sdk` is an embeddable workflow execution engine for the AI Assistant Platform.

**What it does:**
It takes a static JSON representation of a workflow (a `Journey` and its `JourneyStep`s) and actually **runs** it. It traverses the execution graph, evaluates conditional branching, resolves variables contextually, and triggers the actual handlers (like making HTTP calls, sending emails, or asking the user for input).

**Its role in the system:**
If `journey-service` is the "blueprint archive", this SDK is the "construction crew". 
It has no database of its own—it solely relies on in-memory state tracking via an `ExecutionContext` that holds variables generated as steps execute.

**Who uses it:**
Currently, it is heavily consumed by `speech-service` (the orchestrator) which uses this engine to fulfill user intents.

---

## 2. Architecture / Structure

The SDK is built using a highly extensible **Strategy Pattern** combined with a **DAG (Directed Acyclic Graph) Traversal** mechanism.

### Key Components

- **Engine Core** (`JourneyEngineImpl`): Sorts the steps using a Depth First Search (DFS) based on parent-child relationships, manages branching eligibility, and tracks execution context state.
- **Handler Registry** (`StepHandlerRegistry`): A dynamic Spring-managed map of `StepHandler` implementations.
- **Step Handlers**: Stateless, single-purpose classes implementing `StepHandler.execute()`. Examples:
  - `ApiCallStepHandler`
  - `ConditionStepHandler`
  - `DataMapStepHandler`
  - `UserInputStepHandler`
  - `ResponseStepHandler`
  - `MailStepHandler`

### Key Design Principles
- **Extensibility**: To add a new type of step (e.g., `SEND_SLACK_MSG`), a developer only needs to implement `StepHandler` and return `"SEND_SLACK_MSG"` from `getType()`. The `StepHandlerRegistry` automatically picks it up at boot time.
- **In-Memory Context (`ExecutionContext`)**: Variables cascade step-by-step. If Step 1 returns `{"userId": 123}`, Step 2 can access `123` via placeholder string replacement (e.g., `/users/${userId}`).

---

## 3. Public APIs / Methods

### `JourneyEngine` (The Main Entrypoint)

This is the primary interface exposed to host applications.

```java
public interface JourneyEngine {
	// Starts a brand new execution of a journey
	Map<String, Object> start(Journey journey, String accountId, Map<String, Object> initialParams);

	// Resumes a paused execution (such as after waiting for USER_INPUT)
	Map<String, Object> resume(Journey journey, ExecutionContext context, Map<String, Object> inputParams);
}
```

**Input/Output:**
- Takes a `Journey` object (the graph), tenant ID, and seed variables.
- Returns a `Map<String, Object>` containing:
  - `status`: `"FINISHED"`, `"WAITING"` (Paused for input), or `"ERROR"`.
  - `stepResults`: A history array of what happened at each step.
  - `context`: The final execution variables block.
  - `message`: The final string meant to be spoken or shown to the user.

### Auto-Configuration (`@EnableJourneyEngine`)
To activate the SDK in a microservice, place this annotation on the main application class. It imports `JourneyConfiguration` to initialize all beans.

```java
@EnableCommon
@EnableCustomSecurity
@EnableJourneyEngine // <--- Registers all engine beans and handlers
@SpringBootApplication
public class AssistanceServiceApplication { ... }
```

---

## 4. Dependencies & SDK Usage

Unlike typical microservices, this SDK embeds other libraries to do heavy lifting on behalf of the host application.

### 📦 INTERNAL SDK: `ai-engine-sdk`
**Purpose/Usage:** 
The journey engine sometimes hits a step like `DATA_MAP`, which requires taking unstructured user text and pulling out structured JSON parameters using an LLM.

**Where it is used:**
It is used in `DataMapStepHandler.java` (inferred from imports) to dynamically extract entities (like dates, names, or quantities) from raw user input text on-the-fly without hardcoded RegEx patterns.

### 📦 EXTERNAL DEPENDENCY: `spring-boot-starter-freemarker`
**Purpose/Usage:**
Used internally in `TemplateRenderHandler` or `TemplateRender` configurations to render dynamic text outputs by injecting variables from the `ExecutionContext` into `.ftl` templates.

### 📦 EXTERNAL DEPENDENCY: `RestTemplate` (`spring-boot-starter-web`)
**Purpose/Usage:**
Used heavily inside `ApiCallStepHandler` to make synchronous HTTP calls to 3rd party APIs based on the `apiConfig` JSON defined in the journey step.

---

## 5. Usage Examples

### Typical Host Service Workflow

```java
@Service
public class ChatOrchestrator {
    
    @Autowired
    private JourneyEngine journeyEngine;

    public void runIntent(Journey journey, String userText) {
        
        // 1. Setup seed variables
        Map<String, Object> params = new HashMap<>();
        params.put("text", userText); // Will be read by DATA_MAP handlers
        
        // 2. Start the engine
        Map<String, Object> executionResult = journeyEngine.start(journey, "acc_123", params);
        
        // 3. Handle pause/resume for inputs
        if ("WAITING".equals(executionResult.get("status"))) {
            // Save executionResult.get("context") to DB
            // Tell user we need more info
        } else {
            // Display final executionResult.get("message")
        }
    }
}
```

---

## 6. Known Issues / Limitations

| # | Issue | Severity | Details |
|---|---|---|---|
| 1 | **Synchronous Blocking Context** | ⚠️ Medium | `start()` and `resume()` execute in a completely synchronous, blocking loop. Heavy network operations (like multiple `API_CALL` steps) will freeze the host service thread until the entire flow is complete. |
| 2 | **State Persistence Delegation** | 💡 Low | The SDK itself defines the `ExecutionContext` object but does *not* persist it. If an engine returns `"WAITING"`, the host application is 100% responsible for serializing and storing the context to the database to `resume()` later. |
| 3 | **Basic Branch Evaluation** | 💡 Low | The condition logic `isEligible` inside the engine heavily relies on String equality or Boolean evaluation (`"true"` / `"false"`). Complex rule-based branching requires a very explicit setup. |

---

## 7. Quick Mental Model

Imagine `journey-engine-sdk` as a **Factory Assembly Line Robot**.

- The host application (`speech-service`) is the Factory Manager.
- The Manager hands the Robot an instruction manual (`Journey`).
- The Robot starts reading the steps one-by-one (`JourneyEngineImpl.execute()`).
- At Step 1, it needs to grab a tool (It looks in its toolbox: `StepHandlerRegistry`). It pulls out the `ApiCallStepHandler`.
- At Step 2, the manual says "If Step 1 succeeded, do A. If it failed, do B". The Robot makes the choice (`isEligible()` branch logic).
- Sometimes, at Step 3, the manual says "Wait for the human supervisor to provide a keycard". The Robot completely suspends operations (`status = "WAITING"`), hands its current notebook (`ExecutionContext`) to the Manager, and powers down.
- Once the Manager gets the keycard, they hand the notebook back to the Robot and hit power, and the Robot skips Steps 1-2 and immediately begins Step 3 (`resume()`).

---

## 8. TL;DR

`journey-engine-sdk` is an embeddable workflow executor. You feed it a JSON graph (from `journey-service`), provide some initial variables, and it iteratively executes handlers (`API_CALL`, `MAIL`, `DATA_MAP`) via a Strategy Pattern until it finishes or hits a manual user input pause. It relies on `ai-engine-sdk` internally for LLM-based text extraction steps.
