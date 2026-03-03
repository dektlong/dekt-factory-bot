# Dekt Factory Bot

An AI-powered manufacturing monitoring bot that analyzes factory data, matches car orders, and delivers actionable insights. Built with [Goose AI agent](https://github.com/block/goose), Spring Boot, and Angular -- all services hosted on **Tanzu Platform**.


> **[Getting Started Guide](GETTING-STARTED.md)** — Learn how to configure LLM providers, add MCP servers, set up skills, and deploy to Cloud Foundry with Tanzu Marketplace integration.

## Overview

Dekt Factory Bot showcases how Tanzu brings 'adult supervision' to Agentic development and deployment while interacting with existing enterprise applications.

It connects to live manufacturing data through MCP servers, applies domain-specific skills for supply chain analysis and car-order matching, and uses document embeddings to ground responses in your own operational documents.

All backing services -- GenAI chat, GenAI embeddings, SSO, databases, and the MCP data servers -- are provisioned and managed on Tanzu Platform.

## Key Capabilities

- **Manufacturing Data Monitoring** -- Connects to factory stage health data via the `dekt-factory-info` MCP server hosted on Tanzu Platform
- **Car Order Matching** -- Generates random car orders via the `dekt-car-orders` MCP server and validates factory capacity before accepting them
- **Skills** -- Pluggable Goose skills for domain-specific analysis:
  - `car-orders-matching` -- Matches a car order against manufacturing stage readiness and accepts or rejects it
  - `supplychain-motivator` -- Supply chain performance insights and recommendations
  - `google-chat-poster` -- Posts alerts and summaries to Google Chat spaces
- **Document Embeddings** -- Upload and embed operational documents (PDFs) for retrieval-augmented responses (see: factory-maintenance-log.pdf), powered by a GenAI embedding service on Tanzu Platform
- **Multi-turn Chat** -- Maintains conversation context across messages with real-time SSE streaming
- **Material Design 3 UI** -- Modern, responsive Angular frontend
- **Authentication** -- Access code auth with optional SSO via Tanzu Platform identity services

## Tanzu Platform Services

All services are bound and managed through Tanzu Platform:

| Service | Description |
|---------|-------------|
| `dekt-genai-chat` | GenAI LLM service for chat completions |
| `dekt-genai-embed` | GenAI embedding service for document vectorization |
| `dekt-factory-info` | MCP server exposing live manufacturing stage health data |
| `dekt-car-orders` | MCP server for generating and managing car orders |
| `dekt-db` | Database for session and document storage |
| `dekt-sso` | SSO identity provider for user authentication |

## Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 22+ (managed by Maven during build)
- Goose CLI (installed via buildpack or locally)

## Tanzu Deployment

```bash
# Build the application
./mvnw clean package -DskipTests

# Create all services in the manifest
cf create-service

# Deploy to Tanzu
cf push
```

## Example usage
```
Check manufacturing stages and current supply chain. Inspect maintenance document.
```
```
Are we ready to paint the next car order?
```
```
Post summary to Google chat
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
│  │  │ dekt-genai-  │  │ dekt-genai-  │  │ dekt-factory-info         │   │  │
│  │  │ chat (LLM)   │  │ embed        │  │ (MCP Server)              │   │  │
│  │  └──────────────┘  └──────────────┘  └───────────────────────────┘   │  │
│  │                                                                       │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐   │  │
│  │  │ dekt-sso     │  │ dekt-db      │  │ dekt-car-orders           │   │  │
│  │  └──────────────┘  └──────────────┘  │ (MCP Server)              │   │  │
│  │                                       └───────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Skills

Skills are pluggable Goose extensions configured in `.goose-config.yml`:

| Skill | Purpose |
|-------|---------|
| `car-orders-matching` | Generates a random car order and validates factory stage readiness (all stages ≥ 80 %) before accepting it |
| `supplychain-motivator` | Adds a motivation sentence to the supply chain data based on performance |
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
