# Spring URL Extractor Plugin

An IntelliJ IDEA plugin that extracts complete URLs from Spring controller methods, including the context path from configuration files.

## Features

- 🎯 Extract complete URLs from Spring controller methods
- 📁 Automatically detects context path from `application.yml` and `application.properties`
- 🏷️ Supports all Spring mapping annotations (`@RequestMapping`, `@GetMapping`, `@PostMapping`, etc.)
- 📋 Copies extracted URL to clipboard
- ⌨️ Right-click context menu and keyboard shortcut (`Ctrl+Alt+U`)

## Project Structure

```
src/
├── main/
│   ├── java/com/springurlextractor/
│   │   ├── ExtractUrlAction.java
│   │   └── SpringUrlExtractor.java
│   └── resources/
│       └── META-INF/
│           └── plugin.xml
├── build.gradle
└── README.md
```

## Setup Instructions

### Prerequisites
- IntelliJ IDEA 2025.1 or higher
- Java 11 or higher
- Gradle

### Building the Plugin

1. Clone or create the project with the provided files
2. Place all Java files in `src/main/java/com/example/springurl/`
3. Place `plugin.xml` in `src/main/resources/META-INF/`
4. Run the following commands:

```bash
# Build the plugin
./gradlew buildPlugin

# Run in development mode
./gradlew runIde

# Create distribution ZIP
./gradlew buildPlugin
```

### Installation

1. Build the plugin using `./gradlew buildPlugin`
2. The plugin ZIP will be created in `build/distributions/`
3. In IntelliJ IDEA: **File** → **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk**
4. Select the generated ZIP file

## Usage

### Using Right-Click Menu
1. Open a Spring controller Java file
2. Place your cursor inside a controller method with mapping annotations
3. Right-click and select **"Extract Spring URL"**
4. The complete URL will be displayed and copied to clipboard

### Using Keyboard Shortcut
1. Place cursor inside a controller method
2. Press `Ctrl+Alt+U` (or `Cmd+Alt+U` on Mac)

## Supported Annotations

- `@RequestMapping`
- `@GetMapping`
- `@PostMapping` 
- `@PutMapping`
- `@DeleteMapping`
- `@PatchMapping`

## Configuration File Support

The plugin automatically detects context path from:

### YAML Files (`application.yml` or `application.yaml`)
```yaml
server:
  servlet:
    context-path: /api/v1
# or
server:
  context-path: /api/v1
```

### Properties Files (`application.properties`)
```properties
server.servlet.context-path=/api/v1
# or
server.context-path=/api/v1
```

## Example

Given a controller:

```java
@RestController
@RequestMapping("/users")
public class UserController {
    
    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        // ...
    }
}
```

With `application.yml`:
```yaml
server:
  servlet:
    context-path: /api/v1
```

**Result:** `/api/v1/users/{id}`

## Development

### File Descriptions

- **`plugin.xml`**: Plugin configuration and metadata
- **`ExtractUrlAction.java`**: Main action class that handles user interaction
- **`SpringUrlExtractor.java`**: Core logic for URL extraction and context path detection
- **`build.gradle`**: Build configuration with IntelliJ plugin setup

### Extending the Plugin

To add support for additional frameworks or annotations:

1. Modify `MAPPING_ANNOTATIONS` set in `SpringUrlExtractor.java`
2. Add new annotation parsing logic in `getMethodPath()` method
3. Update configuration file parsing for other frameworks

## Troubleshooting

### Plugin Not Working
- Ensure you're in a Java file
- Verify cursor is inside a method with Spring mapping annotations
- Check that the project contains Spring dependencies

### Context Path Not Detected
- Verify configuration file names (`application.yml`, `application.properties`)
- Check file location (should be in classpath, typically `src/main/resources`)
- Ensure proper YAML/properties syntax

### Build Issues
- Verify Java 11+ is installed
- Check Gradle version compatibility
- Ensure IntelliJ IDEA version matches plugin compatibility

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## License

This plugin is provided as-is for educational and development purposes.