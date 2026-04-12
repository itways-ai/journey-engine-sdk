# 💎 Nixtio Elite AI Modules - Documentation

This guide provides a detailed technical breakdown of the 6 specialized "Elite" modules integrated into the Nixtio Orchestration Engine. These modules enable agentic workflows, long-running processes, and deep intelligence integration.

---

## 1. 🧠 Knowledge Retrieval (RAG)
**Action Type:** `KNOWLEDGE_RETRIEVAL`

This module performs semantic search against a vector database or knowledge base to provide the AI with contextually relevant information before generating a response.

### ⚙️ Configuration (`apiConfig`)
| Field | Type | Description |
| :--- | :--- | :--- |
| `query` | String | The search term or natural language query (supports `{{placeholders}}`). |
| `indexName` | String | The target knowledge base or vector index to search within. |
| `limit` | Integer | Maximum number of knowledge chunks to retrieve (Default: 3). |
| `threshold` | Double | Minimum confidence score for retrieval (0.0 to 1.0). |

### 🚀 Behavior
The engine calls the `AiService` search provider. Retrieval results are injected into the context as `retrieved_knowledge` and `[stepName]_result`, making them available for subsequent `RESPONSE` or `TEMPLATE_RENDER` steps.

---

## 2. 👁️ Document Insight (OCR & extraction)
**Action Type:** `DOCUMENT_INSIGHT`

A high-performance intelligence module designed to extract structured data from files (PDFs, Images) using vision-language models.

### ⚙️ Configuration (`apiConfig`)
| Field | Type | Description |
| :--- | :--- | :--- |
| `strategy` | String | Extraction mode: `OCR_DETAILED`, `FINANCE_SPECIAL` (Invoices), `IDENTITY_PRO` (Passports). |
| `autoExtract` | Boolean | Whether to perform automatic schema mapping (Default: true). |
| `languageHint` | String | Primary language of the document for improved accuracy. |
| `pages` | String | Page range to process (e.g., "1-3", "all"). |

### 🚀 Behavior
It analyzes files attached to the session. Extracted fields are mapped into a structured JSON object and saved to the session context, allowing for automated validation of invoice amounts or identity data.

---

## 3. 🤝 Human Approval (Governance)
**Action Type:** `HUMAN_APPROVAL`

Introduces a "Human-in-the-Loop" gate. The journey execution pauses until a designated stakeholder grants approval.

### ⚙️ Configuration (`apiConfig`)
| Field | Type | Description |
| :--- | :--- | :--- |
| `stakeholders` | String | Comma-separated list of emails or roles required for approval. |
| `instruction` | String | Guidelines for the approver regarding what to check. |
| `timeout` | Integer | (Optional) Minutes to wait before auto-rejecting or escalating. |

### 🚀 Behavior
Execution transitions to `WAITING` status. The UI displays a specialized governance card. Once approved (via a resume event), the journey proceeds to the next step.

---

## 4. 💾 State Store (Persistence)
**Action Type:** `STATE_STORE`

Enables journeys to "remember" data across complex turns by directly manipulating the execution context.

### ⚙️ Configuration (`apiConfig`)
| Field | Type | Description |
| :--- | :--- | :--- |
| `variable` | String | The name of the variable to modify in the session. |
| `operation` | String | `SET` (overwrite), `APPEND` (add to list), `INCREMENT` (counters). |
| `source` | String | The value to store (supports `{{stepResults}}` or static text). |

### 🚀 Behavior
This is a silent background operation. Use it to keep track of retry counts, accumulate items in a shopping cart, or store extraction results for later use.

---

## 5. ⏳ Smart Delay (Temporal Orchestration)
**Action Type:** `DELAY`

Pauses the automation for a specific duration, allowing for time-gated workflows (e.g., "Wait 2 minutes before checking payment status").

### ⚙️ Configuration (`apiConfig`)
| Field | Type | Description |
| :--- | :--- | :--- |
| `duration` | Integer | Amount of time to pause. |
| `unit` | String | `SECONDS`, `MINUTES`, `HOURS`. |

### 🚀 Behavior
Sets a `resumeAt` timestamp in the metadata and transitions to `WAITING`. The journey will only become eligible for execution once the time window has passed.

---

## 6. 📜 Code Script (Logic Engine)
**Action Type:** `CODE_SCRIPT`

Provides a logic bridge for custom data transformations that are too complex for SpEL or simple mapping.

### ⚙️ Configuration (`apiConfig`)
| Field | Type | Description |
| :--- | :--- | :--- |
| `code` | String | The JavaScript code block to execute. |
| `language` | String | Currently supports `javascript`. |

### 🚀 Behavior
Current session variables are injected into the script context. The script can perform calculations, regex filters, or data reformatting. The `return` value is saved to the step result.
