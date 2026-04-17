# 💠 Storm Journey Architect: Elite Module Blueprint

Welcome to the definitive guide for **Storm Journey Architect**. This document is designed for business architects, automation specialists, and visionary leaders who want to build high-intelligence AI workflows without getting lost in the code.

Each module below represents a "Neural Block" that you can drop into your journey to add specific logic, intelligence, or interaction capabilities.

---

## 🏗️ Core Logic Modules
*The Foundation of Every Workflow*

### 🔌 API Engine (`API_CALL`)
**The Universal Connector**
- **What it does**: Connects your AI Assistant to the outside world (CRMs, ERPs, Databases, or private APIs).
- **Business Superpower**: Real-time data sync. Pull customer data from Salesforce or push leads to HubSpot instantly.
- **Example Use Case**: Fetching the current status of an order using an Order ID captured in the chat.

**Blueprint Example:**
```json
{
  "method": "GET",
  "queryParams": { "orderId": "{{captured_order_id}}" },
  "headers": { "Authorization": "Bearer nx_prod_8273" }
}
```

---

### 🔀 Switch Logic (`SWITCH`)
**The Traffic Controller**
- **What it does**: Directs the flow based on specific values. If the user says "Sales," go to path A; if "Support," go to path B.
- **Business Superpower**: Multi-department routing without human intervention.
- **Example Use Case**: Routing a customer inquiry based on their subscription tier (Basic vs. Premium).

**Blueprint Example:**
```json
{
  "variable": "subscription_tier",
  "cases": {
    "PREMIUM": "VIP_Support_Path",
    "BASIC": "Standard_Queue"
  }
}
```

---

### ⚖️ Condition Logic (`CONDITION`)
**The Gatekeeper**
- **What it does**: Checks a simple "Yes/No" or "True/False" condition.
- **Business Superpower**: Risk mitigation and automated validation.
- **Example Use Case**: Checking if an applicant is over 18 before proceeding with a lease agreement.

**Blueprint Example:**
```json
{
  "expression": "age >= 18"
}
```

---

## 🎨 Creative & UI Modules
*Designing the User Experience*

### 📄 Template Renderer (`TEMPLATE_RENDER`)
**The Visual Architect**
- **What it does**: Generates beautiful, custom HTML/Markdown UI cards directly in the chat bubble.
- **Business Superpower**: High-end presentation. Instead of text, show a cinematic receipt, a dashboard, or a product catalog.
- **Example Use Case**: Displaying a summarized "Booking Confirmation" card with an embedded map.

**Blueprint Example:**
```json
{
  "format": "html",
  "template": "<h1>Booking Confirmed</h1><p>See you on {{date}}</p>"
}
```

---

**Blueprint Example:**
```json
{
  "message": "Transmission received. Processing your request..."
}
```

---

## 🧠 Intelligence & AI Modules
*The Brain of the Assistant*

### 📂 Knowledge Retrieval (`KNOWLEDGE_RETRIEVAL / RAG`)
**The Library Assistant**
- **What it does**: Searches through your private documents (PDFs, Wikis, Docs) to find specific answers.
- **Business Superpower**: Accurate, non-hallucinating knowledge. The AI only answers based on *your* data.
- **Example Use Case**: Answering HR policy questions based on a 200-page employee handbook.

**Blueprint Example:**
```json
{
  "indexName": "company_handbook_v2",
  "query": "How many days of PTO do I get?",
  "threshold": 0.85
}
```

---

### 👁️ Document Insight (`DOCUMENT_INSIGHT / OCR`)
**The Digital Eye**
- **What it does**: "Sees" and reads uploaded documents, receipts, or IDs.
- **Business Superpower**: Automated data entry. No more manual typing of invoice details.
- **Example Use Case**: Automatically extracting the total amount and tax from a scanned receipt for expense reporting.

**Blueprint Example:**
```json
{
  "strategy": "FINANCIAL_EXTRACTION",
  "autoExtract": true
}
```

---

## 🤝 Human-in-the-loop (HITL)
*Governance & Interaction*

### 👤 User Input (`USER_INPUT`)
**The Interactive Hybrid**
- **What it does**: Captures messy text/voice, extracts fields, and presents a "Fix & Confirm" form if needed.
- **Business Superpower**: Error-free data capture. It combines the speed of voice with the precision of a form.
- **Example Use Case**: Taking a complex shipping order via voice and showing a summary form for final confirmation.

**Blueprint Example:**
```json
{
  "inputMode": "INTERACTIVE",
  "fields": [
    { "name": "quantity", "type": "number", "label": "How many units?" },
    { "name": "address", "type": "text", "label": "Shipping Address" }
  ]
}
```

---

**Blueprint Example:**
```json
{
  "stakeholders": "admin@itways.ai, manager@itways.ai",
  "instruction": "Please verify the discount amount of {{discount}}%",
  "timeout": 3600
}
```

---

## 🛠️ Logic & Utilities
*Connecting the Dots*

**Blueprint Example:**
```json
{
  "variable": "is_returning_user",
  "operation": "SET",
  "source": "TRUE"
}
```

---

**Blueprint Example:**
```json
{
  "to": "{{user_email}}",
  "subject": "Onboarding Complete: {{user_name}}",
  "content": "Welcome to the nexus. Your access is now live."
}
```

---

**Blueprint Example:**
```json
{
  "duration": 5,
  "unit": "MINUTES"
}
```

---

**Blueprint Example:**
```json
{
  "targetStepName": "START_VALIDATION"
}
```

---

**Blueprint Example:**
```json
{
  "url": "https://nexus.ai/checkout/{{order_id}}",
  "target": "_self"
}
```

---

*Storm Journey Architect - Building the Autonomic Future.*
