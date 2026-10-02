
# ADR 0001: Record move to Spring Boot and React

## Status
Accepted

## Context
The team decided to standardize on Spring Boot 3.x for backend services and React 19 for the web client.

## Decision
- Backend: Spring Boot 3.2+, Java 21
- Frontend: React 19, TypeScript, Vite, TanStack Router
- Build: Maven for Java, pnpm for web

## Consequences
- Consistent tech stack across all services
- Better developer experience with modern tooling
- Spring Boot 3 requires Java 17+ (we use Java 21)

