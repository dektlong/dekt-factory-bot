# Dekt Factory Bot

An AI-powered manufacturing monitoring bot that analyzes factory data, audits supply chains, and delivers actionable insights. Built with [Goose AI agent](https://github.com/block/goose), Spring Boot, and Angular -- all services hosted on **Tanzu Platform**.

### Example Usage

```
You:   Check supply chain status, audit factory apps, review risk assessment,
       and post results to Google Chat.

Bot:   On it. Here's what I found:

       Supply Chain Status — Health is at 94%. 2 suppliers flagged for delayed
       shipments (Acme Corp, GlobalParts Inc). Strong momentum — up 3% from
       last week, keep pushing for that 97% target!

       Factory Audit — 12 apps scanned across 3 production lines. All apps
       compliant. 1 recommendation: update firmware on Line-B controller.

       Risk Assessment — 3 CVEs detected across factory apps. 1 critical:
       CVE-2026-1234 in Line-B controller (outdated Spring Boot 3.2).
       Suggested mitigation: upgrade to Spring Boot 3.4.x and patch dependencies.

       Posted summary to dekt-factory space on Google Chat.
```

> **[Getting Started Guide](GETTING-STARTED.md)** — Learn how to configure LLM providers, add MCP servers, set up skills, and deploy to Cloud Foundry with Tanzu Marketplace integration.

## Overview

Dekt Factory Bot connects to live manufacturing data through MCP servers, applies domain-specific skills for supply chain and factory analysis, and uses document embeddings to ground responses in your own operational documents. All backing services -- GenAI chat, GenAI embeddings, SSO, databases, and the MCP data server -- are provisioned and managed on Tanzu Platform.

## Key Capabilities

- **Manufacturing Data Monitoring** -- Connects to factory data via an MCP server (`dekt-factory-data`) hosted on Tanzu Platform
- **Skills** -- Pluggable Goose skills for domain-specific analysis:
  - `supplychain-motivator` -- Supply chain performance insights and recommendations
  - `factory-audit` -- Factory operations auditing and compliance checks
  - `google-chat-poster` -- Posts alerts and summaries to Google Chat spaces
- **Document Embeddings** -- Upload and embed operational documents (PDFs) for retrieval-augmented responses, powered by a GenAI embedding service on Tanzu Platform
- **Multi-turn Chat** -- Maintains conversation context across messages with real-time SSE streaming
- **Material Design 3 UI** -- Modern, responsive Angular frontend
- **Authentication** -- Access code auth with optional SSO via Tanzu Platform identity services

## Tanzu Platform Services

All services are bound and managed through Tanzu Platform:

| Service | Description |
|---------|-------------|
| `dekt-genai-chat` | GenAI LLM service for chat completions |
| `dekt-genai-embed` | GenAI embedding service for document vectorization |
| `dekt-factory-data` | MCP server exposing live manufacturing data |
| `dekt-db` | Database for session and document storage |
| `dekt-sso` | SSO identity provider for user authentication |
| `dekt-tanzu-platform` | Tanzu Platform integration service |

## Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 22+ (managed by Maven during build)
- Goose CLI (installed via buildpack or locally)
- An API key for your chosen LLM provider

## Local Development

### 1. Set Environment Variables

```bash
# Set your preferred provider's API key
export ANTHROPIC_API_KEY=your-api-key
# Or for OpenAI:
# export OPENAI_API_KEY=your-api-key

# Set the path to Goose CLI (if not in PATH)
export GOOSE_CLI_PATH=/path/to/goose
```

### 2. Build and Run

```bash
# Build the application (includes Angular frontend)
./mvnw clean package

# Run the application
./mvnw spring-boot:run
```

### 3. Access the Application

Open http://localhost:8080 in your browser.

### Frontend Development

For faster frontend development with hot reload:

```bash
# Terminal 1: Start the Spring Boot backend
./mvnw spring-boot:run

# Terminal 2: Start Angular dev server
cd src/main/frontend
npm install
npm start
```

The Angular dev server runs on http://localhost:4200 and proxies API requests to the Spring Boot backend.

## Cloud Foundry Deployment

### 1. Create vars.yaml

```yaml
ANTHROPIC_API_KEY: your-api-key
```

### 2. Deploy

```bash
# Build the application
./mvnw clean package -DskipTests

# Deploy to Cloud Foundry
cf push --vars-file vars.yaml
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Tanzu Platform (Cloud Foundry)                                             │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │  dekt-factory-portal (Spring Boot + Angular)                          │  │
│  │                                                                       │  │
│  │  ┌─────────────────────┐      ┌─────────────────────────────────┐    │  │
│  │  │  Angular SPA        │      │  REST Controllers               │    │  │
│  │  │  Material Design 3  │─────▶│  GooseChatController            │    │  │
│  │  │                     │ HTTP │  DocumentController              │    │  │
│  │  │                     │      │  EmbeddingController             │    │  │
│  │  └─────────────────────┘      └─────────────────────────────────┘    │  │
│  │                                         │                             │  │
│  │                                         ▼                             │  │
│  │                              ┌─────────────────────────┐              │  │
│  │                              │  Goose Agent             │              │  │
│  │                              │  + Skills                │              │  │
│  │                              │  + MCP Connections       │              │  │
│  │                              └─────────────────────────┘              │  │
│  └───────────────────────────────────────│───────────────────────────────┘  │
│                                          │                                  │
│  ┌───────────────────────────────────────│───────────────────────────────┐  │
│  │  Tanzu Platform Services              ▼                               │  │
│  │                                                                       │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐   │  │
│  │  │ dekt-genai-  │  │ dekt-genai-  │  │ dekt-factory-data         │   │  │
│  │  │ chat (LLM)   │  │ embed        │  │ (MCP Server)              │   │  │
│  │  └──────────────┘  └──────────────┘  └───────────────────────────┘   │  │
│  │                                                                       │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐   │  │
│  │  │ dekt-sso     │  │ dekt-db      │  │ dekt-tanzu-platform       │   │  │
│  │  │ (Identity)   │  │ (Database)   │  │ (Platform Integration)    │   │  │
│  │  └──────────────┘  └──────────────┘  └───────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Skills

Skills are pluggable Goose extensions configured in `.goose-config.yml`:

| Skill | Purpose |
|-------|---------|
| `supplychain-motivator` | Analyzes supply chain metrics and provides performance recommendations |
| `factory-audit` | Audits factory operations for compliance and efficiency |
| `google-chat-poster` | Posts alerts and summaries to configured Google Chat spaces |

Skills are sourced from [dektlong/agent-skills](https://github.com/dektlong/agent-skills).

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/auth/login` | POST | Form login with username + password (access code) |
| `/auth/status` | GET | Returns current authentication state and user info |
| `/auth/provider` | GET | Detects available SSO provider (used by login page) |
| `/logout` | POST | End the current session |
| `/api/chat/health` | GET | Check Goose availability and version |
| `/api/chat/sessions` | POST | Create a new conversation session |
| `/api/chat/sessions/{id}/messages` | POST | Send message (returns SSE stream) |
| `/api/chat/sessions/{id}/status` | GET | Check session status |
| `/api/chat/sessions/{id}` | DELETE | Close a session |
| `/api/documents/upload` | POST | Upload a document for embedding |
| `/api/embeddings/search` | POST | Search embedded documents |

## Configuration

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| `goose.enabled` | `true` | Enable/disable Goose integration |
| `app.auth.secret` | `changeme` | Shared access code for login (set via `APP_AUTH_SECRET` env var) |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `APP_AUTH_SECRET` | Shared access code for login |
| `GOOSE_CLI_PATH` | Path to Goose CLI binary |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `OPENAI_API_KEY` | OpenAI API key |
| `GOOGLE_API_KEY` | Google AI API key |
| `GOOSE_PROVIDER__TYPE` | Default provider (anthropic, openai, etc.) |
| `GOOSE_PROVIDER__MODEL` | Default model |

## License

MIT License
