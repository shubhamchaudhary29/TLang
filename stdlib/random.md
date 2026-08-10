# random

## Purpose
Provides utilities for generating random integers, booleans, and making random choices or permutations of elements in lists.

## API

#### `between(min, max)`
- **Signature**: `between(min: Number, max: Number)`
- **Return Type**: `Number`
- **Description**: Generates a random integer between `min` and `max` (inclusive).

#### `boolean()`
- **Signature**: `boolean()`
- **Return Type**: `Boolean`
- **Description**: Generates a random boolean (`true` or `false`).

#### `choice(list)`
- **Signature**: `choice(list: List)`
- **Return Type**: `Object`
- **Description**: Selects and returns a random element from the specified list.

#### `shuffle(list)`
- **Signature**: `shuffle(list: List)`
- **Return Type**: `List`
- **Description**: Returns a new list containing the elements of the input list in a randomized order. The original list is not modified.

---

## Examples

### 1. Simple Random Numbers and Choices
```tiny
import random

let roll be random.between(1, 6)
show "Rolled a: " + roll

let items be ["apple", "banana", "cherry"]
let snack be random.choice(items)
show "Snack: " + snack
```

### 2. Shuffling a List
```tiny
import random

let deck be [1, 2, 3, 4, 5]
let shuffled be random.shuffle(deck)
show shuffled // e.g. [4, 1, 5, 2, 3]
show deck     // [1, 2, 3, 4, 5] (original is unchanged)
```

---

## Errors
- **Argument validation**:
  - `Arguments to 'between' must be integers.`
  - `Argument to 'choice' must be a list.`
  - `Argument to 'shuffle' must be a list.`
- **Invalid Ranges**:
  - If `min` is greater than `max` in `between`: `Min (...) must be less than or equal to Max (...).`
  - Choosing from an empty list: `Cannot choose from an empty list.`

---

## Notes
- **PRNG implementation**: Uses Java's standard pseudo-random number generator (`java.util.Random`) under the hood.
- **Side effects**: `shuffle` duplicates the list before shuffling, preserving the caller's list structure.
