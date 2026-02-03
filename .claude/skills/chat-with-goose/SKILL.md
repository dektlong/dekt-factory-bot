# Chat with Goose

**Description:** Send messages to the Goose Agent Chat application with automatic authentication and session management.

**Usage:**
- `/chat-with-goose <password> <message>` - Send a message to Goose with password
- Can also be invoked by saying "chat with goose" or "send message to goose"

## Configuration

- **App URL:** https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com (default, configurable)
- **Username:** user
- **Password:** Provided by user (APP_AUTH_SECRET value)
- **Cookie File:** /tmp/goose-chat-cookies.txt
- **Session File:** /tmp/goose-chat-session.txt
- **Password Cache:** /tmp/goose-chat-password.txt
- **URL Cache:** /tmp/goose-chat-url.txt

## Instructions

When the user wants to chat with the Goose Agent Chat application, use the helper script.

### URL Configuration

The script uses `https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com` by default.

**To use a different URL:**

1. **Provide URL inline:**
   ```bash
   ./.claude/skills/chat-with-goose/goose-chat-helper.sh --url https://my-app.example.com --password PASSWORD "Message"
   ```

2. **URL is cached:**
   - Once provided, custom URLs are cached in `/tmp/goose-chat-url.txt`
   - Subsequent calls reuse the cached URL
   - To switch back to default, delete the cache file

### Password Handling

**IMPORTANT:** The user must provide the password. You have three options:

1. **User provides password inline:**
   ```bash
   ./.claude/skills/chat-with-goose/goose-chat-helper.sh --password PASSWORD "User's message here"
   ```

2. **User provides password separately:**
   Ask the user for the password first, then pass it to the script:
   ```bash
   ./.claude/skills/chat-with-goose/goose-chat-helper.sh --password=PASSWORD "User's message here"
   ```

3. **Prompt user for password:**
   If the user doesn't provide a password, the script will prompt for it:
   ```bash
   ./.claude/skills/chat-with-goose/goose-chat-helper.sh "User's message here"
   # Script will display: "Password required for authentication"
   # Script will prompt: "Enter password: "
   ```

**Password Caching:**
- Once provided (via argument or prompt), the password is cached in `/tmp/goose-chat-password.txt`
- Subsequent calls reuse the cached password automatically
- If authentication fails, the cache is cleared and the user must provide the password again
- The cache file has restrictive permissions (600) for security

### Usage Flow

The helper script automatically:
1. Checks if password is provided or cached
2. Prompts for password if needed
3. Checks authentication status
4. Authenticates if needed (using username: `user`, password from user)
5. Gets or creates a chat session (reuses existing active sessions)
6. Sends the message and streams the response
7. Parses SSE events and displays formatted output

### Response Format

The script parses Server-Sent Events (SSE) and displays:
- **Token events**: Goose's text response (concatenated and displayed in real-time)
- **Complete event**: Final token count
- **Error events**: Any errors that occur

Activity events (tool calls) are tracked but not displayed to reduce noise.

### Manual Implementation (if needed)

If the helper script is not available or you need fine-grained control:

1. **Authenticate**: POST to `/auth/login` with form data `username=user&password=tanzu`
2. **Create Session**: POST to `/api/chat/sessions` with empty JSON body
3. **Send Message**: GET to `/api/chat/sessions/{sessionId}/stream?message={urlEncodedMessage}`
4. **Parse SSE**: Extract token events and concatenate the response

See `goose-chat-helper.sh` for detailed implementation.

## Error Handling

- If authentication fails, inform the user and stop
- If session creation fails, show error details
- If the streaming request times out or fails, show the error
- If cookies expire, re-authenticate automatically

## Examples

### Example 1: User provides password inline

User says: "Chat with Goose using password 'tanzu' and ask about Spring Boot best practices"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --password tanzu "What are Spring Boot best practices?"
```

### Example 2: User provides password separately

User says: "My password is 'tanzu'. Now chat with Goose and ask about microservices"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --password=tanzu "Tell me about microservices architecture"
```

### Example 3: No password provided (will prompt)

User says: "Chat with Goose and ask how to optimize database queries"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh "How do I optimize database queries in Spring Boot?"
```

The script will prompt: `Enter password:` and wait for user input.

### Example 4: Using cached password

User says: "Ask Goose another question about Spring Security"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh "Explain Spring Security best practices"
```

The script will use the cached password from previous authentication (no prompt needed).

### Example 5: Using custom URL

User says: "Connect to my Goose instance at https://goose.mycompany.com using password 'secret' and ask about deployment"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --url https://goose.mycompany.com --password secret "How do I deploy to production?"
```

The custom URL will be cached and reused for subsequent requests.

## Notes

- Sessions timeout after 30 minutes of inactivity by default
- The skill maintains state using temporary files for cookies, session ID, password, and URL
- Multiple invocations reuse the same session for conversation continuity
- **Clear cached data:**
  - Fresh conversation: `rm /tmp/goose-chat-session.txt`
  - Clear password: `rm /tmp/goose-chat-password.txt`
  - Reset to default URL: `rm /tmp/goose-chat-url.txt`
  - Clear everything: `rm /tmp/goose-chat-*.txt`
- **Security:** Password and URL are never stored in the skill code, only in temporary files with restrictive permissions (600)
