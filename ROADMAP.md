# API Catalog - MVP Development Roadmap

## Project Objective
Build a web application that automatically extracts API information from source code repositories, allows users to review and edit the extracted information, and stores the approved APIs in a database for browsing and searching.

The application should support **multiple backend frameworks** using a modular parser architecture. Each framework should have its own parser, but the rest of the application should remain framework-independent.

This is an MVP. The focus is **accurate API extraction and cataloging**, not API validation or documentation generation.

---

# Out of Scope
Do **not** implement the following:

- API health checks
- Host URL verification
- Runtime endpoint verification
- OpenAPI generation
- AI/LLM features
- Authentication/Authorization
- Version comparison
- Background jobs
- Message queues
- Event-driven architecture
- Microservices
- CI/CD integration
- Repository webhooks
- API testing

Keep the project as a simple monolithic CRUD application.

---

# Supported Frameworks
The extraction engine should be designed so new frameworks can be added easily.

Target support should include as many of the following as practical:

### Java

- Spring Boot
- JAX-RS (optional)

### JavaScript / TypeScript

- Express.js
- NestJS
- Fastify
- Koa (optional)

### Python

- FastAPI
- Flask
- Django REST Framework

### C#

- ASP.NET Core Web API

### Go

- Gin
- Fiber
- Echo

Framework detection should be automatic based on repository contents.

| Detection Signal | Framework |
|---|---|
| `pom.xml` + Spring dependencies | Spring Boot |
| `package.json` + `express` | Express |
| `package.json` + `@nestjs/core` | NestJS |
| `package.json` + `fastify` | Fastify |
| `requirements.txt` + `fastapi` | FastAPI |
| `requirements.txt` + `flask` | Flask |
| `requirements.txt` + `django` | Django REST |
| `*.csproj` + ASP.NET packages | ASP.NET Core |
| `go.mod` + `gin` | Gin |
| `go.mod` + `fiber` | Fiber |

---

# Architecture
The application should follow this simple workflow.

```
User
 │
 ▼
Submit Repository URL
 │
 ▼
Clone Repository
 │
 ▼
Detect Framework
 │
 ▼
Run Appropriate Parser
 │
 ▼
Extract API Metadata
 │
 ▼
Review & Edit APIs
 │
 ▼
Save Approved APIs
 │
 ▼
Browse Catalog
```

---

# Common Extraction Model
Every parser must return the **same data structure**.

```
ExtractedApi

- method
- path
- description
- controller/module
- handler/function
- parameters
- requestBody
- responseBody
- statusCodes
```

Regardless of whether the repository is Spring Boot, Express, FastAPI, or another supported framework, the output should conform to this model.

The UI and database should only work with this common model.

---

# Phase 1 – Project Setup

**Backend**

- Spring Boot
- PostgreSQL
- Spring Data JPA
- REST API
- Project structure

**Frontend**

- React
- React Router
- Basic navigation
- Home page
- Review page
- Catalog page

**Deliverable**

- Backend running
- Frontend running
- Database connected

---

# Phase 2 – Repository Submission
Create a form where the user submits a repository URL.

Backend should:

- Validate URL
- Clone repository
- Store it temporarily
- Detect project type

**Deliverable**

Repository cloning works.

---

# Phase 3 – Framework Detection
Analyze the repository.

Determine which framework it uses.

If unsupported, return an informative message.

**Deliverable**

Correct framework identification.

---

# Phase 4 – API Extraction
Invoke the correct parser.

Extract as much metadata as possible.

**Minimum fields:**

- HTTP Method
- Endpoint
- Controller / Module
- Handler Function

**Additional fields when available:**

- Description
- Path Parameters
- Query Parameters
- Request Body
- Response Body
- Status Codes

Missing information should simply remain empty.

Never guess values.

**Deliverable**

Return extracted APIs as JSON.

---

# Phase 5 – Review & Edit
Display extracted APIs.

Allow editing of:

- Method
- Path
- Description
- Controller
- Handler
- Parameters
- Request Body
- Response Body
- Status Codes

Changes remain local until the user clicks **Save**.

**Deliverable**

Editable review screen.

---

# Phase 6 – Persistence
Create database tables for:

**Repositories**

- id
- name
- url
- framework
- created_at

**APIs**

- id
- repository_id
- method
- path
- description
- controller
- handler

Additional tables (or JSON columns) may be used for:

- Parameters
- Request Body
- Response Body
- Status Codes

Save only after user approval.

**Deliverable**

Approved APIs stored successfully.

---

# Phase 7 – Catalog
Create a catalog view.

Users should be able to:

- Browse repositories
- View extracted APIs
- View API details

Read-only.

**Deliverable**

Working API catalog.

---

# Phase 8 – Search
Support searching by:

- Repository
- Framework
- HTTP Method
- Endpoint Path

Simple filtering is sufficient.

---

# Development Guidelines

- Keep the project monolithic.
- Keep code modular.
- One parser per framework.
- Avoid unnecessary abstractions.
- Do not implement features outside the roadmap.
- Prefer clarity over cleverness.
- Every phase should result in a working application.
- The application should degrade gracefully: if a repository contains an unsupported framework or unsupported routing pattern, extract whatever can be confidently determined rather than failing the entire extraction process.

---

This roadmap gives you a clean MVP while leaving the parser architecture extensible enough to support many frameworks over time. The only part that grows as you add support is the set of framework-specific parsers; the UI, persistence layer, and catalog remain unchanged.
