```markdown
# V3SP3R Development Patterns

> Auto-generated skill from repository analysis

## Overview
This skill teaches the core development patterns and conventions used in the V3SP3R TypeScript codebase. You'll learn how to structure files, write imports and exports, follow commit message practices, and organize tests. While no frameworks are enforced, the repository maintains clean, consistent TypeScript code with a focus on modularity and clarity.

## Coding Conventions

### File Naming
- **Style:** kebab-case (all lowercase, words separated by hyphens)
- **Example:**  
  ```
  user-profile.ts
  data-fetcher.test.ts
  ```

### Import Style
- **Style:** Relative imports are used throughout the codebase.
- **Example:**
  ```typescript
  import { fetchData } from './data-fetcher';
  import { UserProfile } from '../models/user-profile';
  ```

### Export Style
- **Style:** Named exports are preferred.
- **Example:**
  ```typescript
  // In user-profile.ts
  export interface UserProfile { ... }
  export function getUserProfile(id: string): UserProfile { ... }
  ```

### Commit Messages
- **Format:** Freeform, no enforced prefixes.
- **Average Length:** ~74 characters.
- **Example:**
  ```
  Add support for fetching user data from new endpoint
  Fix bug in data transformation logic
  ```

## Workflows

### Adding a New Module
**Trigger:** When you need to add a new feature or utility.
**Command:** `/add-module`

1. Create a new file in kebab-case (e.g., `feature-name.ts`).
2. Use relative imports to include dependencies.
3. Export all functions, types, or classes using named exports.
4. If applicable, create a corresponding test file (`feature-name.test.ts`).

### Writing a Test
**Trigger:** When you need to test a module or function.
**Command:** `/write-test`

1. Create a test file with the `.test.ts` suffix (e.g., `feature-name.test.ts`).
2. Place the test file alongside the module or in a dedicated test directory.
3. Follow the same import/export conventions as main code.
4. Use the project's preferred (undetected) testing framework.

### Refactoring Code
**Trigger:** When improving code structure or readability.
**Command:** `/refactor`

1. Update file names to kebab-case if needed.
2. Convert default exports to named exports.
3. Change absolute imports to relative imports.
4. Update any affected test files.

## Testing Patterns

- **Test File Naming:** Use the `.test.ts` suffix (e.g., `data-fetcher.test.ts`).
- **Location:** Test files are typically placed alongside the code they test or in a test directory.
- **Framework:** Not explicitly detected; follow existing patterns.
- **Example:**
  ```typescript
  // data-fetcher.test.ts
  import { fetchData } from './data-fetcher';

  describe('fetchData', () => {
    it('returns expected data', () => {
      // test implementation
    });
  });
  ```

## Commands
| Command         | Purpose                                         |
|-----------------|-------------------------------------------------|
| /add-module     | Scaffold a new module with proper conventions   |
| /write-test     | Create a new test file for a module             |
| /refactor       | Refactor code to match repository conventions   |
```
