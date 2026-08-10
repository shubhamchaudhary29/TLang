# filesystem

## Purpose
Provides native file and directory input/output operations, including reading, writing, appending, check existence, and directory management.

## API

#### `read(path)`
- **Signature**: `read(path: String)`
- **Return Type**: `String`
- **Description**: Reads the entire contents of a file as a string.

#### `write(path, content)`
- **Signature**: `write(path: String, content: String)`
- **Return Type**: `Null`
- **Description**: Overwrites a file with the specified string content. Creates the file if it does not exist.

#### `append(path, content)`
- **Signature**: `append(path: String, content: String)`
- **Return Type**: `Null`
- **Description**: Appends the specified string content to the end of a file. Creates the file if it does not exist.

#### `exists(path)`
- **Signature**: `exists(path: String)`
- **Return Type**: `Boolean`
- **Description**: Returns `true` if the file or directory exists, and `false` otherwise.

#### `delete(path)`
- **Signature**: `delete(path: String)`
- **Return Type**: `Boolean`
- **Description**: Deletes a file. Returns `true` if deleted successfully, and `false` if the file did not exist or could not be deleted.

#### `list(path)`
- **Signature**: `list(path: String)`
- **Return Type**: `List` (of Strings)
- **Description**: Lists the names of the files and directories inside the specified path.

#### `mkdir(path)` / `mkdir(path, recursive)`
- **Signature**: `mkdir(path: String, recursive: Boolean = false)`
- **Return Type**: `Boolean`
- **Description**: Creates a directory. If `recursive` is `true`, it creates missing parent directories. Returns `true` if created, `false` if it already exists as a directory.

#### `rmdir(path)` / `rmdir(path, recursive)`
- **Signature**: `rmdir(path: String, recursive: Boolean = false)`
- **Return Type**: `Boolean`
- **Description**: Removes a directory. If `recursive` is `true`, it deletes all children recursively. Returns `true` if removed, `false` if the directory did not exist.

---

## Examples

### 1. File Read and Write
```tiny
import filesystem

let path be "hello.txt"
filesystem.write(path, "Hello from TLang!")
if filesystem.exists(path)
    let text be filesystem.read(path)
    show text // Hello from TLang!
    filesystem.delete(path)
```

### 2. Creating and Listing Directories
```tiny
import filesystem

let dir be "logs/daily"
filesystem.mkdir(dir, true)
filesystem.write("logs/daily/app.log", "Log entry")

let entries be filesystem.list("logs/daily")
show entries.length() // 1

filesystem.rmdir("logs", true)
```

---

## Errors
- **Argument validation**:
  - `Argument to 'read' must be a string.`
  - `Arguments to 'write' must be strings.`
  - `First argument to 'mkdir' must be a string.`
  - `Second argument to 'mkdir' must be a boolean (got ...).`
- **I/O Failures**:
  - Reading a non-existent file: `Error reading file '...': <system error message>`
  - Listing a file instead of a directory: `Path '...' is not a directory or does not exist.`
  - Deleting/removing a directory that is not empty without recursive flag: `Directory '...' is not empty. Use recursive mode to remove non-empty directories.`
  - Trying to run directory commands on a standard file: `Path '...' is not a directory.`

---

## Notes
- **String encoding**: All read and write operations assume UTF-8 text encoding.
- **Blocking operations**: Filesystem operations are synchronous and block the interpreter thread.
- **Platform compatibility**: Path separators are resolved in a platform-independent manner using Java's `java.nio.file.Path`.
