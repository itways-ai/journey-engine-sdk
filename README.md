# Journey Engine SDK

The Journey Engine SDK is a powerful, stateful orchestration engine designed to execute complex, multi-step workflows (journeys). It features a modular handler architecture, built-in support for AI-driven logic, external API integrations, and robust execution resumption.

## 🚀 Key Features

- **Stateful Execution**: Maintains a persistent `ExecutionContext` for variables and step history.
- **Modular Step Handlers**: Easily extensible architecture for custom business logic.
- **Resumption Support**: Ability to pause journeys (e.g., waiting for user input) and resume later from the exact same state.
- **Logical Branching**: Built-in support for Conditions, Switches, and Intent-based routing.
- **Data Transformation**: Seamless variable management and placeholder replacement across steps.
- **Spring Boot Native**: One-annotation activation via `@EnableJourneyEngine`.

## 🛠 Installation

Include the SDK in your `pom.xml`:

```xml
<dependency>
    <groupId>com.itways.assistant</groupId>
    <artifactId>journey-engine-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

## ⚙️ Configuration

Enable the engine in your Spring Boot configuration:

```java
@SpringBootApplication
@EnableJourneyEngine
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

## 📖 Core Concepts

### 1. Journey & JourneyStep
A `Journey` is a collection of `JourneyStep` objects. Each step defines an `actionType` (e.g., `API_CALL`, `MAIL`) and configuration.

### 2. JourneyEngine
The primary service for lifecycle management.

```java
@Autowired
private JourneyEngine engine;

// Start a journey
Map<String, Object> result = engine.start(journey, accountId, initialParams);

// Resume a journey (after User Input or external callback)
Map<String, Object> resumedResult = engine.resume(journey, context, userInput);
```

### 3. ExecutionContext
Carries the state of a running journey:
- **Variables**: namespaced buckets — `inputs`, `steps`, `state`, `channel`, `runtime`.
- **Step Results**: history of what each step returned.
- **Internal**: engine bookkeeping (nested-journey stack, paused child context).
  Deliberately separate from variables so it never reaches run history, the
  CODE_SCRIPT sandbox, or an LLM prompt.
- **Status**: current lifecycle (RUNNING, WAITING, COMPLETED, ERROR).

### 4. Variables
Values are addressed as `{{steps.3.output.email}}` — see
[docs/Variables.md](../docs/Variables.md) for the buckets, path syntax, and how
unresolved references are reported. `{{ }}` is the only placeholder syntax.

## 🧩 Supported Step Types

| Type | Description |
|------|-------------|
| `API_CALL` | Executes HTTP requests with placeholder support. |
| `CODE_SCRIPT` | Runs JavaScript against the variable buckets (GraalVM). |
| `CONDITION` | Evaluates boolean logic to determine branch eligibility. |
| `DATA_MAP` | AI-extracts structured fields from free text. |
| `DELAY` | Gates the flow until a deadline has passed. |
| `DOCUMENT_INSIGHT` | Reads uploaded documents. |
| `HUMAN_APPROVAL` | Pauses for stakeholder sign-off. |
| `JUMP` | Moves execution back to an earlier step. |
| `KNOWLEDGE_RETRIEVAL` | Vector search over a knowledge base. |
| `REDIRECT` | Resolves a URL for the client to navigate to. |
| `RESPONSE` | Returns a message to the client. |
| `SEND_MAIL` | Sends email notifications. |
| `STATE_STORE` | Writes a value into session `state`. |
| `SWITCH` | Multi-path branching based on variable values. |
| `TEMPLATE_RENDER` | Renders dynamic content via the Template Service. |
| `TRIGGER_JOURNEY` | Runs another journey inline. |
| `USER_INPUT` | Pauses execution and waits for a client response. |

## 🛠 Extending the Engine

To add a new step type, simply implement the `StepHandler` interface and mark it as a `@Component`:

```java
@Component
public class MyCustomHandler implements StepHandler {
    @Override
    public String getType() { return "MY_CUSTOM_ACTION"; }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        // Your logic here
        return StepResult.success("Action completed!");
    }
}
```

## 📝 License

Copyright © 2024 ITWays. All rights reserved.
