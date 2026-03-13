# LittleProxy Agent Guidelines

## Build Commands

```bash
# Compile
mvn compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ActivityLoggerTest

# Run a single test method
mvn test -Dtest=ActivityLoggerTest#testResponseSentToClient

# Run smoke tests (excludes slow tests)
mvn test -Psmoke-test

# Run only slow tests
mvn test -Pslow-tests

# Package
mvn package

# Clean build
mvn clean compile
```

## Code Style & Formatting

This project uses **Spotless** with **Google Java Format**:

```bash
# Check formatting
mvn spotless:check

# Apply formatting
mvn spotless:apply
```

### Formatting Rules
- **Indent**: 2 spaces (no tabs)
- **Line length**: 100 characters
- **Braces**: K&R style (opening brace on same line)
- **Imports**: Ordered and unused imports removed automatically

### Import Order
1. `java.*` imports
2. `javax.*` imports  
3. Third-party libraries (alphabetically)
4. `org.littleshoot.proxy.*` imports
5. Static imports

## Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Classes | PascalCase | `ActivityLogger`, `FlowContext` |
| Interfaces | PascalCase (no I-prefix) | `ActivityTracker`, `LogEntryFormatter` |
| Methods | camelCase | `requestReceivedFromClient()`, `getTimingData()` |
| Variables | camelCase | `flowContext`, `timedRequest` |
| Constants | UPPER_SNAKE_CASE | `UTC`, `LOG` |
| Enums | PascalCase | `LogFormat`, `StandardField` |
| Packages | lowercase | `org.littleshoot.proxy.extras.logging` |

## Type Guidelines

- Use `var` for local variables when type is obvious
- Prefer `final` for fields and parameters when possible
- Use `Optional<T>` for nullable return values
- Use primitive types over boxed types when possible
- Use `Long` for timing values (milliseconds)

## Error Handling

- Use `LOG.error()` with context for unexpected errors
- Use `LOG.warn()` for recoverable issues
- Use `LOG.debug()` for diagnostic information
- Always include `flowId` in log messages for correlation
- Prefer early returns to reduce nesting
- Use specific exception types over generic `Exception`

## Javadoc

- Required for public classes and methods
- Use `@param`, `@return`, `@throws` tags
- Include usage examples for complex methods
- Keep first sentence concise (summary line)

## Testing

- Use JUnit 5 with AssertJ assertions
- Test class naming: `ClassNameTest`
- Test method naming: descriptive with `#` separator
- Use `@Tag("slow-test")` for long-running tests
- Mock external dependencies with Mockito

## Architecture Patterns

- **Strategy Pattern**: For formatters (LogEntryFormatter)
- **Builder Pattern**: For configuration (LogFieldConfiguration)
- **Adapter Pattern**: ActivityTrackerAdapter for optional overrides
- Store timing data in FlowContext, not as parameters
- Use ConcurrentHashMap for thread-safe collections

## Common Pitfalls

- Don't use `System.out.println()` - use SLF4J Logger
- Don't catch generic exceptions without logging
- Don't forget to call `super()` in overridden lifecycle methods
- Don't use blocking operations in Netty event loops
