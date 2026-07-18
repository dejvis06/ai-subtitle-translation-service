# Project Overview

Build an AI Subtitle Translation Service.

The service receives an .srt subtitle file through a REST endpoint, processes the file, extracts the subtitle text, translates it through AI, replaces the original subtitle text with the translated text, and returns the translated .srt file.

The request must also contain a translateTo parameter specifying the target language.

The generated file must be renamed using the incoming file name with the appropriate language translation extension appended. The file must be provided to the client and once its delivered it needs to be deleted.

## SRT File Structure

The service will receive the file through a REST endpoint and create a copy of it on the filesystem.

After  it, start reading the file.

.srt files have the following structure:

```text
1
00:01:58,991 --> 00:02:00,367
Good luck.

2
00:03:39,216 --> 00:03:42,136
Hi. I have an appointment with...
```

Note the empty line that exists between subtitle blocks.

That empty line means the end of the text that belongs to the current subtitle.

It should therefore be considered by the code as the pointer indicating that there is no more text to be translated for the current subtitle block.

The structure is:

```text
Subtitle Number
Timestamp
Text to Translate
Empty Line

Next Subtitle Number
Timestamp
Text to Translate
Empty Line
```

The subtitle text may contain more than one line. Everything between the timestamp and the empty line belongs to the same subtitle and must be treated as one translation entry.

## File Processing

After receiving the .srt file into memory, copy the file into the filesystem.

Then start reading the copied file.

While reading the file, when reaching the subtitle text:

- Extract the text.
- Add the extracted text to a list.
- The list must maintain insertion order.
- Replace the extracted text inside the copied file with a placeholder.
- The placeholder must use the subtitle number specified by the .srt structure.

Example:

```text
1
00:01:58,991 --> 00:02:00,367
Good luck.
```

Becomes:

```text
1
00:01:58,991 --> 00:02:00,367
{{TRANSLATION_1}}
```

The number specified by the .srt structure is 1, therefore the placeholder is:

```text
{{TRANSLATION_1}}
```

For subtitle:

```text
2
00:03:39,216 --> 00:03:42,136
Hi. I have an appointment with...
```

The placeholder becomes:

```text
{{TRANSLATION_2}}
```

### Important

Before replacing the subtitle text with the placeholder, add the original text to the translation list.

The list must maintain the same order as the subtitles.

Therefore:

```text
Subtitle 1 → first element
Subtitle 2 → second element
Subtitle 3 → third element
...
```

Use the following model:

```java
public record TranslationEntry(
        String placeholder,
        String originalText
) {
}
```

Example:

```java
TranslationEntry(
    "{{TRANSLATION_1}}",
    "Good luck."
)

TranslationEntry(
    "{{TRANSLATION_2}}",
    "Hi. I have an appointment with..."
)
```

After extraction, the copied .srt file should contain placeholders while maintaining the original SRT structure:

```text
1
00:01:58,991 --> 00:02:00,367
{{TRANSLATION_1}}

2
00:03:39,216 --> 00:03:42,136
{{TRANSLATION_2}}
```

## AI Translation

After all subtitle text has been extracted, send the translation list to the AI.

Translations must be processed in batches.

For now, use a fixed maximum of:

```text
20 TranslationEntry objects per iteration
```

Do not worry about performance optimization yet.

Example:

```text
Entries 1–20   → AI request 1
Entries 21–40  → AI request 2
Entries 41–60  → AI request 3
...
```

The order must always be maintained.

The target language comes from the REST request:

```text
translateTo
```

Example:

```text
translateTo=Albanian
```

## AI Request Structure

The AI receives a list containing:

```java
public record TranslationEntry(
        String placeholder,
        String originalText
) {
}
```

Example:

```text
[
    TranslationEntry(
        "{{TRANSLATION_1}}",
        "Good luck."
    ),
    TranslationEntry(
        "{{TRANSLATION_2}}",
        "Hi. I have an appointment with..."
    )
]
```

## AI Response Structure

The AI must keep exactly the same placeholder and only translate the original text.

The response structure must be:

```java
public record TranslatedEntry(
        String placeholder,
        String translatedText
) {
}
```

Example response when translating to Albanian:

```text
[
    TranslatedEntry(
        "{{TRANSLATION_1}}",
        "Paç fat."
    ),
    TranslatedEntry(
        "{{TRANSLATION_2}}",
        "Përshëndetje. Kam një takim me..."
    )
]
```

The placeholder must never be translated or modified.

## AI System Prompt

The AI system prompt must contain two parts:

```text
Static system prompt
+
Runtime target language
```

The static part defines:

- AI identity
- AI responsibility
- Translation rules
- Request structure
- Response structure

The target language must be provided dynamically during runtime using the translateTo request parameter.

The system prompt must instruct the AI that:

- Its only responsibility is translating subtitle text.
- It receives TranslationEntry objects.
- Each entry contains a placeholder and originalText.
- It must translate originalText into the target language.
- It must preserve the exact placeholder.
- It must return TranslatedEntry objects.
- Every received entry must have exactly one corresponding translated entry.
- The response order must match the request order.
- It must not translate placeholders.
- It must not add additional entries.
- It must not remove entries.
- It must not add explanations or unrelated text.
- It must preserve the meaning of the subtitle.
- It must preserve multi-line subtitle structure where applicable.

Conceptually:

```text
You are an AI subtitle translation agent.

Your only responsibility is translating the provided subtitle entries into:

{TARGET_LANGUAGE}

You receive entries with this structure:

TranslationEntry(
    String placeholder,
    String originalText
)

Translate only originalText.

Keep placeholder exactly unchanged.

Return entries with this structure:

TranslatedEntry(
    String placeholder,
    String translatedText
)

Maintain the same number of entries and the same order.

Do not add explanations or additional content.
```

The actual implementation should separate the static prompt from the runtime target language.

## ChatClient Configuration

Use Spring AI with ChatClient.

Configuration:

```java
@Configuration
class ChatClientConfig {

    private static String systemPrompt = ""; // TODO complete

    @Bean
    ChatClient assistant(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .build();
    }
}
```

Complete the static systemPrompt according to the AI translation requirements described above.

The target language must be supplied dynamically during runtime.

Do not introduce:

- Chat memory
- Conversation history
- RAG
- MCP
- Tools
- Agents beyond the translation responsibility
- Other unnecessary AI complexities

The flow should simply be:

```text
TranslationEntry batch
        ↓
ChatClient
        ↓
AI
        ↓
List<TranslatedEntry>
```

Configure Spring AI as follows:

```yaml
spring:
  ai:
    openai:
      api-key: {{OPENAI_API_KEY}}
      chat.options.temperature: 0
      chat.options.model: gpt-5.2
```

Use structured output so that the AI response can be mapped directly into:

```text
List<TranslatedEntry>
```

## Translation Replacement

After receiving each translated batch from the AI, use the returned placeholder to identify where the translated text belongs.

Example:

```text
{{TRANSLATION_1}}
```

with:

```text
Paç fat.
```

Replace:

```text
1
00:01:58,991 --> 00:02:00,367
{{TRANSLATION_1}}
```

with:

```text
1
00:01:58,991 --> 00:02:00,367
Paç fat.
```

Continue until every placeholder has been replaced.

The final file must preserve the original .srt structure.

Example:

```text
1
00:01:58,991 --> 00:02:00,367
Paç fat.

2
00:03:39,216 --> 00:03:42,136
Përshëndetje. Kam një takim me...
```

After all translations have been inserted, return the translated .srt file through the REST response.

## Web Server

Use Jetty instead of Tomcat.

Use:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
    <exclusions>
        <!-- Exclude the Tomcat dependency -->
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-tomcat</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<!-- Use Jetty instead -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jetty</artifactId>
</dependency>
```

## REST API

Expose a REST endpoint that receives:

- file
- translateTo

The file must be an .srt file.

Conceptually:

```http
POST /api/translations
Content-Type: multipart/form-data
```

Parameters:

```text
file=<uploaded .srt file>
translateTo=Albanian
```

The controller must delegate processing to the application layer.

After translation is complete, return the translated .srt file.

## Domain-Driven Design (DDD)

Use Domain-Driven Design (DDD) when generating the project structure.

Each bounded context should be organized independently and follow the same architecture.

Example:

```text
<bounded-context>
├─ domain
│  ├─ model
│  │  ├─ AggregateRoot.java
│  │  ├─ Entity.java
│  │  ├─ ValueObject.java
│  │  ├─ Enum.java
│  │  └─ Identifier.java
│  │
│  ├─ repository
│  │  └─ Repository.java
│  │
│  └─ service
│     ├─ DomainService.java
│     └─ DefaultDomainService.java
│
├─ application
│  ├─ dto
│  │  ├─ Request.java
│  │  └─ Response.java
│  │
│  ├─ port
│  │  ├─ ExternalServiceClient.java
│  │  └─ AnotherExternalServiceClient.java
│  │
│  └─ service
│     └─ ApplicationService.java
│
├─ infrastructure
│  ├─ persistence
│  │  ├─ entity
│  │  │  ├─ JpaEntity.java
│  │  │  └─ ChildJpaEntity.java
│  │  │
│  │  ├─ mapper
│  │  │  └─ Mapper.java
│  │  │
│  │  ├─ repository
│  │  │  ├─ SpringDataRepository.java
│  │  │  └─ RepositoryImpl.java
│  │
│  └─ client
│     ├─ HttpExternalServiceClient.java
│     └─ HttpAnotherExternalServiceClient.java
│
└─ interface
   └─ rest
      └─ Controller.java
```

## Domain Layer

The domain layer represents the business model.

It should contain:

- Aggregates
- Entities
- Value Objects
- Domain Services
- Repository interfaces
- Domain Events when applicable
- Business rules and invariants

The domain layer must not depend on Spring, JPA, HTTP, or infrastructure concerns.

## Application Layer

The application layer coordinates use cases.

It is responsible for:

- orchestrating domain objects
- invoking domain services
- calling repositories
- communicating with external systems through ports
- transaction boundaries
- mapping requests and responses

Business rules should remain in the domain layer whenever possible.

## Infrastructure Layer

The infrastructure layer contains technical implementations.

Typical components include:

- JPA entities
- Spring Data repositories
- Repository implementations
- Mappers
- HTTP clients
- MCP clients/servers
- Messaging
- Database configuration
- External integrations

Infrastructure implements interfaces defined by the domain or application layers.

For this project, persistence is not required.

Do not introduce JPA, a database, repositories, or persistence components unless they become necessary later.

Filesystem handling and Spring AI integration belong in the infrastructure layer.

## Interface Layer

The interface layer exposes the application.

Typical components include:

- REST Controllers
- MCP Tools
- GraphQL Controllers
- Messaging Consumers
- Scheduled Jobs

Controllers should delegate directly to the application layer.