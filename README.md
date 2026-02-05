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
Carries the "state" of a running journey, including:
- **Variables**: Global key-value store accessible by all steps.
- **Step Results**: History of what each step returned.
- **Status**: Current lifecycle (RUNNING, WAITING, COMPLETED, ERROR).

## 🧩 Supported Step Types

| Type | Description |
|------|-------------|
| `API_CALL` | Executes HTTP requests with placeholder support. |
| `CONDITION` | Evaluates boolean logic to determine branch eligibility. |
| `DATA_MAP` | Extracts and transforms variables using JSON paths. |
| `MAIL` | Sends email notifications via configured SMTP. |
| `RESPONSE` | Generates a successesful result for the client. |
| `SWITCH` | Multi-path branching based on variable values. |
| `TEMPLATE` | Renders dynamic content using the Template Service. |
| `INTENT` | Triggers specific AI intents mid-journey. |
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
