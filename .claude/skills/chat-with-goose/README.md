# Goose Agent Chat Skill

This skill enables Claude Code to interact with the Goose Agent Chat application deployed at https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com/

## Files

- `chat-with-goose.skill.md` - The skill definition that Claude Code uses
- `goose-chat-helper.sh` - Helper script that handles authentication, session management, and messaging
- `README.md` - This file

## Usage

Just ask Claude to chat with Goose and provide the password:

**Examples:**
- "Using password 'tanzu', chat with Goose and ask about Spring Boot best practices"
- "My password is 'tanzu'. Send a message to Goose: What are the latest Java features?"
- "Ask Goose how to optimize this code" (will use cached password or prompt if needed)

Claude will automatically:
1. Get the password from your request, cache, or prompt you for it
2. Authenticate with the application (username: `user`, password from you)
3. Create or reuse a chat session
4. Send your message
5. Display Goose's streaming response

## Direct Script Usage

You can also run the helper script directly:

**With password inline:**
```bash
./.claude/skills/goose-chat-helper.sh --password tanzu "Your message here"
```

**Without password (will prompt or use cache):**
```bash
./.claude/skills/goose-chat-helper.sh "Your message here"
```

**With custom URL:**
```bash
./.claude/skills/goose-chat-helper.sh --url https://goose.mycompany.com --password tanzu "Your message here"
```

## Session Management

- **Sessions** are stored in `/tmp/goose-chat-session.txt`
- **Cookies** are stored in `/tmp/goose-chat-cookies.txt`
- **Password** is cached in `/tmp/goose-chat-password.txt` (chmod 600 for security)
- **URL** is cached in `/tmp/goose-chat-url.txt` (chmod 600 for security)
- Sessions timeout after 30 minutes of inactivity
- The script automatically creates a new session if the current one expires

**Clear cached data:**
```bash
# Start a fresh conversation
rm /tmp/goose-chat-session.txt

# Clear cached password
rm /tmp/goose-chat-password.txt

# Reset to default URL
rm /tmp/goose-chat-url.txt

# Clear all cached data
rm /tmp/goose-chat-*.txt
```

## How It Works

1. **Authentication**: Uses form-based login with credentials from APP_AUTH_SECRET
2. **Session Creation**: Calls `POST /api/chat/sessions` to create a new conversation
3. **Message Streaming**: Calls `GET /api/chat/sessions/{id}/stream` with SSE for real-time responses
4. **Response Parsing**: Parses Server-Sent Events to display token streams and completion status

## Configuration

All configuration is in the helper script and skill definition:

- **App URL:** `https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com` (default)
  - Configurable via `--url` parameter
  - Custom URLs are cached in `/tmp/goose-chat-url.txt`
- **Username:** `user`
- **Password:** Provided by user (value from APP_AUTH_SECRET environment variable)
  - Cached in `/tmp/goose-chat-password.txt` after first use

## Troubleshooting

**Authentication fails:**
- Verify the password matches APP_AUTH_SECRET
- Check that the application is running at the configured URL

**Session creation fails:**
- Ensure you're authenticated
- Check application logs for errors

**No response from Goose:**
- Check that Goose CLI is available on the backend
- Verify GenAI service or LLM provider is configured
