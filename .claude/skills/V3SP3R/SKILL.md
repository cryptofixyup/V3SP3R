```markdown
# V3SP3R Development Patterns

> Auto-generated skill from repository analysis

## Overview
This skill teaches the core development patterns and conventions used in the V3SP3R TypeScript codebase. You'll learn about file naming, import/export styles, commit message habits, and how to write and locate tests. The repository does not use a specific framework, focusing on idiomatic TypeScript and custom workflows.

## Coding Conventions

### File Naming
- Use **camelCase** for file names.
  - Example: `userProfile.ts`, `dataManager.test.ts`

### Import Style
- Use **relative imports** for referencing modules.
  - Example:
    ```typescript
    import { fetchData } from './dataManager';
    ```

### Export Style
- Use **named exports** for all modules.
  - Example:
    ```typescript
    // In userProfile.ts
    export function getUserProfile(id: string) { ... }
    ```

    ```typescript
    // In another file
    import { getUserProfile } from './userProfile';
    ```

### Commit Messages
- Freeform style, no enforced prefixes.
- Average commit message length: ~53 characters.
  - Example: `Fix bug in data fetching logic`

## Workflows

_No automated workflows detected in this repository._

## Testing Patterns

- **Test Framework:** Unknown (no specific framework detected).
- **Test File Pattern:** Files named with `.test.` in the filename.
  - Example: `dataManager.test.ts`
- **Test Structure:** Tests are colocated with source files or in the same directory.

  ```typescript
  // dataManager.test.ts
  import { fetchData } from './dataManager';

  describe('fetchData', () => {
    it('should return expected data', () => {
      // test implementation
    });
  });
  ```

## Commands
| Command | Purpose |
|---------|---------|
| /test   | Run all test files matching `*.test.*` |
| /lint   | Lint the codebase according to TypeScript best practices |
| /build  | Compile the TypeScript codebase |
```