# math

## Purpose
Provides basic mathematical operations for integers, such as absolute values, comparisons, powers, greatest common divisors, and signs.

## API

#### `abs(value)`
- **Signature**: `abs(value: Number)`
- **Return Type**: `Number`
- **Description**: Returns the absolute value of the given integer.

#### `max(a, b)`
- **Signature**: `max(a: Number, b: Number)`
- **Return Type**: `Number`
- **Description**: Returns the larger of the two integers.

#### `min(a, b)`
- **Signature**: `min(a: Number, b: Number)`
- **Return Type**: `Number`
- **Description**: Returns the smaller of the two integers.

#### `pow(base, exponent)`
- **Signature**: `pow(base: Number, exponent: Number)`
- **Return Type**: `Number`
- **Description**: Computes the value of the base raised to the power of the exponent.

#### `gcd(a, b)`
- **Signature**: `gcd(a: Number, b: Number)`
- **Return Type**: `Number`
- **Description**: Computes the greatest common divisor of two integers.

#### `floor_div(a, b)`
- **Signature**: `floor_div(a: Number, b: Number)`
- **Return Type**: `Number`
- **Description**: Divides the first integer by the second, rounding down to the nearest integer.

#### `sign(value)`
- **Signature**: `sign(value: Number)`
- **Return Type**: `Number`
- **Description**: Returns `-1` if the value is negative, `0` if it is zero, and `1` if it is positive.

---

## Examples

### 1. Basic Math Operations
```tiny
import math

show math.abs(-10) // 10
show math.max(10, 20) // 20
show math.min(10, 20) // 10
```

### 2. Powers and Divisions
```tiny
import math

show math.pow(2, 3) // 8
show math.floor_div(7, 2) // 3
show math.floor_div(-7, 2) // -4
```

### 3. Greatest Common Divisor
```tiny
import math

show math.gcd(48, 18) // 6
```

---

## Errors
- **Type Mismatches**: Passing non-integer arguments to any math function throws a `RuntimeError`:
  - `Argument to 'abs' must be an integer.`
  - `Arguments to 'max' must be integers.`
  - `Arguments to 'pow' must be integers.`
- **Invalid Arithmetic**:
  - Exponent value in `pow` must be non-negative: `Exponent must be non-negative.`
  - Dividing by zero in `floor_div`: `Division by zero.`

---

## Notes
- **Integer Limits**: Calculations are subject to the standard limits of 32-bit signed Java integers (range `-2,147,483,648` to `2,147,483,647`). Operations exceeding this range will overflow silently.
- **No Float Support**: In line with the language scope, no functions in this module take or return floating-point values.
